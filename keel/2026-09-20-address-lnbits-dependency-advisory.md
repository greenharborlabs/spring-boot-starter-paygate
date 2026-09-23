# Address LNbits Dependency Advisory

**Created at:** `901de77` on `2026-09-20` | **Mode:** `eng`

## Summary

Resolve the September 19 dependency-advisory failure without hiding a real vulnerability. Upgrade the two local Docker fixtures from affected LNbits `0.12.11` to the current supported `v1.6.2` image, preserve a narrowly scoped suppression for the false-positive match against Paygate's Java adapter, make the risk validator enforce its documented rules, and add automated Docker image update and vulnerability coverage.

The plan deliberately spans more than eight files because the failure crossed four existing control surfaces: integration infrastructure, Dependency-Check disposition evidence, validation tests, and dependency automation. Limiting the change to a new suppression date would restore CI but leave the affected fixture and the validator/scanner gaps intact.

## Existing Code Leverage

- `integration-tests/docker-compose-lnbits.yml` and `integration-tests/docker-compose-lnbits-lnd.yml` already isolate LNbits to host loopback and share one bootstrap contract.
- `integration-tests/scripts/setup-lnbits.sh` already supports both older and newer LNbits initialization and wallet APIs; retain this compatibility unless the upgraded image proves a concrete mismatch.
- `config/dependency-check-suppressions.xml` already scopes CVE-2025-32013 to the exact Paygate Maven package URL and substitutes the current project version during the build.
- `config/dependency-check-risk-dispositions.md` already records why the Java artifact is distinct from the affected Python server.
- `scripts/validate-dependency-check-risk-dispositions.sh` and `scripts/test-dependency-check-risk-dispositions.sh` provide the existing policy gate and negative-test entry point.
- `.github/workflows/dependency-advisory.yml` already runs daily, uploads reports on failure, and is protected by `scripts/test-dependency-advisory-workflow.sh`.
- `.github/dependabot.yml` already manages Gradle and GitHub Actions updates and can add Docker coverage for `integration-tests/`.

## Architecture

```text
Docker Compose fixtures ----> supported, digest-pinned LNbits image
          |                              |
          |                              +--> daily container vulnerability scan
          +--> Dependabot Docker updates

Paygate Java adapter ----> OWASP Dependency-Check ----> exact PURL + CVE suppression
                                                        |
                                                        +--> parsed approval/expiry/evidence gate
```

The Maven suppression remains specific to `com.greenharborlabs:paygate-lightning-lnbits`; it must never suppress or imply acceptance of the actual LNbits server vulnerability.

## Blast Radius

| Modified File/Interface | Consumers | Covered by Work Unit? |
| --- | --- | --- |
| `integration-tests/docker-compose-lnbits.yml` | FakeWallet setup and invoice-check flows | W1-01 |
| `integration-tests/docker-compose-lnbits-lnd.yml` | Full L402/MPP LNbits-over-LND flows | W1-01 |
| `integration-tests/scripts/setup-lnbits.sh` | Both Compose stacks; edit only if v1.6.2 verification exposes incompatibility | W1-01 |
| `integration-tests/README.md`, `integration-tests/PLAYBOOK.md` | Developer setup and troubleshooting | W1-01 |
| `scripts/validate-dependency-check-risk-dispositions.sh` and new Python parser | CI, `check`, and release-readiness policy gates | W1-02 |
| `scripts/test-dependency-check-risk-dispositions.sh` and `scripts/test-fixtures/dependency-check-risk/*` | Negative controls for suppression policy | W1-02 |
| `build.gradle.kts` | Validator task registration and root `check` wiring | W1-02 |
| `scripts/validate-low-security-fixtures.sh`, `scripts/test-low-security-fixtures.sh`, `test-lnd-credential-permissions.sh` | Direct Compose safety consumers; verification only unless compatibility changes are required | W1-01 |
| `FixtureSafetyIT`, `ExampleBootstrapIT` | Java integration consumers of the Docker fixtures; verification only unless compatibility changes are required | W1-01 |
| `.github/dependabot.yml` | Automated dependency update PRs | W2-01 |
| `.github/workflows/dependency-advisory.yml` | Daily Java and LNbits-container advisory checks | W2-01 |
| `scripts/validate-container-scan-report.py` and report fixtures | Shared, locally testable fixable-vulnerability policy | W2-01 |
| `scripts/test-dependency-advisory-workflow.sh`, new container-report negative controls, and `build.gradle.kts` task wiring | Static and behavioral workflow security controls | W2-01 |
| `config/dependency-check-suppressions.xml` | OWASP Dependency-Check aggregate scan | W3-01 |
| `config/dependency-check-risk-dispositions.md` | Maintainer review and release evidence | W3-01 |

