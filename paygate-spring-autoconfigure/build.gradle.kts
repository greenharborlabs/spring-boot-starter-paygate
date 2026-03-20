val caffeineVersion: String by extra
val grpcVersion: String by extra
val jacksonVersion: String by extra

dependencies {
    api(project(":paygate-core"))

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    // Optional dependencies — present at compile time only; consumers bring them if needed
    compileOnly("com.github.ben-manes.caffeine:caffeine:$caffeineVersion")
    compileOnly("io.micrometer:micrometer-core")
    compileOnly("org.springframework.boot:spring-boot-actuator-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-health")

    // Lightning backend modules — optional; consumers bring the one they need
    compileOnly(project(":paygate-lightning-lnbits"))
    compileOnly(project(":paygate-lightning-lnd"))
    compileOnly("io.grpc:grpc-netty-shaded:$grpcVersion")
    compileOnly("io.grpc:grpc-stub:$grpcVersion")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("com.github.ben-manes.caffeine:caffeine:$caffeineVersion")
}
