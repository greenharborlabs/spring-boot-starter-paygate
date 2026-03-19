rootProject.name = "spring-boot-starter-l402"

include(
    "l402-core",
    "l402-lightning-lnd",
    "l402-lightning-lnbits",
    "l402-spring-autoconfigure",
    "l402-spring-security",
    "l402-spring-boot-starter",
    "l402-example-app",
)

// Integration test module is excluded from the default build.
// Include with: ./gradlew :l402-integration-tests:test -Pintegration
if (providers.gradleProperty("integration").isPresent) {
    include("l402-integration-tests")
}