## Risk Flags

`security`: yes | `performance`: no | `migration`: yes | `public-api`: no | `concurrency`: no

## Wave 1: Remove the Real Exposure and Repair Preventive Controls

### W1-01: Upgrade and verify the LNbits integration fixtures

Replace `lnbits/lnbits:0.12.11` in both Compose files with the current supported multi-architecture image `lnbits/lnbits:v1.6.2@sha256:284b9c2a0df9a1f867b4c52b694699fccade513c09368879922ff90bc7bf850e`. Start from fresh disposable volumes to avoid unsupported pre-1.0 migration state. Update the setup script only if executed tests show an API or startup-contract change, and remove the obsolete LNbits 0.12 troubleshooting guidance.

**Files:** `integration-tests/docker-compose-lnbits.yml`, `integration-tests/docker-compose-lnbits-lnd.yml`, `integration-tests/scripts/setup-lnbits.sh` (conditional), `integration-tests/README.md`, `integration-tests/PLAYBOOK.md`

**Acceptance criteria:**
- Both Compose files resolve the identical digest-pinned `v1.6.2` LNbits image; neither contains an affected `<0.12.12` tag.
- `docker compose -f <file> config` succeeds for both stacks.
- A fresh FakeWallet stack reaches `/api/v1/health`, completes `setup-lnbits.sh`, creates an admin key, starts the example app, and creates an invoice.
- A fresh LNbits-over-LND stack completes channel/bootstrap setup and passes both L402 and MPP smoke tests with payer-side preimage verification.
- Existing loopback host bindings, disposable-secret handling, TLS certificate mounting, and non-logging of API keys remain intact.
- Documentation names the supported pinned release and current reset/troubleshooting flow without claiming support for LNbits 0.12.
- Existing `0.12.11` data volumes are explicitly unsupported because these are disposable test fixtures; documentation requires `docker compose ... down -v --remove-orphans` before starting the upgraded image.

**Error handling:** Health timeout, first-install failure, login failure, wallet-creation failure, LND certificate/macaroon incompatibility, and missing payment preimages must fail closed with the existing actionable messages. Do not add a fallback to the vulnerable image.

**Tests:** Docker Compose validation plus fresh-volume integration smoke tests.

**Test spec:**
- Run `docker compose -f docker-compose-lnbits.yml config` and `docker compose -f docker-compose-lnbits-lnd.yml config`.
- From `integration-tests/`, reset the FakeWallet stack, run `COMPOSE_FILE=docker-compose-lnbits.yml bash scripts/setup-lnbits.sh`, start the example app, and verify health plus invoice creation.
- Reset the full stack, run `GENERATE_CREDENTIAL=true COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnbits-lnd-stack.sh`, then run `PAYER_BACKEND=lnd-cli bash scripts/run-smoke-test.sh` and `PAYER_BACKEND=lnd-cli bash scripts/run-mpp-smoke-test.sh`.
- Inspect logs and committed changes to confirm no generated `.env`, setup secret, API key, macaroon, or preimage is emitted or tracked.
- Run `./gradlew validateLowSecurityFixtures verifyLowSecurityFixtureNegativeControls verifyLndCredentialPermissionControls --no-daemon`.
- Run `./gradlew :paygate-integration-tests:test --tests '*ExampleBootstrapIT' -Pintegration --no-daemon` and `./gradlew :paygate-integration-tests:securityTest --tests '*FixtureSafetyIT' -Pintegration --no-daemon`.

