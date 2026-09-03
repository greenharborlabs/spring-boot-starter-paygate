# Address Spring dependency advisory
Status: review
Base revision: 00612df5c3c6ec2a2d5060605f1aeea74c75d072
Review scope: build.gradle.kts; gradle/verification-metadata.xml

## Goal
Resolve the failing GitHub Dependency advisory run by removing reported Spring Framework/Spring Security vulnerable versions from the Dependency-Check aggregate scan. Acceptance evidence: `./gradlew dependencyCheckAggregate --no-daemon` no longer reports CVE-2026-47892, CVE-2026-47893, CVE-2026-59280, CVE-2026-59283, CVE-2026-59313, CVE-2026-59314, or CVE-2026-59270 for Spring artifacts.

## Constraints
Keep the fail-closed Dependency-Check policy (`failBuildOnCVSS = 0F`) and do not broadly suppress real Spring advisories. Preserve Java 25/Gradle build conventions and existing Spring Boot dependency-management pattern.

## Decisions
Upgrade to patched Spring-managed releases, preferably by moving the Spring Boot BOM/plugin from 4.0.7 to the latest compatible 4.0.x release and only adding explicit Spring Framework/Security overrides if the BOM does not clear the advisories. Reject adding suppression rules for these CVEs because the workflow identified actual Spring libraries, not a known false-positive package-name collision.

## Tasks
- [x] Confirm fixed Spring Boot/Spring Framework/Spring Security versions and update the central Gradle version constants/plugin declarations.
- [x] Run dependency resolution or Dependency-Check to verify Spring artifacts move off vulnerable versions.
- [x] Run the focused dependency advisory check and record the result.

## Review
Implementation committed in 31eb58d; awaiting fresh-context review.

## Outcome
Spring Boot upgraded to 4.0.8, which resolves Spring Framework to 7.0.9 and Spring Security to 7.0.7; dependency verification metadata was refreshed for the upgraded dependency set. Verification: `./scripts/validate-dependency-provenance.sh` passed; `./gradlew dependencyCheckAggregate --rerun-tasks --no-daemon` passed and found 0 vulnerabilities.

## Next action
Run fresh-context review.
