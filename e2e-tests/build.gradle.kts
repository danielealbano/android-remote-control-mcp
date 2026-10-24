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
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Testcontainers (core only; the container lifecycle is managed manually in
    // SharedAndroidContainer, so the JUnit Jupiter extension artifact is not needed —
    // and it no longer exists in Testcontainers 2.x).
    testImplementation(libs.testcontainers)

    // Safety floor for patched transitive dependencies pulled via Testcontainers/docker-java
    // (Testcontainers may already resolve equal or newer versions; these guard against regressions):
    // - commons-compress ≥ 1.27.1 (CVE-2024-25710, CVE-2024-26308)
    // - commons-lang3 ≥ 3.18.0 (CVE-2025-48924)
    constraints {
        testImplementation("org.apache.commons:commons-compress:1.27.1")
        testImplementation("org.apache.commons:commons-lang3:3.18.0")
    }

    // MCP SDK client (Streamable HTTP transport)
    testImplementation(libs.mcp.kotlin.sdk.client)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.sse)

    // Free E2E tunnel: OkHttp-backed Ktor client (SNI + DNS override to reach the tunnel edge), raw OkHttp for
    // the engine config and the HTTP/2 check, BouncyCastle for the test internal CA.
    testImplementation(libs.ktor.client.okhttp)
    testImplementation(libs.okhttp)
    testImplementation(libs.bouncy.castle.pkix)
    testImplementation(libs.bouncy.castle.prov)

    // Serialization
    testImplementation(libs.kotlinx.serialization.json)

    // Coroutines
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    // SLF4J logging for Testcontainers diagnostics
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.18")
}

tasks.withType<Test> {
    // Ensure both the main app and compose test app APKs are built before E2E tests run.
    dependsOn(":app:assembleGmsDebug", ":compose-test-app:assembleDebug")

    useJUnitPlatform()

    // Show test stdout/stderr in the console for debugging.
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }

    // Forward Docker/Podman-related environment variables to the forked test JVM
    // so Testcontainers can discover the container runtime environment.
    listOf(
        "DOCKER_HOST",
        "DOCKER_TLS_VERIFY",
        "DOCKER_CERT_PATH",
        "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE",
        "TESTCONTAINERS_RYUK_DISABLED",
    ).forEach { key ->
        System.getenv(key)?.let { value -> environment(key, value) }
    }

    // Ensure DOCKER_HOST is set for the test JVM when running on Linux.
    // Testcontainers 1.20.x may fail to auto-detect the Unix socket without this.
    // Prefer podman rootful socket (required for redroid), fall back to Docker.
    if (!environment.containsKey("DOCKER_HOST")) {
        val podmanSocket = File("/run/podman/podman.sock")
        val dockerSocket = File("/var/run/docker.sock")
        when {
            podmanSocket.exists() -> environment("DOCKER_HOST", "unix:///run/podman/podman.sock")
            dockerSocket.exists() -> environment("DOCKER_HOST", "unix:///var/run/docker.sock")
        }
    }

    // Pass the root project directory so tests can resolve paths relative to the project root.
    systemProperty("project.rootDir", rootProject.projectDir.absolutePath)
}
