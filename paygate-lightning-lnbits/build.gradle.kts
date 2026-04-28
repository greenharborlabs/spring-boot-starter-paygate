val mockWebServerVersion: String by extra

dependencies {
    api(project(":paygate-core"))

    implementation("tools.jackson.core:jackson-databind")

    testImplementation("com.squareup.okhttp3:mockwebserver:$mockWebServerVersion")
}
