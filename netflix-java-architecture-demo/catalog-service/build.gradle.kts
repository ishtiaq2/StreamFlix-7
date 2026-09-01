plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.protobuf")
}

val grpcVersion = "1.68.1"
val protobufVersion = "3.25.5"

dependencies {
    implementation("net.devh:grpc-server-spring-boot-starter:3.1.0.RELEASE")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53") // @Generated on the proto stubs, Java 9+

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.grpc:grpc-testing:$grpcVersion")
    // GrpcCleanupRule is a JUnit 4 @Rule specifically (grpc-testing
    // hasn't shipped a JUnit 5 extension equivalent as of this writing) -
    // spring-boot-starter-test pulls in JUnit 5 by default, so JUnit 4
    // needs adding explicitly for this one test class.
    testImplementation("junit:junit:4.13.2")
}

protobuf {
    protobuf.protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    protobuf.plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-java-grpc:$grpcVersion"
        }
    }
    protobuf.generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}
