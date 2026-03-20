plugins {
    `java-library`
}

// This module is excluded from the default build.
// Run with: ./gradlew :paygate-integration-tests:test -Pintegration

dependencies {
    testImplementation(project(":paygate-example-app"))
    testImplementation(project(":paygate-core"))
    testImplementation(project(":paygate-spring-boot-starter"))
    testImplementation(project(":paygate-spring-autoconfigure"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("com.squareup.okhttp3:mockwebserver:${project.extra["mockWebServerVersion"]}")
}

tasks.test {
    useJUnitPlatform {
        includeTags("integration")
    }
}

// Disable JaCoCo coverage verification — this module contains only integration tests
tasks.withType<JacocoCoverageVerification> {
    isEnabled = false
}
