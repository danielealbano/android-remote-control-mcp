import org.gradle.process.ExecOperations
import java.io.FileInputStream
import java.time.YearMonth
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    jacoco
}

/**
 * Derives the version name from `git describe` output.
 *
 * Output formats handled:
 * - "v1.2.3"              → exact tag    → "1.2.3"
 * - "v1.2.3-7-gabc1234"   → after tag    → "1.2.3-dev.7+abc1234"
 * - "v1.2.3-beta-7-g..."  → pre-release  → "1.2.3-beta-dev.7+abc1234"
 * - "abc1234"              → no tags      → "0.0.0-dev+abc1234"
 *
 * Returns null when git is unavailable or the command fails.
 */
fun getGitDescribeVersion(): String? {
    return try {
        val process =
            ProcessBuilder("git", "describe", "--tags", "--match", "v*", "--always")
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
        val output =
            process
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
        val exitCode = process.waitFor()
        if (exitCode != 0 || output.isEmpty()) return null

        // Describe pattern checked first: the -N-gHASH suffix is unambiguous.
        // Using (.+) for the version part lets the regex engine backtrack correctly
        // even when pre-release segments contain hyphens (e.g. v1.0.0-rc1-3-g1234567).
        val describePattern = Regex("""^v(.+)-(\d+)-g([0-9a-f]+)$""")
        val tagPattern = Regex("""^v(\d+\.\d+\.\d+(?:-.+)?)$""")

        when {
            describePattern.matches(output) -> {
                val match = describePattern.find(output)!!
                val baseVersion = match.groupValues[1]
                val commitCount = match.groupValues[2]
                val hash = match.groupValues[3]
                "$baseVersion-dev.$commitCount+$hash"
            }

            tagPattern.matches(output) -> {
                tagPattern.find(output)!!.groupValues[1]
            }

            else -> {
                "0.0.0-dev+$output"
            }
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Runs `git <args>` in the repo root, capturing stdout only. stderr is discarded so a
 * git warning/hint can never be merged into and corrupt the parsed output. Returns
 * (exitCode, trimmed stdout), or null if the process could not be started.
 */
fun runGit(vararg args: String): Pair<Int, String>? =
    try {
        val process =
            ProcessBuilder(listOf("git", *args))
                .directory(rootDir)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        val output =
            process
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
        Pair(process.waitFor(), output)
    } catch (_: Exception) {
        null
    }

/**
 * True when the working tree is a shallow clone, detected via the `shallow` marker file
 * in the COMMON git dir (shared across linked worktrees, so `--git-common-dir` is
 * required — `--git-dir` points at a worktree's private dir and would miss it).
 * `--git-common-dir` exists since git 2.5; on older/absent git this returns false.
 */
fun isShallowClone(): Boolean {
    val gitCommonDir = runGit("rev-parse", "--git-common-dir") ?: return false
    if (gitCommonDir.first != 0) return false
    return rootDir.resolve(gitCommonDir.second).resolve("shallow").exists()
}

/**
 * Derives the numeric version code from git history.
 *
 * `versionCode = (2_000_000 + commitCount) * 10 + (dirty ? 1 : 0)` where:
 * - `commitCount` is `git rev-list --count HEAD` — strictly monotonic on `main`, so
 *   every commit/merge auto-increments the code with no manual bumping.
 * - The `2_000_000` base clears the ceiling of the previous scheme (max 1_090_099 for
 *   v1.9.0), so codes under the new scheme never regress below already-shipped builds.
 * - The trailing digit marks a dirty working tree (uncommitted changes to tracked
 *   files): a local dirty build sorts one above the clean build at the same commit
 *   without ever colliding with the next commit's code.
 *
 * Returns null only when git is unavailable (no repository / git absent), so the caller
 * fails the build rather than falling back to a hardcoded code. A shallow clone still
 * derives a (truncated) code here so config-only work — `help`, dependency resolution,
 * IDE sync — is not broken; shipping a shallow (wrong) code is prevented separately by
 * the release-artifact guard (see gradle.taskGraph.whenReady below).
 */
fun getGitVersionCode(): Int? {
    val (countExit, countOutput) = runGit("rev-list", "--count", "HEAD") ?: return null
    if (countExit != 0) return null
    val commitCount = countOutput.toIntOrNull() ?: return null

    // `git diff --quiet HEAD` exits 1 when tracked files differ from HEAD (staged or
    // unstaged); untracked files are intentionally ignored. Any other non-zero exit is
    // a git error, not a dirty tree, so bail rather than mislabel it.
    val (dirtyExit, _) = runGit("diff", "--quiet", "HEAD") ?: return null
    val dirty =
        when (dirtyExit) {
            0 -> false
            1 -> true
            else -> return null
        }

    return (2_000_000 + commitCount) * 10 + if (dirty) 1 else 0
}

val isExplicitVersion =
    project.gradle.startParameter
        .projectProperties
        .containsKey("VERSION_NAME")
val fallbackVersion = project.findProperty("VERSION_NAME") as String? ?: "1.0.0"
val versionNameProp =
    if (isExplicitVersion) fallbackVersion else (getGitDescribeVersion() ?: fallbackVersion)
val isExplicitVersionCode =
    project.gradle.startParameter
        .projectProperties
        .containsKey("VERSION_CODE")
val versionCodeProp =
    if (isExplicitVersionCode) {
        // An explicit -PVERSION_CODE override MUST be a valid integer; fail loudly
        // rather than silently degrading to a fallback and shipping a wrong code.
        val raw = project.findProperty("VERSION_CODE") as String
        raw.toIntOrNull() ?: error("VERSION_CODE must be an integer, got: \"$raw\"")
    } else {
        // The version code is ALWAYS derived from git — there is no hardcoded fallback.
        // Fail only when git is entirely unavailable (no repository); a shallow clone
        // still derives a code so config-only tasks (help, dependency resolution, IDE
        // sync) are not broken. A shallow RELEASE build is rejected by the guard below.
        getGitVersionCode()
            ?: error(
                "Cannot derive versionCode: no git repository found. Build from a git " +
                    "checkout, or pass an explicit -PVERSION_CODE=<integer>.",
            )
    }

// A release/bundle artifact must carry a full-history git-derived code. A shallow
// checkout yields a truncated (wrong, too-low) count, so refuse to build one from a
// shallow tree — but only for actual release-artifact tasks, so config-only work
// (help, dependency resolution, IDE sync) on a shallow checkout is unaffected.
gradle.taskGraph.whenReady {
    val buildingReleaseArtifact =
        !isExplicitVersionCode &&
            gradle.taskGraph.allTasks.any { task ->
                task.name.contains("Release") &&
                    (
                        task.name.startsWith("assemble") ||
                            task.name.startsWith("bundle") ||
                            task.name.startsWith("package")
                    )
            }
    if (buildingReleaseArtifact && (getGitVersionCode() == null || isShallowClone())) {
        error(
            "Refusing to build a release artifact without a full-history git versionCode " +
                "(missing repository or shallow clone). Build from a full git checkout, or " +
                "pass an explicit -PVERSION_CODE=<integer>.",
        )
    }
}

ktlint {
    version.set("1.8.0")
}

// ktlint-cli 1.8.0 (the latest release) bundles logback 1.3.16, which trips CVE alerts. logback is
// used only for ktlint's own build-time console logging and never ships in the APK. Force the patched
// logback onto ktlint's resolvable configurations. Compatible: logback 1.5.x keeps the slf4j 2.0.x
// binding ktlint already uses and only requires Java 11+, which the JDK 17 build satisfies.
configurations.matching { it.name.startsWith("ktlint") }.configureEach {
    resolutionStrategy {
        force(
            "ch.qos.logback:logback-core:1.5.34",
            "ch.qos.logback:logback-classic:1.5.34",
        )
    }
}

android {
    namespace = "com.danielealbano.androidremotecontrolmcp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.danielealbano.androidremotecontrolmcp"
        // Android 12 (API 31) port: lowered from 33. All Android 13+ (API 33) and
        // Android 14+ (API 34) API calls are guarded at runtime (see
        // PermissionUtils, PermissionCheckerImpl, MainActivity, NotificationProviderImpl).
        minSdk = 31
        targetSdk = 34
        versionCode = versionCodeProp
        versionName = versionNameProp
    }

    // Distribution flavors: `gms` (full app, GitHub/Play) and `foss` (F-Droid, no Google Play Services).
    // Release applicationId is identical for both flavors; debug builds get a per-flavor suffix (set via the
    // variant API in androidComponents below) so gms/foss debug builds can be installed side-by-side.
    flavorDimensions += "distribution"
    productFlavors {
        create("gms") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }

    // Release signing configuration (optional, uses keystore.properties if present)
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        val keystoreProperties = Properties()
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))

        signingConfigs {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            // Debug applicationId is set per-flavor via the variant API (androidComponents below) so it becomes
            // `…mcp.<flavor>.debug`, keeping the release applicationId identical across flavors.
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // QUERY_ALL_PACKAGES is required for app management tools (list/launch/force-stop)
        disable += "QueryAllPackagesPermission"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.*"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Tooling-transitive CVE forces (netty 4.1.x, bouncycastle, httpclient, commons-lang3) live in the
// root build.gradle.kts `allprojects` block so they apply to every module uniformly. The shipping
// netty 4.2.x pins for Ktor's server engine remain here as scoped constraints (see below).

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Material Components (XML themes)
    implementation(libs.material)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.navigation)
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // DataStore
    implementation(libs.datastore.preferences)

    // DocumentFile (SAF)
    implementation(libs.androidx.documentfile)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)

    // Google Play Services (gms flavor only — excluded from the foss/F-Droid build)
    "gmsImplementation"(libs.play.services.location)

    // OpenStreetMap
    implementation(libs.osmdroid)

    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.network.tls.certificates)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Ktor Client (Event Channel dispatcher — no Logging plugin, it would expose auth token)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)

    // Force patched Netty: Ktor's server engine ships netty 4.2.9, which is vulnerable.
    // Covers the HTTP Request Smuggling / HTTP/2 CONTINUATION-flood CVEs (CVE-2026-33870,
    // CVE-2026-33871) plus the native-transport advisories that the engine also pulls onto the
    // release classpath: epoll DoS (GHSA-rwm7-x88c-3g2p) and the epoll/kqueue fd leak
    // (GHSA-w573-9ffj-6ff9). All netty modules are pinned to the same version to avoid skew.
    constraints {
        implementation("io.netty:netty-codec-http:4.2.16.Final")
        implementation("io.netty:netty-codec-http2:4.2.16.Final")
        implementation("io.netty:netty-handler:4.2.16.Final")
        implementation("io.netty:netty-common:4.2.16.Final")
        implementation("io.netty:netty-buffer:4.2.16.Final")
        implementation("io.netty:netty-transport:4.2.16.Final")
        implementation("io.netty:netty-codec-base:4.2.16.Final")
        implementation("io.netty:netty-codec-compression:4.2.16.Final")
        implementation("io.netty:netty-resolver:4.2.16.Final")
        implementation("io.netty:netty-transport-native-unix-common:4.2.16.Final")
        implementation("io.netty:netty-transport-classes-epoll:4.2.16.Final")
        implementation("io.netty:netty-transport-native-epoll:4.2.16.Final")
        implementation("io.netty:netty-transport-classes-kqueue:4.2.16.Final")
        implementation("io.netty:netty-transport-native-kqueue:4.2.16.Final")
    }

    // Certificate generation (Bouncy Castle for self-signed cert with SAN support)
    implementation(libs.bouncy.castle.pkix)
    implementation(libs.bouncy.castle.prov)

    // ngrok tunnel (in-process, JNI-based) — built from source via vendor/ngrok-java submodule
    // ngrok-java: API module (interfaces, builders, Session)
    implementation(files("../vendor/ngrok-java/ngrok-java/target/ngrok-java-1.2.0-SNAPSHOT.jar"))
    // ngrok-java-native: implementation classes (NativeSession, Runtime, etc.)
    implementation(files("../vendor/ngrok-java/ngrok-java-native/target/ngrok-java-native-classes.jar"))

    // MCP SDK
    implementation(libs.mcp.kotlin.sdk.server)
    runtimeOnly(libs.slf4j.android)

    // OAuth (JWT signing/verification)
    // The Jackson BOM aligns java-jwt's transitive jackson-core/jackson-databind to a patched
    // release, closing the CVE alerts on the 2.21.3 versions the library would otherwise pull in.
    implementation(platform(libs.jackson.bom))
    implementation(libs.java.jwt)

    // OAuth client logos (SSRF-guarded remote image loading)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)

    // Kotlinx
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // WorkManager + Hilt integration (periodic in-app update check)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Accompanist
    implementation(libs.accompanist.permissions)

    // Privacy Mode (on-device PII detection). The detection core lives in :privacy (pure JVM,
    // compileOnly onnxruntime); the Android AAR below supplies ai.onnxruntime.* at app runtime.
    implementation(project(":privacy"))
    implementation(libs.onnxruntime.android)

    // Unit Testing
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bouncy.castle.pkix)
    testImplementation(libs.bouncy.castle.prov)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mcp.kotlin.sdk.client)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.sse)
}

