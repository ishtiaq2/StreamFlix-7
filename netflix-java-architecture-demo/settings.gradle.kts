rootProject.name = "netflix-java-architecture-demo"

include(":graphql-service")
include(":catalog-service")

// virtual-threads-demo is deliberately NOT a Gradle module — it's plain
// javac/java, zero dependencies, compiled and run directly (see its own
// directory and the root README). Keeping it outside the Gradle build
// keeps the one genuinely-verified-in-this-sandbox piece independent of
// the parts that need Maven Central, which this sandbox can't reach.
