pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("com.autonomousapps.build-health") version "3.6.1"
}

rootProject.name = "spring-boot-starter-paygate"

include(
    "paygate-api",
    "paygate-core",
    "paygate-lightning-lnd",
    "paygate-lightning-lnbits",
    "paygate-protocol-l402",
    "paygate-protocol-mpp",
    "paygate-spring-autoconfigure",
    "paygate-spring-security",
    "paygate-spring-boot-starter",
    "paygate-example-app",
    "paygate-example-app-spring-security",
)

// Integration test module is excluded from the default build.
// Include ordinary integration tests with -Pintegration, or the isolated live Wavelength spike
// with -PwavelengthSpike. Module inclusion alone never starts live spike work.
if (
    providers.gradleProperty("integration").isPresent ||
        providers.gradleProperty("wavelengthSpike").isPresent
) {
    include("paygate-integration-tests")
}
