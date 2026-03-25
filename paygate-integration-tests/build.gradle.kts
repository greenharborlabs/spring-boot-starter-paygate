plugins {
    `java-library`
}

// This module is excluded from the default build.
// Run with: ./gradlew :paygate-integration-tests:test -Pintegration
// Spring Security tests: ./gradlew :paygate-integration-tests:securityTest -Pintegration

// Separate source set for Spring Security integration tests.
// The security example app and the plain example app have overlapping
// component-scanned packages, so they cannot share a classpath.
sourceSets {
    create("securityTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val securityTestImplementation by configurations.getting {
    extendsFrom(configurations.implementation.get())
}
val securityTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations.runtimeOnly.get())
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    testImplementation(project(":paygate-example-app"))
    testImplementation(project(":paygate-core"))
    testImplementation(project(":paygate-api"))
    testImplementation(project(":paygate-protocol-l402"))
    testImplementation(project(":paygate-protocol-mpp"))
    testImplementation(project(":paygate-spring-boot-starter"))
    testImplementation(project(":paygate-spring-autoconfigure"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("com.squareup.okhttp3:mockwebserver:${project.extra["mockWebServerVersion"]}")

    securityTestImplementation(project(":paygate-example-app-spring-security"))
    securityTestImplementation(project(":paygate-core"))
    securityTestImplementation(project(":paygate-api"))
    securityTestImplementation(project(":paygate-protocol-l402"))
    securityTestImplementation(project(":paygate-protocol-mpp"))
    securityTestImplementation(project(":paygate-spring-boot-starter"))
    securityTestImplementation(project(":paygate-spring-autoconfigure"))
    securityTestImplementation("org.springframework.boot:spring-boot-starter-test")
    securityTestImplementation("org.springframework.boot:spring-boot-starter-web")
    securityTestImplementation("org.springframework.boot:spring-boot-starter-security")
}

tasks.test {
    useJUnitPlatform {
        includeTags("integration")
    }
}

val securityTest = tasks.register<Test>("securityTest") {
    description = "Runs Spring Security integration tests"
    group = "verification"
    testClassesDirs = sourceSets["securityTest"].output.classesDirs
    classpath = sourceSets["securityTest"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
}

// Wire securityTest into the check lifecycle when running with -Pintegration
tasks.named("check") {
    dependsOn(securityTest)
}

// Disable JaCoCo coverage verification — this module contains only integration tests
tasks.withType<JacocoCoverageVerification> {
    isEnabled = false
}