### W1-02: Make the disposition validator enforce current approval evidence

Replace the current keyword-presence checks with deterministic validation of every suppression and risk record. Keep the shell entry point but delegate parsing to a small Python 3 standard-library validator using `xml.etree.ElementTree` for the namespaced XML and an explicit Markdown grammar: one `## CVE-NNNN-NNNN — title` section with unique labeled fields for Disposition, Scope, Owner, Approval, Approval date, Status, Review date, Compensating controls, and Scanner evidence. Require exact advisory scope, an exact non-wildcard package URL, a future `until` date, a matching risk-record section, owner, `Status: Approved`, approval reference/date, review date, compensating controls, and scanner evidence. Compare the recorded Dependency-Check version with the plugin version in `build.gradle.kts`, and reject orphaned records as well as suppressions without records.

Refactor the fixtures so every negative case has readable XML and Markdown inputs; each test must fail for its named policy violation rather than because the companion file is missing. Add `VALIDATION_DATE`, defaulting to the current UTC date in production, and run all fixtures with `VALIDATION_DATE=2026-09-20` so expiry behavior never depends on wall-clock time.

**Files:** `scripts/validate-dependency-check-risk-dispositions.sh`, `scripts/validate-dependency-check-risk-dispositions.py` (new), `scripts/test-dependency-check-risk-dispositions.sh`, `scripts/test-fixtures/dependency-check-risk/*`, `build.gradle.kts`

**Acceptance criteria:**
- The current expired suppression fails with a message that names its expired `until` date.
- A valid exact-PURL suppression with a future deadline and complete approved record passes.
- Missing approval, broad scope, unmatched CVE, orphaned record, expired deadline, missing compensating controls, and stale scanner evidence each fail for their named reason.
- The validator is runner-portable and does not depend on `xmllint`, GNU-only date flags, network access, or optional packages.
- `validateDependencyCheckRiskDispositions` and `verifyDependencyCheckRiskNegativeControls` are direct dependencies of root `check`, remain explicit CI/release gates, and have a task-graph regression assertion so future edits cannot silently detach them.
- `VALIDATION_DATE` accepts only canonical `YYYY-MM-DD`; invalid or externally supplied noncanonical values fail closed.

**Error handling:** Malformed XML/Markdown, duplicate advisory records, missing files, unparseable ISO dates, absent plugin version, and ambiguous matches must fail closed with a single actionable diagnostic.

**Tests:** Shell policy tests through the existing Gradle tasks.

**Test spec:**
- Run `./gradlew validateDependencyCheckRiskDispositions` before renewing the suppression and assert that it fails specifically for `2026-09-15`.
- Run `./gradlew verifyDependencyCheckRiskNegativeControls` against complete paired fixtures and assert that every mutation is rejected for its intended reason.
- Run `VALIDATION_DATE=2026-09-20 bash scripts/test-dependency-check-risk-dispositions.sh` and assert stable expiry results.
- Run `./gradlew check --dry-run --no-daemon` and assert that both disposition tasks occur in the root check graph; exercise the task-graph negative fixture/assertion as well.
- After W3-01, rerun both tasks and require success for the repository files while all negative fixtures continue to fail.

## Wave 2: Automate Docker Detection

### W2-01: Add Docker update and multi-platform vulnerability coverage