dependencies {
    // ngrok-java host native library packaged as JAR for classpath-based loading.
    // Runtime.load() uses Class.getResourceAsStream() to extract the .so/.dylib,
    // so the native library must be inside a JAR on the classpath (not a loose directory).
    testRuntimeOnly(files("../vendor/ngrok-java/ngrok-java-native/target/ngrok-java-native-host.jar"))
}

// Offline IP-geolocation database, generated at build time from the CURRENT month's DB-IP City Lite
// (CC BY 4.0). The gzipped LDB1 asset is not committed (a generated artifact); it is produced into a
// generated-assets directory and registered via androidComponents so AGP wires every consumer (asset
// merge, lint-vital, etc.) to depend on it. Keyed on the year-month, so it naturally refreshes when
// DB-IP publishes a new monthly DB (up-to-date within the same month). Requires python3 + network at
// build time; the source CSV is cached under .dbip-cache (gitignored) so CI can cache the monthly download.
val generateLocationDb =
    tasks.register<GenerateLocationDbTask>("generateLocationDb") {
        script.set(rootProject.layout.projectDirectory.file("scripts/location-db/build_location_db.py"))
        month.set(YearMonth.now().toString())
        cacheDir.set(rootProject.layout.projectDirectory.dir(".dbip-cache"))
        outputDir.set(layout.buildDirectory.dir("generated/locationDb"))
    }

