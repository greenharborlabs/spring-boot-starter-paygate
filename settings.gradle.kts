rootProject.name = "spring-boot-starter-paygate"

include(
    "paygate-core",
    "paygate-lightning-lnd",
    "paygate-lightning-lnbits",
    "paygate-spring-autoconfigure",
    "paygate-spring-security",
    "paygate-spring-boot-starter",
    "paygate-example-app",
)

// Integration test module is excluded from the default build.
// Include with: ./gradlew :paygate-integration-tests:test -Pintegration
if (providers.gradleProperty("integration").isPresent) {
    include("paygate-integration-tests")
}