Begin by scanning the selected `v1.6.2` index and its `linux/amd64` and `linux/arm64` manifests to establish a recorded baseline. If a fixable HIGH or CRITICAL finding exists, adopt a newer supported digest or stop for maintainer triage; do not add a broad exception merely to make the new job green. The enforcement gate fails on fixable HIGH/CRITICAL findings while the uploaded report retains unfixed findings for review.

Add a weekly Dependabot Docker entry for `/integration-tests` so Compose image tags and digests receive update PRs. Extend the daily dependency-advisory workflow with a separate least-privilege job that extracts the resolved `lnbits` service image from both Compose files, confirms they are identical, resolves both supported platform manifests, and scans each platform with a pinned scanner action/tool version. Keep the Java Dependency-Check report and per-platform container reports separate so a Maven false positive cannot obscure an affected server image.

Extract the enforcement decision into `scripts/validate-container-scan-report.py`. The workflow passes each machine-readable platform report to this script, and local fixture tests exercise the same code. The parser must reject malformed or incomplete reports, fail for HIGH/CRITICAL findings with a nonempty fixed version, and report without failing for unfixed findings.

**Files:** `.github/dependabot.yml`, `.github/workflows/dependency-advisory.yml`, `scripts/validate-container-scan-report.py` (new), `scripts/test-container-scan-report.sh` (new), `scripts/test-fixtures/container-scan/*` (new), `scripts/test-dependency-advisory-workflow.sh`, `build.gradle.kts`

**Acceptance criteria:**
- Dependabot has a weekly `docker` ecosystem entry for `/integration-tests`, targeting `main` and using the existing `dependencies` label.
- The scheduled/manual workflow scans the exact LNbits digest resolved from both Compose files for both `linux/amd64` and `linux/arm64`, and fails on fixable HIGH or CRITICAL findings from either platform.
- The workflow does not duplicate the image coordinate in a separate hard-coded variable that can drift from Compose.
- Scanner/action references are immutable SHA pins, permissions remain `contents: read`, reports upload with `if: always()`, and retention remains at most 14 days.
- Static workflow tests reject removal of the container job, Compose image equality check, platform resolution, either platform scan, severity/fixability gate, immutable pin, or report upload.
- The baseline result and selected gate behavior are recorded in the PR evidence; if either platform cannot be resolved or scanned, the job fails rather than silently reducing coverage.
- The workflow and local tests call the same report validator; no GitHub-only action behavior contains the final pass/fail policy.

**Error handling:** Missing Compose files, missing/empty image resolution, divergent LNbits images between stacks, missing AMD64/ARM64 manifest, scanner setup failure, malformed or missing report fields, scan failure, and missing report artifacts must all fail the job. Report upload must still run after a vulnerability failure. An unavoidable unfixed finding is reported but does not weaken the fixable-vulnerability gate; any exception to that policy requires a separate maintainer decision.

**Tests:** Dependabot configuration validation and expanded workflow negative controls.

**Test spec:**
- Parse `.github/dependabot.yml` and assert exactly one Docker entry covers `/integration-tests`.
- Run `./gradlew verifyDependencyAdvisoryWorkflowControls` and its negative-control task after adding mutations for each required container-scan control.
- Run the container scan command locally against both platform manifests of the digest-pinned LNbits image before editing the workflow; retain both machine-readable reports as verification evidence without committing them.
- Run `scripts/test-container-scan-report.sh` with clean, fixable HIGH, fixable CRITICAL, unfixed HIGH, malformed, and empty-report fixtures; assert only clean and unfixed reports pass.
- Run the registered Gradle negative-control task for the report validator and require success.
- Seed one missing-platform mutation in isolated workflow tests and assert it fails before report evaluation.

## Wave 3: Obtain Risk Approval and Close the Incident

### W3-01: Reapprove the exact Maven suppression and verify all gates

