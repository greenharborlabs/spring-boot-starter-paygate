val jacksonVersion: String by extra
val mockWebServerVersion: String by extra

dependencies {
    api(project(":l402-core"))

    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")

    testImplementation("com.squareup.okhttp3:mockwebserver:$mockWebServerVersion")
}