androidComponents {
    // Per-flavor debug applicationId (`…mcp.gms.debug` / `…mcp.foss.debug`) so both debug builds coexist, while
    // the release applicationId stays identical across flavors (`com.danielealbano.androidremotecontrolmcp`).
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.applicationId.set(
            "com.danielealbano.androidremotecontrolmcp.${variant.flavorName}.debug",
        )
    }
    onVariants { variant ->
        // Registered as a generated assets source — the generation runs only for variants that package
        // assets (assemble/lint-vital), never for the unit-test path, which uses the committed fixture.
        variant.sources.assets?.addGeneratedSourceDirectory(generateLocationDb, GenerateLocationDbTask::outputDir)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "3g"
    // Distribute tests across all available CPU cores for faster execution.
    maxParallelForks = (Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
    // MockK uses byte-buddy/reflection internally; JDK 17 strong encapsulation
    // blocks access to these packages from unnamed modules, causing test failures.
    jvmArgs(
        "--add-opens",
        "java.base/java.lang=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.util=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.time=ALL-UNNAMED",
    )
}

jacoco {
    toolVersion = "0.8.14"
}

val jacocoExcludes =
    listOf(
        // Android generated
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        // Hilt / Dagger generated
        "**/*_HiltModules*",
        "**/*_Factory*",
        "**/*_MembersInjector*",
        "**/Hilt_*",
        "**/dagger/**",
        "**/*Module_*",
        "**/*_Impl*",
        // Compose generated
        "**/*ComposableSingletons*",
        // Android framework classes (require device/emulator, not unit-testable)
        "**/McpApplication*",
        "**/services/mcp/McpServerService*",
        "**/services/mcp/BootCompletedReceiver*",
        "**/services/screencapture/ScreenCaptureService*",
        "**/services/accessibility/McpAccessibilityService*",
        "**/services/accessibility/McpInputMethodService*",
        // Update check: WorkManager worker, scheduler, and notifier require Android framework
        "**/services/update/UpdateCheckWorker*",
        "**/services/update/UpdateCheckScheduler*",
        "**/services/update/UpdateNotifierImpl*",
        "**/services/update/BuildConfigAppVersionProvider*",
        // UI layer (requires instrumented/Compose tests)
        "**/ui/**",
        // Dependency injection configuration
        "**/di/**",
    )

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testGmsDebugUnitTest")

    reports {
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/jacocoTestReport/html"))
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacocoTestReport/jacocoTestReport.xml"))
        csv.required.set(false)
    }

    val debugTree =
        fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/gmsDebug") {
            exclude(jacocoExcludes)
        }

    classDirectories.setFrom(debugTree)
    sourceDirectories.setFrom(files("src/main/kotlin", "src/gms/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include("jacoco/testGmsDebugUnitTest.exec")
        },
    )
}

tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn("jacocoTestReport")

    val debugTree =
        fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/gmsDebug") {
            exclude(jacocoExcludes)
        }

    classDirectories.setFrom(debugTree)
    sourceDirectories.setFrom(files("src/main/kotlin", "src/gms/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include("jacoco/testGmsDebugUnitTest.exec")
        },
    )

    violationRules {
        rule {
            limit {
                minimum = "0.50".toBigDecimal()
            }
        }
    }
}

/**
 * Generates the compact LDB1 geolocation DB into a generated-assets directory by invoking the Python
 * builder. A proper typed task (vs a bare Exec writing into the source tree) so AGP can wire it as a
 * generated assets source with correct task dependencies. Keyed on [month] so it refreshes monthly.
 */
@CacheableTask
abstract class GenerateLocationDbTask : DefaultTask() {
    // NONE: only the script's content matters for the output, not its path on disk — so the
    // cache entry stays valid across differently-rooted checkouts (e.g. CI vs local).
    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFile
    abstract val script: RegularFileProperty

    @get:Input
    abstract val month: Property<String>

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun generate() {
        val asset = outputDir.get().asFile.resolve("geo/location-db.bin.gz")
        asset.parentFile.mkdirs()
        execOperations.exec {
            commandLine(
                "python3",
                script.get().asFile.absolutePath,
                "--month",
                month.get(),
                "--cache-dir",
                cacheDir.get().asFile.absolutePath,
                "--out",
                asset.absolutePath,
            )
        }
    }
}
