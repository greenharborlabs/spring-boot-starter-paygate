// paygate-core: Pure Java library — ZERO external compile dependencies (JDK only).
// Test deps (JUnit 5 + AssertJ) are inherited from the root build.

plugins {
    id("me.champeau.jmh") version "0.7.2"
}

dependencies {
    api(project(":paygate-api"))
    testImplementation("tools.jackson.core:jackson-databind")
}

// JMH configuration — run with: ./gradlew :paygate-core:jmh
jmh {
    includes.set(listOf(providers.gradleProperty("jmhInclude").getOrElse(".*L402PriceValidationBenchmark.*")))
    resultFormat.set("JSON")
    resultsFile.set(
        layout.projectDirectory.file(
            providers.gradleProperty("jmhResultFile").getOrElse("build/reports/jmh/l402-price.json")
        )
    )
    warmupIterations.set(2)
    iterations.set(3)
    fork.set(1)
    // Fail on JMH errors so CI catches regressions
    failOnError.set(true)
}
