plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        // The DGS BOM aligns DGS's own modules AND their transitive
        // dependencies (Spring, Jackson, graphql-java) - the platform-
        // dependencies variant, not the narrower graphql-dgs-platform,
        // specifically so this doesn't fight the Spring Boot dependency-
        // management plugin over versions. Confirmed against Netflix's
        // own current docs (netflix.github.io/dgs/advanced/platform-bom)
        // before writing this, not guessed.
        mavenBom("com.netflix.graphql.dgs:graphql-dgs-platform-dependencies:10.2.4")
    }
}

dependencies {
    implementation("com.netflix.graphql.dgs:graphql-dgs-spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // The gRPC client side of "GraphQL/gRPC over REST, think methods not
    // data" - this service's data fetchers call catalog-service over
    // gRPC rather than REST. Using the community grpc-spring starter
    // (net.devh / now grpc-ecosystem/grpc-spring) specifically because
    // its exact API (@GrpcClient, stub injection) was directly confirmed
    // against real, current documentation while writing this repo - the
    // newer official org.springframework.grpc starter exists too, but
    // wasn't verified closely enough here to commit to its exact
    // annotations without guessing. Worth re-checking both when actually
    // building this, since this space is moving fast.
    implementation("net.devh:grpc-client-spring-boot-starter:3.1.0.RELEASE")
    implementation(project(":catalog-service")) // for the generated proto stubs only

    testImplementation("com.netflix.graphql.dgs:graphql-dgs-spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
