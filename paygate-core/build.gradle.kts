// paygate-core: Pure Java library — ZERO external compile dependencies (JDK only).
// Test deps (JUnit 5 + AssertJ) are inherited from the root build.

val jacksonVersion: String by extra

dependencies {
    testImplementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
}
