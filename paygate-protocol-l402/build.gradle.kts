// paygate-protocol-l402: L402 protocol implementation — depends on paygate-api and paygate-core.
// Test deps (JUnit 5 + AssertJ) are inherited from the root build.

dependencies {
    api(project(":paygate-api"))
    implementation(project(":paygate-core"))
}
