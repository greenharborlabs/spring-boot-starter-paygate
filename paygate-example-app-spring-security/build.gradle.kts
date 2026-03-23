plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":paygate-spring-boot-starter"))
    implementation(project(":paygate-spring-security"))
    implementation(project(":paygate-lightning-lnbits"))
    implementation(project(":paygate-lightning-lnd"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
}
