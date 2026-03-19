import com.google.protobuf.gradle.id

plugins {
    id("com.google.protobuf") version "0.9.4"
}

val grpcVersion: String by extra
val protobufVersion: String by extra

dependencies {
    api(project(":l402-core"))

    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")

    // Required for generated gRPC code — protoc-gen-grpc-java emits @javax.annotation.Generated
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")

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
