plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val mcpVersion = "0.5.0"
val slf4jVersion = "2.0.9"
val ktorVersion = "3.1.1"
val postgresVersion = "42.7.2"

dependencies {
    // MCP Kotlin SDK - try latest version
    implementation("io.modelcontextprotocol:kotlin-sdk:$mcpVersion")

    // PostgreSQL driver
    implementation("org.postgresql:postgresql:$postgresVersion")

    // HikariCP connection pooling
    implementation("com.zaxxer:HikariCP:5.0.1")

    // Logging
    implementation("org.slf4j:slf4j-nop:$slf4jVersion")

    // HTTP client for potential future use
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // JSON handling
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

// Configure the shadow plugin to create a fat JAR
tasks.shadowJar {
    archiveClassifier.set("")
    archiveVersion.set("") // Remove version from JAR filename

    // Require jarSuffix property to distinguish different database JARs
    val jarSuffix = project.findProperty("jarSuffix") as String?
        ?: throw GradleException("jarSuffix property is required. Use: ./gradlew shadowJar -PjarSuffix=<database-name>")

    archiveBaseName.set("${project.name}-$jarSuffix")

    manifest {
        attributes["Main-Class"] = "PostgreSqlMcpServerKt"
    }
}