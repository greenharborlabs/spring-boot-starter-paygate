// paygate-core: Pure Java library — ZERO external compile dependencies (JDK only).
// Test deps (JUnit 5 + AssertJ) are inherited from the root build.

plugins {
    id("me.champeau.jmh") version "0.7.3"
}

val jacksonVersion: String by extra

dependencies {
    api(project(":paygate-api"))
    testImplementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
}

// JMH configuration — run with: ./gradlew :paygate-core:jmh
jmh {
    warmupIterations.set(2)
    iterations.set(3)
    fork.set(1)
    // Fail on JMH errors so CI catches regressions
    failOnError.set(true)
}