After the real LNbits fixture is upgraded and the validator is enforcing dates, renew only the existing `CVE-2025-32013` suppression for `pkg:maven/com.greenharborlabs/paygate-lightning-lnbits@PAYGATE_VERSION@`. Set a bounded future deadline consistent with the repository's review cadence, update the risk record to `Approved`, cite the September 19 CI report and current reproduced scanner evidence, and record the fixture upgrade. Do not suppress the LNbits Docker image or broaden the rule to a CPE, regex, artifact-name pattern, or all versions.

Avoid circular approval evidence with a two-stage PR flow: open the remediation as a draft with the disposition marked pending, obtain explicit maintainer approval, then add a final commit containing the PR number, named approver, approval date, and `Status: Approved` before required checks and merge. A merge commit is not acceptable pre-merge evidence.

**Orchestration gate:** Stop after Waves 1-2 and open the draft PR. Do not begin this work unit until a maintainer has approved the exact false-positive disposition. Record that approval in the final commit, then run the complete gate set. This human approval is a one-way security decision and is intentionally excluded from parallel execution.

**Files:** `config/dependency-check-suppressions.xml`, `config/dependency-check-risk-dispositions.md`

**Acceptance criteria:**
- The suppression contains one CVE, the exact Paygate Maven package URL template, and a future bounded `until` date.
- The risk record has matching scope and date, explicit owner and approval status/reference, current Dependency-Check 13.0.0 evidence, and states that the actual LNbits fixtures now use a non-affected supported release.
- The approval reference exists before final CI: it names the PR, approver, and approval date, and the PR review is visible to maintainers.
- A forced `dependencyCheckAggregate` scan reports no unsuppressed CVE-2025-32013 finding for the Paygate Java adapter and no unused suppression.
- All disposition validators, workflow controls, formatting checks, and the standard build pass.
- A final repository search finds no `lnbits/lnbits:0.12.11`, expired `2026-09-15` review, or stale `Status: Proposed` text.

**Error handling:** If the scanner no longer produces the false-positive match, remove the suppression instead of renewing it. If the PURL, CVE, or evidence differs from the reviewed report, stop and re-triage rather than widening the rule.

**Tests:** Fresh vulnerability-data scan and normal project verification.

**Test spec:**
- Run `./gradlew dependencyCheckAggregate --rerun-tasks --no-daemon` and inspect the JSON report for zero unsuppressed vulnerabilities and the expected scoped suppression behavior.
- Run `./gradlew validateDependencyCheckRiskDispositions verifyDependencyCheckRiskNegativeControls verifyDependencyAdvisoryWorkflowControls spotlessCheck build --no-daemon`.
- Run `rg -n 'lnbits/lnbits:0\.12\.11|2026-09-15|Status: Proposed' integration-tests config` and require no matches.

## NOT in Scope

- Changes to the Paygate Java LNbits client API or HTTP behavior, unless the v1.6.2 integration tests prove a concrete compatibility defect.
- Operating or upgrading third-party production LNbits deployments; document that operators must run a currently supported release.
- Broad container scanning of every integration service in this change; the new job covers the LNbits image implicated by this incident, while Dependabot covers Docker references across the directory.
- Suppressing CVE-2025-32013 for the LNbits server image.

## Security Considerations

- Treat the Maven match and the Docker server finding as separate trust decisions. The Maven suppression applies only to Green Harbor Labs' Java coordinate; the server image must be upgraded and scanned.
- Pin the LNbits tag and multi-architecture digest so a mutable registry tag cannot change the tested bytes. Dependabot should update both through reviewed PRs.
- Preserve loopback-only host publishing and the existing Docker-network boundary. Do not expose LNbits or the example app on all interfaces as part of the upgrade.
- Bootstrap credentials, API keys, LND macaroons, and payment preimages remain local secrets. Verification must confirm they are neither logged nor committed.
- Container and Java scan failures remain fail-closed. A scanner outage is an operational failure, not a reason to silently pass the advisory workflow.

## Failure Modes Summary

