plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":l402-spring-boot-starter"))
    implementation(project(":l402-lightning-lnbits"))
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
