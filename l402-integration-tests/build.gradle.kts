plugins {
    `java-library`
}

// This module is excluded from the default build.
// Run with: ./gradlew :l402-integration-tests:test -Pintegration

dependencies {
    testImplementation(project(":l402-example-app"))
    testImplementation(project(":l402-core"))
    testImplementation(project(":l402-spring-boot-starter"))
    testImplementation(project(":l402-spring-autoconfigure"))
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
