import com.google.protobuf.gradle.id

plugins {
    id("com.google.protobuf") version "0.9.6"
}

val grpcVersion: String by extra
val protobufVersion: String by extra

dependencies {
    api(project(":paygate-core"))

    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")

    // protoc-gen-grpc-java 1.80.0 uses @io.grpc.stub.annotations.GrpcGenerated (no javax/jakarta needed);
    // kept as compileOnly for any hand-written code that may use Jakarta annotations
    compileOnly("jakarta.annotation:jakarta.annotation-api")

    testImplementation("io.grpc:grpc-testing:$grpcVersion")
    testImplementation("io.grpc:grpc-inprocess:$grpcVersion")
}

tasks.withType<JacocoReport> {
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude("lnrpc/**")
            }
        })
    )
}

tasks.withType<JacocoCoverageVerification> {
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude("lnrpc/**")
            }
        })
    )
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
            }
        }
    }
}
