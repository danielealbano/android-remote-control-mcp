plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version libs.versions.kotlin.get()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Testing framework
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)

    // Testcontainers
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)

    // MCP SDK client (Streamable HTTP transport)
    testImplementation(libs.mcp.kotlin.sdk.client)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.sse)

    // Serialization
    testImplementation(libs.kotlinx.serialization.json)

    // Coroutines
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    // SLF4J logging for Testcontainers diagnostics
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Show test stdout/stderr in the console for debugging.
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }

    // Forward Docker-related environment variables to the forked test JVM
    // so Testcontainers can discover the Docker environment.
    listOf("DOCKER_HOST", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH", "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE").forEach { key ->
        System.getenv(key)?.let { value -> environment(key, value) }
    }

    // Ensure DOCKER_HOST is set for the test JVM when running on Linux with Docker CE.
    // Testcontainers 1.20.x may fail to auto-detect the Unix socket without this.
    if (!environment.containsKey("DOCKER_HOST") && File("/var/run/docker.sock").exists()) {
        environment("DOCKER_HOST", "unix:///var/run/docker.sock")
    }

    // Pass the root project directory so tests can resolve paths relative to the project root.
    systemProperty("project.rootDir", rootProject.projectDir.absolutePath)
}
