plugins {
    `java-library`
}

val grpcVersion: String by extra
val protobufVersion: String by extra

// This module is excluded from the default build.
// Run with: ./gradlew :paygate-integration-tests:test -Pintegration
// Spring Security tests: ./gradlew :paygate-integration-tests:securityTest -Pintegration
// Live Wavelength spike: ./gradlew :paygate-integration-tests:wavelengthSpike -PwavelengthSpike
// Offline Wavelength tests: ./gradlew :paygate-integration-tests:wavelengthSpikeTest -Pintegration

// Separate source sets keep overlapping Spring applications and the funded Wavelength spike out
// of each other's test discovery and out of the ordinary test lifecycle.
val securityTestSourceSet = sourceSets.create("securityTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
val wavelengthSpikeSourceSet = sourceSets.create("wavelengthSpike") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
val wavelengthSpikeTestSourceSet = sourceSets.create("wavelengthSpikeTest") {
    compileClasspath += sourceSets.main.get().output + wavelengthSpikeSourceSet.output
    runtimeClasspath += sourceSets.main.get().output + wavelengthSpikeSourceSet.output
}

val securityTestImplementation by configurations.getting {
    extendsFrom(configurations.implementation.get())
}
val securityTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations.runtimeOnly.get())
    extendsFrom(configurations.testRuntimeOnly.get())
}
val wavelengthSpikeImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val wavelengthSpikeRuntimeOnly by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}
val wavelengthSpikeTestImplementation by configurations.getting {
    extendsFrom(wavelengthSpikeImplementation)
}
val wavelengthSpikeTestRuntimeOnly by configurations.getting {
    extendsFrom(wavelengthSpikeRuntimeOnly)
}

dependencies {
    testImplementation(project(":paygate-example-app"))
    testImplementation(project(":paygate-core"))
    testImplementation(project(":paygate-api"))
    testImplementation(project(":paygate-spring-autoconfigure"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")

    securityTestImplementation(project(":paygate-example-app-spring-security"))
    securityTestImplementation(project(":paygate-spring-autoconfigure"))
    securityTestImplementation(project(":paygate-lightning-lnbits"))
    securityTestImplementation(project(":paygate-lightning-lnd"))
    securityTestImplementation("org.springframework.boot:spring-boot-starter-test")
    securityTestImplementation("org.springframework.boot:spring-boot-starter-web")
    securityTestImplementation("org.springframework.boot:spring-boot-starter-security")
    securityTestImplementation("io.grpc:grpc-inprocess:$grpcVersion")
    securityTestImplementation("io.grpc:grpc-stub:$grpcVersion")
    securityTestImplementation("com.google.protobuf:protobuf-java:$protobufVersion")
}

tasks.test {
    useJUnitPlatform {
        includeTags("integration")
    }
}

val securityTest = tasks.register<Test>("securityTest") {
    description = "Runs Spring Security integration tests"
    group = "verification"
    testClassesDirs = securityTestSourceSet.output.classesDirs
    classpath = securityTestSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
}

val requireWavelengthSpikeOptIn = tasks.register("requireWavelengthSpikeOptIn") {
    description = "Requires explicit opt-in before the funded Wavelength spike can run"

    doLast {
        if (!providers.gradleProperty("wavelengthSpike").isPresent) {
            throw GradleException(
                "wavelengthSpike requires explicit -PwavelengthSpike opt-in; module inclusion alone cannot run live work."
            )
        }
    }
}

val wavelengthSpike = tasks.register<Test>("wavelengthSpike") {
    description = "Runs the explicitly opted-in live Wavelength signet capability spike"
    group = "verification"
    dependsOn(requireWavelengthSpikeOptIn)
    testClassesDirs = wavelengthSpikeSourceSet.output.classesDirs
    classpath = wavelengthSpikeSourceSet.runtimeClasspath
    useJUnitPlatform()
}

val wavelengthSpikeTest = tasks.register<Test>("wavelengthSpikeTest") {
    description = "Runs deterministic offline tests for the Wavelength spike"
    group = "verification"
    testClassesDirs = wavelengthSpikeTestSourceSet.output.classesDirs
    classpath = wavelengthSpikeTestSourceSet.runtimeClasspath
    useJUnitPlatform()
}

// Wire securityTest into the check lifecycle when running with -Pintegration.
// The Wavelength tasks remain explicit-only and are deliberately not lifecycle dependencies.
tasks.named("check") {
    dependsOn(securityTest)
}

// Disable JaCoCo coverage verification — this module contains only integration tests
tasks.withType<JacocoCoverageVerification> {
    isEnabled = false
}
