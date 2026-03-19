# Release Checklist

Step-by-step process for publishing a new release of `spring-boot-starter-l402`.

## Prerequisites

- [ ] GPG key configured for signing artifacts
- [ ] Write access to the GitHub repository
- [ ] The following GitHub Actions secrets are configured:
  - `SONATYPE_USERNAME` -- Sonatype OSSRH username
  - `SONATYPE_PASSWORD` -- Sonatype OSSRH password
  - `GPG_SIGNING_KEY` -- ASCII-armored GPG private key
  - `GPG_SIGNING_PASSWORD` -- Passphrase for the GPG key
- [ ] Access to an LND node and LNbits instance for smoke testing

## Release Steps

### 1. Run the full build

```bash
./gradlew build
```

- [ ] All modules compile and unit tests pass

### 2. Run integration tests

```bash
./gradlew :l402-integration-tests:test -Pintegration
```

- [ ] Integration tests pass against Lightning backends

### 3. Smoke test with Docker (manual)

```bash
docker-compose up -d
# Exercise the example app: confirm 402 challenge/payment/access flow
# Verify health endpoint reports healthy for LND and LNbits backends
docker-compose down
```

- [ ] End-to-end flow works against LND backend
- [ ] End-to-end flow works against LNbits backend

### 4. Update CHANGELOG.md

- [ ] Move items from `[Unreleased]` into a new version section: `[X.Y.Z] - YYYY-MM-DD`
- [ ] Add comparison link at the bottom of the file

### 5. Bump version in gradle.properties

Remove the `-SNAPSHOT` suffix:

```properties
# Before
version=0.1.0-SNAPSHOT
# After
version=0.1.0
```

- [ ] Version updated

### 6. Commit and tag

```bash
git add gradle.properties CHANGELOG.md
git commit -m "Release v0.1.0"
git tag -a v0.1.0 -m "Release v0.1.0"
```

### 7. Push to trigger the release workflow

```bash
git push origin main
git push origin v0.1.0
```

The `release.yml` GitHub Actions workflow will automatically:
- Build all modules and run tests
- Publish artifacts to Sonatype OSSRH staging
- Close and release the staging repository to Maven Central via `closeAndReleaseSonatypeStagingRepository`

- [ ] Workflow completes successfully in GitHub Actions

### 8. Verify artifacts on Maven Central

- [ ] All modules are present on [Maven Central](https://central.sonatype.com/):
  - `com.greenharborlabs:l402-core`
  - `com.greenharborlabs:l402-lightning-lnd`
  - `com.greenharborlabs:l402-lightning-lnbits`
  - `com.greenharborlabs:l402-spring-autoconfigure`
  - `com.greenharborlabs:l402-spring-security`
  - `com.greenharborlabs:l402-spring-boot-starter`
- [ ] POM metadata, signatures, and javadoc/sources JARs are attached

Note: Maven Central indexing can take up to 30 minutes.

### 9. Bump to next SNAPSHOT

```bash
# Update gradle.properties
version=0.2.0-SNAPSHOT

git add gradle.properties
git commit -m "Prepare next development iteration (0.2.0-SNAPSHOT)"
git push origin main
```

- [ ] Next SNAPSHOT version pushed

### 10. Announce

- [ ] Create a GitHub Release from the tag (copy notes from CHANGELOG.md)

## Rollback

- **Before Maven Central sync**: Drop the staging repository in the [Sonatype UI](https://s01.oss.sonatype.org/). The artifact will not reach Maven Central.
- **After Maven Central sync**: Artifacts are immutable. Publish a patch release (e.g., `0.1.1`) with the fix.
