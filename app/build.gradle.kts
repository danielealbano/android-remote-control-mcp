import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    jacoco
}

val versionNameProp = project.findProperty("VERSION_NAME") as String? ?: "1.0.0"
val versionCodeProp = (project.findProperty("VERSION_CODE") as String?)?.toInt() ?: 1

android {
    namespace = "com.danielealbano.androidremotecontrolmcp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.danielealbano.androidremotecontrolmcp"
        minSdk = 33
        targetSdk = 34
        versionCode = versionCodeProp
        versionName = versionNameProp
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
            applicationIdSuffix = ".debug"
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
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // DataStore
    implementation(libs.datastore.preferences)

    // DocumentFile (SAF)
    implementation(libs.androidx.documentfile)

    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.network.tls.certificates)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Certificate generation (Bouncy Castle for self-signed cert with SAN support)
    implementation(libs.bouncy.castle.pkix)
    implementation(libs.bouncy.castle.prov)

    // ngrok tunnel (in-process, JNI-based) — built from source via vendor/ngrok-java submodule
    // ngrok-java: API module (interfaces, builders, Session)
    implementation(files("../vendor/ngrok-java/ngrok-java/target/ngrok-java-1.1.1.jar"))
    // ngrok-java-native: implementation classes (NativeSession, Runtime, etc.)
    implementation(files("../vendor/ngrok-java/ngrok-java-native/target/ngrok-java-native-classes.jar"))

    // MCP SDK
    implementation(libs.mcp.kotlin.sdk.server)
    runtimeOnly(libs.slf4j.android)

    // Kotlinx
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Accompanist
    implementation(libs.accompanist.permissions)

    // Unit Testing
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
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

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "4g"
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
        // UI layer (requires instrumented/Compose tests)
        "**/ui/**",
        // Dependency injection configuration
        "**/di/**",
    )

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")

    reports {
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/jacocoTestReport/html"))
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacocoTestReport/jacocoTestReport.xml"))
        csv.required.set(false)
    }

    val debugTree =
        fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
            exclude(jacocoExcludes)
        }

    classDirectories.setFrom(debugTree)
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include("jacoco/testDebugUnitTest.exec")
        },
    )
}

tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn("jacocoTestReport")

    val debugTree =
        fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
            exclude(jacocoExcludes)
        }

    classDirectories.setFrom(debugTree)
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include("jacoco/testDebugUnitTest.exec")
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