| Codepath | Failure Mode | Handled In | Tested? |
| --- | --- | --- | --- |
| LNbits Compose startup | v1.6.2 config/API incompatibility or failed migration | W1-01 fresh-volume setup and explicit failure messages | Yes |
| Full payment proof | LNbits/LND flow omits or mismatches the preimage | W1-01 existing L402/MPP smoke tests | Yes |
| Risk validator | Expired, broad, unapproved, unmatched, orphaned, or stale evidence accepted | W1-02 parsed policy gate, injected date, and paired negative fixtures | Yes |
| Dependency automation | Docker image remains stale without update PRs | W2-01 Docker Dependabot entry | Configuration test |
| Container advisory job | Image drift, malformed report, missing platform scan, or fixable critical vulnerability passes CI | W2-01 shared report parser, per-platform scan, and workflow controls | Yes |
| Maven advisory scan | False positive blocks CI or broad suppression hides a real CVE | W3-01 exact PURL/CVE renewal and forced fresh scan | Yes |

## Architect Review Findings

### Auto-Incorporated

- Added root `check` wiring and a task-graph regression assertion for both disposition controls.
- Replaced grep-oriented validation with namespaced XML parsing, an explicit Markdown grammar, and an injectable deterministic validation date.
- Added all known direct Compose safety/integration consumers to the blast radius and verification commands.
- Required explicit AMD64 and ARM64 scans, per-platform reports, and failure when either platform is missing.
- Added a baseline-first policy that blocks fixable HIGH/CRITICAL findings while continuing to report unfixed findings.
- Defined a two-stage PR approval flow so the risk record has real pre-merge approval evidence.
- Declared old disposable LNbits volumes unsupported and required documented destructive reset rather than an untested in-place migration.
- Extracted container-report enforcement into a shared local parser with behavioral fixtures, avoiding an untestable GitHub-only policy.
- Added a third-wave orchestration gate so suppression renewal cannot proceed until the draft PR has explicit maintainer approval.
- Corrected the fixture-upgrade language to require fresh disposable volumes rather than implying that in-place migration is tested.

### Resolved with User Input

None.

### Deferred

None.

## Confidence Assessment

| Dimension | Score | Source | Notes |
| --- | --- | --- | --- |
| Architecture | HIGH | Architect review + repository exploration | The four control surfaces, their consumers, and cross-wave dependencies are explicit. |
| Error Handling | HIGH | Architect delta review | Parser, date, image/platform, report, bootstrap, and scanner failures all fail closed with named behavior. |
| Test Strategy | MEDIUM | Architect delta review, then D1 incorporated | Coverage is concrete and shares the production report parser; full Docker smoke tests remain environment-dependent. |
| Security | HIGH | Architect delta review | Exact PURL scope, immutable image digest, multi-platform scanning, secret handling, and approval evidence are preserved. |
| Migration | HIGH | Plan decision | Test data is disposable; unsupported pre-1.0 volumes are explicitly reset instead of silently migrated. |

**Gate result:** Passed after one review round and one delta review. There are no unresolved CRITICAL findings or LOW-confidence dimensions.

## Orchestration Playbook

Expected intermediate state: Waves 1-2 use their targeted verification commands, but root `check` and PR CI remain intentionally red because the repository suppression is expired. Do not weaken the validator to make that state green. Open the draft PR, obtain approval, and complete Wave 3 before requiring the full gate set to pass.

```bash
# Wave 1: remove the affected fixture and repair validation
/greenharbor-orchestrate keel/2026-09-20-address-lnbits-dependency-advisory.md --scope "Wave 1"

# Wave 2: add Docker update and vulnerability automation
/greenharbor-orchestrate keel/2026-09-20-address-lnbits-dependency-advisory.md --scope "Wave 2"

# Open a draft PR and obtain explicit maintainer approval, then run Wave 3
/greenharbor-orchestrate keel/2026-09-20-address-lnbits-dependency-advisory.md --scope "Wave 3"
```
