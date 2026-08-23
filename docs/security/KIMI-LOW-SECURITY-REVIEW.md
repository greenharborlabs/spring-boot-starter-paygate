# Kimi Low Phase 11 Security Review

## Decision

**Approved** on 2026-08-23 by **OpenAI Codex (AI security reviewer)** at reviewed implementation
revision `c45e2a9b4d6c9694dcb021d869242270d65356e3`.

This is an AI-performed security review, recorded as such rather than represented as an external
human audit. The review covered the complete feature diff against refreshed `origin/main`, the 28
Low-finding dispositions, their residual risks, the tracked evidence ledger, and the release gate.

## Findings and resolution

The initial review withheld approval for three bounded implementation findings:

1. **Critical — nested Spring Security chain coverage:** nested `FilterChainProxy` inspection could
   accept controls found in one nested branch while a sibling effective chain omitted or misordered
   them. Every nested effective chain is now recursively checked for Paygate authentication,
   authentication-failure throttling, ordering, and dispatcher coverage, with regression tests for
   an unprotected sibling and misordered controls.
2. **Informational — LNbits setup-state symlink handling:** existing setup-secret state could follow
   a symbolic link before permission repair and parsing. Non-regular or symbolic-link state is now
   rejected, and existing state is opened with no-follow semantics before descriptor-level mode
   repair and parsing. A negative test verifies rejection without changing target permissions.
3. **Informational — temporary test preimage copy:** test-mode challenge formatting retained a
   temporary preimage byte-array copy and requested it before confirming validated test mode. The
   accessor is now limited to validated test mode and the copy is zeroized in a `finally` block.

All three corrections are committed in the reviewed revision. No unresolved critical, high,
medium, or low implementation finding remains within the Phase 11 review scope.

## Approval evidence

- The original quickstart, compatibility, web, Lightning, provenance, quality, fixture, advisory,
  ledger, and release evidence remains recorded under `LOW-EV-CORE` through `LOW-EV-RELEASE`.
- Targeted regression tests, Spotless, and PMD passed for the corrected boundaries.
- `LOW-EV-RELEASE-REVIEWED` records a successful exact `releaseReadiness -Pintegration` execution
  at the reviewed revision: 599 tasks, exit status 0.
- `LOW-EV-SECURITY-REVIEW` identifies this decision and its reviewed revision.

Approval must be revisited when any disposition's documented review trigger occurs, including
changes to credential boundaries, Spring filter-chain topology, Lightning credential handling,
dependency trust inputs, or supplied integration fixtures.
