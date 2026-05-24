# Release Checklist

Step-by-step process for publishing a new release of `spring-boot-starter-paygate`.

## Prerequisites

- [ ] GPG key configured for signing artifacts
- [ ] Write access to the GitHub repository
- [ ] The following GitHub Actions secrets are configured:
  - `SONATYPE_USERNAME` -- Sonatype OSSRH username
  - `SONATYPE_PASSWORD` -- Sonatype OSSRH password
  - `GPG_SIGNING_KEY` -- ASCII-armored GPG private key
  - `GPG_SIGNING_PASSWORD` -- Passphrase for the GPG key
- [ ] Docker available for the local regtest LND and LND-backed LNbits smoke stacks, or access to equivalent external LND/LNbits instances

## Release Steps

### 1. Run the full release readiness gate

```bash
./gradlew releaseReadiness -Pintegration
```

- [ ] Build, dependency health, integration tests, and aggregate Javadocs all pass

### 2. Smoke test with Docker (manual)

```bash
cd integration-tests

# Direct LND backend
docker compose -f docker-compose-lnd.yml up -d
COMPOSE_FILE=docker-compose-lnd.yml bash scripts/setup-lnd.sh
curl -sf "http://localhost:${APP_PORT:-18080}/api/v1/health"
docker compose -f docker-compose-lnd.yml down -v

# LNbits backend backed by a payee LND node, paid by a separate payer LND node
docker compose -f docker-compose-lnbits-lnd.yml up -d bitcoind lnd lnd-payer
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnd-channel.sh
docker compose -f docker-compose-lnbits-lnd.yml up -d lnbits
COMPOSE_FILE=docker-compose-lnbits-lnd.yml bash scripts/setup-lnbits.sh
docker compose -f docker-compose-lnbits-lnd.yml up -d paygate-example-app
PAYER_BACKEND=lnd-cli bash scripts/run-smoke-test.sh
PAYER_BACKEND=lnd-cli bash scripts/run-mpp-smoke-test.sh
docker compose -f docker-compose-lnbits-lnd.yml down -v
```

- [ ] End-to-end flow works against LND backend
- [ ] L402 smoke flow works against two-node LNbits-over-LND and verifies `sha256(preimage) == payment_hash`
- [ ] MPP smoke flow works against two-node LNbits-over-LND and verifies `sha256(preimage) == payment_hash`
- [ ] MPP key rotation verified: current secret signs new challenges, previous secret still validates in-flight credentials

### 3. Update CHANGELOG.md

- [ ] Move items from `[Unreleased]` into a new version section: `[X.Y.Z] - YYYY-MM-DD`
- [ ] Add comparison link at the bottom of the file

### 4. Bump version in gradle.properties

Remove the `-SNAPSHOT` suffix:

```properties
# Before
version=0.1.0-SNAPSHOT
# After
version=0.1.0
```

- [ ] Version updated

### 5. Commit and tag

```bash
git add gradle.properties CHANGELOG.md
git commit -m "Release v0.1.0"
git tag -a v0.1.0 -m "Release v0.1.0"
```

### 6. Push to trigger the release workflow

```bash
git push origin main
git push origin v0.1.0
```

The `release.yml` GitHub Actions workflow will automatically:
- Build all modules and run tests
- Publish artifacts to Sonatype OSSRH staging
- Close and release the staging repository to Maven Central via `closeAndReleaseSonatypeStagingRepository`

- [ ] Workflow completes successfully in GitHub Actions

### 7. Verify artifacts on Maven Central

- [ ] All modules are present on [Maven Central](https://central.sonatype.com/):
  - `com.greenharborlabs:paygate-core`
  - `com.greenharborlabs:paygate-lightning-lnd`
  - `com.greenharborlabs:paygate-lightning-lnbits`
  - `com.greenharborlabs:paygate-spring-autoconfigure`
  - `com.greenharborlabs:paygate-spring-security`
  - `com.greenharborlabs:paygate-spring-boot-starter`
- [ ] POM metadata, signatures, and javadoc/sources JARs are attached

Note: Maven Central indexing can take up to 30 minutes.

### 8. Bump to next SNAPSHOT

```bash
# Update gradle.properties
version=0.2.0-SNAPSHOT

git add gradle.properties
git commit -m "Prepare next development iteration (0.2.0-SNAPSHOT)"
git push origin main
```

- [ ] Next SNAPSHOT version pushed

### 9. Announce

- [ ] Create a GitHub Release from the tag (copy notes from CHANGELOG.md)

## Rollback

- **Before Maven Central sync**: Drop the staging repository in the [Sonatype UI](https://s01.oss.sonatype.org/). The artifact will not reach Maven Central.
- **After Maven Central sync**: Artifacts are immutable. Publish a patch release (e.g., `0.1.1`) with the fix.
