plugins {
    id("org.springframework.boot") version "3.5.6" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    // Netflix's own upgrade philosophy, per the video: upgrade sooner and
    // more often, invest in tooling so each individual bump is painless
    // rather than deferring into one large, risky migration. This root
    // build intentionally pins current, real Spring Boot 3.x — matching
    // where the video says Netflix actually is ("fully on Spring Boot 3
    // now... starting migration to Spring Boot 4") — rather than jumping
    // straight to 4, which is exactly the kind of not-yet-battle-tested
    // move their own philosophy argues against doing all at once.
    id("com.google.protobuf") version "0.9.4" apply false
}

allprojects {
    group = "com.example.streaming"
    version = "0.1.0"

    repositories {
        mavenCentral()
        // NOTE: unreachable from the sandbox this repo was built in
        // (network egress there allowlists GitHub and package registries
        // for a few other ecosystems, not Maven Central) — see the root
        // README's "what's verified vs not" section. This is exactly
        // what a normal machine or CI runner needs and will reach fine.
    }
}

subprojects {
    apply(plugin = "java")

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
