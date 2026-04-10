val mockWebServerVersion: String by extra

dependencies {
    api(project(":paygate-core"))

    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("com.squareup.okhttp3:mockwebserver:$mockWebServerVersion")
}
