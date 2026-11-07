import java.util.Properties

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version libs.versions.kotlin.get()
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

ktlint {
    version.set("1.8.0")
}

// Same rationale as :app — ktlint-cli 1.8.0 bundles a CVE-flagged logback used only at build time.
configurations.matching { it.name.startsWith("ktlint") }.configureEach {
    resolutionStrategy {
        force(
            "ch.qos.logback:logback-core:1.5.34",
            "ch.qos.logback:logback-classic:1.5.34",
        )
    }
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

fun compareVersions(
    left: String,
    right: String,
): Int {
    val a = left.split('.').map { it.toIntOrNull() ?: 0 }
    val b = right.split('.').map { it.toIntOrNull() ?: 0 }
    return a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: a.size.compareTo(b.size)
}

fun ndkClang(): File {
    val properties = Properties()
    rootProject
        .file("local.properties")
        .takeIf { it.isFile }
        ?.inputStream()
        ?.use(properties::load)
    val sdk =
        listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            properties.getProperty("sdk.dir"),
        ).map(::File)
            .firstOrNull { File(it, "ndk").isDirectory }
            ?: error("Android NDK not found: set ANDROID_HOME or sdk.dir and install an NDK")
    val ndk =
        File(sdk, "ndk")
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .maxWithOrNull { a, b -> compareVersions(a.name, b.name) }
            ?: error("No NDK in ${File(sdk, "ndk")}")
    return File(ndk, "toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android33-clang")
}

val testSuBinary = layout.buildDirectory.file("testsu/x86_64/su")

val compileTestSu =
    tasks.register<Exec>("compileTestSu") {
        val source = file("src/test/native/testsu.c")
        inputs.file(source)
        outputs.file(testSuBinary)
        doFirst {
            val output = testSuBinary.get().asFile
            output.parentFile.mkdirs()
            commandLine(ndkClang().absolutePath, "-O2", "-o", output.absolutePath, source.absolutePath)
        }
    }

tasks.withType<Test> {
    // Ensure both the main app and compose test app APKs are built before E2E tests run.
    dependsOn(":app:assembleGmsDebug", ":compose-test-app:assembleDebug")
    dependsOn(compileTestSu)
    systemProperty("e2e.testSu", testSuBinary.get().asFile.absolutePath)

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
