plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":paygate-spring-boot-starter"))
    implementation(project(":paygate-lightning-lnbits"))
    implementation(project(":paygate-lightning-lnd"))
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
