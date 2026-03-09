val caffeineVersion: String by extra

dependencies {
    api(project(":l402-core"))

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    // Optional dependencies — present at compile time only; consumers bring them if needed
    compileOnly("com.github.ben-manes.caffeine:caffeine:$caffeineVersion")
    compileOnly("io.micrometer:micrometer-core")
    compileOnly("org.springframework.boot:spring-boot-actuator-autoconfigure")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.github.ben-manes.caffeine:caffeine:$caffeineVersion")
}
