# Plan 1: Project Scaffolding & Build System

**Branch**: `feat/project-scaffolding`
**PR Title**: `Plan 1: Project scaffolding and build system`
**Created**: 2026-02-11 16:32:14

---

## Overview

This plan creates the entire Android project structure from scratch. After implementation, the project compiles into a valid (empty) debug and release APK with all development workflow targets available via the Makefile.

This is a greenfield plan -- every file listed below is new. There is no existing code to modify.

---

## User Story 1: Project Scaffolding & Build System

**As a** developer
**I want** a fully configured Android project with Gradle build system, dependency management, CI/CD pipeline, and development workflow automation
**So that** I can immediately begin implementing application features on a solid, reproducible foundation.

### Acceptance Criteria / Definition of Done (High Level)

- [ ] Gradle wrapper is generated and committed (version 8.14.4)
- [ ] `settings.gradle.kts` configures plugin management and dependency resolution
- [ ] `gradle.properties` defines JVM args, AndroidX, Kotlin style, VERSION_NAME, VERSION_CODE
- [ ] `gradle/libs.versions.toml` declares ALL project dependencies with version catalog
- [ ] Root `build.gradle.kts` applies all plugins with `apply false`
- [ ] `app/build.gradle.kts` fully configures the app module (plugins, android block, dependencies, JUnit 5)
- [ ] `app/proguard-rules.pro` exists (empty, with comment)
- [ ] `AndroidManifest.xml` declares all permissions, services, receiver, and activity
- [ ] String resources, theme, accessibility config, and notification icon are present
- [ ] Launcher icons (mipmap) are present (default placeholder)
- [ ] `McpApplication.kt` is a valid `@HiltAndroidApp` Application class
- [ ] `AppModule.kt` is a valid Hilt module
- [ ] `Makefile` contains ALL targets defined in PROJECT.md
- [ ] `.gitignore` covers all standard Android exclusions
- [ ] `.github/workflows/ci.yml` defines a working CI pipeline (lint, test-unit, build)
- [ ] `README.md` skeleton exists with all required sections
- [ ] `make build` succeeds without errors or warnings
- [ ] `make lint` succeeds without errors or warnings
- [ ] `gh act --validate` succeeds (CI workflow syntax is valid)
- [ ] `make test-unit` succeeds (no tests yet, but the task completes without failure)

### Commit Strategy

| # | Message | Files |
|---|---------|-------|
| 1 | `chore: add Gradle project configuration with all dependencies` | `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `build.gradle.kts` (root), `app/build.gradle.kts`, `app/proguard-rules.pro`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat` |
| 2 | `feat: add Android manifest and application resources` | `AndroidManifest.xml`, `strings.xml`, `themes.xml`, `accessibility_service_config.xml`, `ic_notification.xml`, `mipmap-*` launcher icons |
| 3 | `feat: add Application entry point and Hilt DI setup` | `McpApplication.kt`, `AppModule.kt` |
| 4 | `chore: add Makefile with development workflow targets` | `Makefile` |
| 5 | `chore: add CI/CD pipeline and .gitignore` | `.github/workflows/ci.yml`, `.gitignore` |
| 6 | `docs: add README.md skeleton` | `README.md` |

---

### Task 1.1: Generate Gradle Wrapper

**Description**: Generate the Gradle wrapper so that all developers use the same Gradle version without requiring a global install.

**Acceptance Criteria**:
- [ ] `gradlew` and `gradlew.bat` scripts exist and are executable
- [ ] `gradle/wrapper/gradle-wrapper.jar` exists
- [ ] `gradle/wrapper/gradle-wrapper.properties` references Gradle 8.14.4

**Tests**: No automated tests for this task. Verification is that subsequent Gradle commands work.

#### Action 1.1.1: Generate Gradle wrapper files

**What**: Run the `gradle wrapper` command to generate wrapper files. If `gradle` is not available globally, download the wrapper manually or use an existing Gradle installation.

**Context**: The wrapper ensures reproducible builds. All subsequent `./gradlew` commands depend on this.

**Command to run**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp
# verify latest Gradle 8.x version at implementation time
gradle wrapper --gradle-version 8.14.4
```

**Expected files created**:
```
gradlew                                  (executable)
gradlew.bat                              (Windows batch)
gradle/wrapper/gradle-wrapper.jar        (binary)
gradle/wrapper/gradle-wrapper.properties (config)
```

**Verification**: Run `./gradlew --version` and confirm the output shows Gradle 8.14.4.

> **Note**: If `gradle` is not installed globally, manually create `gradle/wrapper/gradle-wrapper.properties` with:
> ```properties
> distributionBase=GRADLE_USER_HOME
> distributionPath=wrapper/dists
> distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.4-bin.zip
> networkTimeout=10000
> validateDistributionUrl=true
> zipStoreBase=GRADLE_USER_HOME
> zipStorePath=wrapper/dists
> ```
> Then download the wrapper JAR from the Gradle distribution and place `gradlew`/`gradlew.bat` scripts from any standard Android project.

---

### Task 1.2: Create Gradle Configuration Files

**Description**: Set up all Gradle configuration files: settings, properties, version catalog, root build script, and app build script.

**Acceptance Criteria**:
- [ ] `settings.gradle.kts` configures pluginManagement and dependencyResolutionManagement
- [ ] `gradle.properties` has correct JVM args and project version
- [ ] `gradle/libs.versions.toml` has all required dependencies with version references
- [ ] Root `build.gradle.kts` applies all plugins with `apply false`
- [ ] `app/build.gradle.kts` is fully configured with all plugins, android block, and dependencies
- [ ] `app/proguard-rules.pro` exists
- [ ] `./gradlew tasks` runs successfully after all files are in place (after Task 1.3 provides AndroidManifest.xml and resources)

**Tests**: No automated tests for this task. Build verification happens at the end of the user story.

#### Action 1.2.1: Create `settings.gradle.kts`

**What**: Create the Gradle settings file with plugin management and dependency resolution.

**Context**: This is the entry point for the Gradle build. It tells Gradle where to find plugins and dependencies, and which modules to include.

**File**: `settings.gradle.kts` (project root)

```diff
--- /dev/null
+++ b/settings.gradle.kts
@@ -0,0 +1,20 @@
+pluginManagement {
+    repositories {
+        google {
+            content {
+                includeGroupByRegex("com\\.android.*")
+                includeGroupByRegex("com\\.google.*")
+                includeGroupByRegex("androidx.*")
+            }
+        }
+        mavenCentral()
+        gradlePluginPortal()
+    }
+}
+
+dependencyResolutionManagement {
+    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
+    repositories {
+        google()
+        mavenCentral()
+    }
+}
+
+rootProject.name = "AndroidRemoteControlMcp"
+include(":app")
```

---

#### Action 1.2.2: Create `gradle.properties`

**What**: Create the Gradle properties file with JVM args, AndroidX, Kotlin style, and version properties.

**Context**: These properties are read by Gradle at build time. VERSION_NAME and VERSION_CODE are used in `app/build.gradle.kts` and by the Makefile versioning targets.

**File**: `gradle.properties` (project root)

```diff
--- /dev/null
+++ b/gradle.properties
@@ -0,0 +1,14 @@
+# Project-wide Gradle settings.
+
+# JVM memory allocation
+org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
+
+# AndroidX
+android.useAndroidX=true
+
+# Kotlin code style
+kotlin.code.style=official
+
+# Application versioning (semantic versioning)
+VERSION_NAME=1.0.0
+VERSION_CODE=1
```

---

#### Action 1.2.3: Create `gradle/libs.versions.toml`

**What**: Create the Gradle version catalog with ALL project dependencies.

**Context**: The version catalog centralizes dependency versions. Every library and plugin version is declared here and referenced from `build.gradle.kts` files using `libs.<alias>` syntax. All version numbers MUST be verified as the latest stable at implementation time.

**File**: `gradle/libs.versions.toml`

```diff
--- /dev/null
+++ b/gradle/libs.versions.toml
@@ -0,0 +1,107 @@
+[versions]
+# Build tools
+agp = "8.13"                            # Android Gradle Plugin (latest stable 8.x)
+kotlin = "2.3.10"                       # Kotlin (latest stable, Feb 2026)
+ksp = "2.3.5"                           # KSP (decoupled from Kotlin since 2.3.0; latest stable)
+
+# Compose
+compose-bom = "2024.12.01"             # verify latest at implementation time
+
+# AndroidX
+activity-compose = "1.9.3"             # verify latest at implementation time
+core-ktx = "1.15.0"                    # verify latest at implementation time
+lifecycle = "2.8.7"                     # verify latest at implementation time
+datastore = "1.1.1"                     # verify latest at implementation time
+
+# Networking
+ktor = "3.0.3"                          # verify latest at implementation time
+
+# MCP SDK
+mcp-kotlin-sdk = "0.4.0"               # verify latest at implementation time
+
+# Serialization
+kotlinx-serialization = "1.7.3"        # verify latest at implementation time
+
+# Coroutines
+kotlinx-coroutines = "1.9.0"           # verify latest at implementation time
+
+# Dependency Injection
+hilt = "2.53.1"                         # verify latest at implementation time
+
+# Accompanist
+accompanist = "0.36.0"                  # verify latest at implementation time
+
+# Testing
+junit5 = "5.11.4"                       # verify latest at implementation time
+mockk = "1.13.14"                       # verify latest at implementation time
+turbine = "1.2.0"                       # verify latest at implementation time
+testcontainers = "1.20.4"              # verify latest at implementation time
+
+# AndroidX Test
+test-core = "1.6.1"                     # verify latest at implementation time
+test-runner = "1.6.2"                   # verify latest at implementation time
+test-rules = "1.6.1"                    # verify latest at implementation time
+compose-ui-test = "1.7.6"              # verify latest at implementation time
+
+# Linting
+ktlint-gradle = "12.1.2"               # verify latest at implementation time
+detekt = "1.23.7"                       # verify latest at implementation time
+
+[libraries]
+# AndroidX Core
+androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "core-ktx" }
+androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activity-compose" }
+
+# Compose BOM
+compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
+compose-ui = { group = "androidx.compose.ui", name = "ui" }
+compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
+compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
+compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
+compose-material3 = { group = "androidx.compose.material3", name = "material3" }
+compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
+
+# Lifecycle
+lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
+lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
+lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
+
+# DataStore
+datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
+
+# Ktor Server
+ktor-server-core = { group = "io.ktor", name = "ktor-server-core", version.ref = "ktor" }
+ktor-server-netty = { group = "io.ktor", name = "ktor-server-netty", version.ref = "ktor" }
+ktor-server-content-negotiation = { group = "io.ktor", name = "ktor-server-content-negotiation", version.ref = "ktor" }
+ktor-server-auth = { group = "io.ktor", name = "ktor-server-auth", version.ref = "ktor" }
+ktor-server-tls = { group = "io.ktor", name = "ktor-server-tls", version.ref = "ktor" }
+ktor-serialization-kotlinx-json = { group = "io.ktor", name = "ktor-serialization-kotlinx-json", version.ref = "ktor" }
+
+# MCP SDK
+mcp-kotlin-sdk = { group = "io.modelcontextprotocol", name = "kotlin-sdk", version.ref = "mcp-kotlin-sdk" }
+
+# Kotlinx
+kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
+kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }
+kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "kotlinx-coroutines" }
+
+# Hilt
+hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
+hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
+
+# Accompanist
+accompanist-permissions = { group = "com.google.accompanist", name = "accompanist-permissions", version.ref = "accompanist" }
+
+# Testing
+junit-jupiter-api = { group = "org.junit.jupiter", name = "junit-jupiter-api", version.ref = "junit5" }
+junit-jupiter-engine = { group = "org.junit.jupiter", name = "junit-jupiter-engine", version.ref = "junit5" }
+junit-jupiter-params = { group = "org.junit.jupiter", name = "junit-jupiter-params", version.ref = "junit5" }
+mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
+mockk-android = { group = "io.mockk", name = "mockk-android", version.ref = "mockk" }
+turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
+kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinx-coroutines" }
+testcontainers = { group = "org.testcontainers", name = "testcontainers", version.ref = "testcontainers" }
+testcontainers-junit-jupiter = { group = "org.testcontainers", name = "junit-jupiter", version.ref = "testcontainers" }
+
+# AndroidX Test
+test-core = { group = "androidx.test", name = "core", version.ref = "test-core" }
+test-runner = { group = "androidx.test", name = "runner", version.ref = "test-runner" }
+test-rules = { group = "androidx.test", name = "rules", version.ref = "test-rules" }
+compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4", version.ref = "compose-ui-test" }
+compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest", version.ref = "compose-ui-test" }
+
+[plugins]
+android-application = { id = "com.android.application", version.ref = "agp" }
+kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
+kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
+hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
+kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
+ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
+ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlint-gradle" }
+detekt = { id = "io.gitlab.arturbosch.detekt", version.ref = "detekt" }
```

> **Implementation Note**: The Compose compiler plugin is now bundled with Kotlin 2.0+ via `kotlin-compose` plugin. There is no separate `compose-compiler` version. The `composeOptions { kotlinCompilerExtensionVersion = ... }` block is no longer needed when using the `kotlin-compose` plugin.

> **Implementation Note on MCP SDK**: The `mcp-kotlin-sdk` artifact coordinates (`io.modelcontextprotocol:kotlin-sdk`) MUST be verified at implementation time. Check the official repository at https://github.com/modelcontextprotocol/kotlin-sdk for the correct Maven coordinates and latest version.

---

#### Action 1.2.4: Create root `build.gradle.kts`

**What**: Create the root build script that applies all plugins with `apply false`.

**Context**: The root build script does not apply plugins directly. It declares them so they are available for submodules (`:app`) to apply.

**File**: `build.gradle.kts` (project root)

```diff
--- /dev/null
+++ b/build.gradle.kts
@@ -0,0 +1,10 @@
+plugins {
+    alias(libs.plugins.android.application) apply false
+    alias(libs.plugins.kotlin.android) apply false
+    alias(libs.plugins.kotlin.compose) apply false
+    alias(libs.plugins.hilt) apply false
+    alias(libs.plugins.kotlin.serialization) apply false
+    alias(libs.plugins.ksp) apply false
+    alias(libs.plugins.ktlint) apply false
+    alias(libs.plugins.detekt) apply false
+}
```

---

#### Action 1.2.5: Create `app/build.gradle.kts`

**What**: Create the full app module build script with plugins, android block, dependencies, and JUnit 5 configuration.

**Context**: This is the main build configuration for the Android application module. It configures compilation targets, build types, Compose, signing, and all library dependencies. The `debug` build type appends `.debug` to the application ID to allow side-by-side installation. Release signing uses `keystore.properties` if available.

**File**: `app/build.gradle.kts`

```diff
--- /dev/null
+++ b/app/build.gradle.kts
@@ -0,0 +1,120 @@
+import java.io.FileInputStream
+import java.util.Properties
+
+plugins {
+    alias(libs.plugins.android.application)
+    alias(libs.plugins.kotlin.android)
+    alias(libs.plugins.kotlin.compose)
+    alias(libs.plugins.hilt)
+    alias(libs.plugins.kotlin.serialization)
+    alias(libs.plugins.ksp)
+    alias(libs.plugins.ktlint)
+    alias(libs.plugins.detekt)
+}
+
+val versionNameProp = project.findProperty("VERSION_NAME") as String? ?: "1.0.0"
+val versionCodeProp = (project.findProperty("VERSION_CODE") as String?)?.toInt() ?: 1
+
+android {
+    namespace = "com.danielealbano.androidremotecontrolmcp"
+    compileSdk = 34
+
+    defaultConfig {
+        applicationId = "com.danielealbano.androidremotecontrolmcp"
+        minSdk = 26
+        targetSdk = 34
+        versionCode = versionCodeProp
+        versionName = versionNameProp
+
+        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
+    }
+
+    // Release signing configuration (optional, uses keystore.properties if present)
+    val keystorePropertiesFile = rootProject.file("keystore.properties")
+    if (keystorePropertiesFile.exists()) {
+        val keystoreProperties = Properties()
+        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
+
+        signingConfigs {
+            create("release") {
+                storeFile = file(keystoreProperties["storeFile"] as String)
+                storePassword = keystoreProperties["storePassword"] as String
+                keyAlias = keystoreProperties["keyAlias"] as String
+                keyPassword = keystoreProperties["keyPassword"] as String
+            }
+        }
+    }
+
+    buildTypes {
+        debug {
+            applicationIdSuffix = ".debug"
+            isDebuggable = true
+            isMinifyEnabled = false
+        }
+        release {
+            isDebuggable = false
+            isMinifyEnabled = false
+            proguardFiles(
+                getDefaultProguardFile("proguard-android-optimize.txt"),
+                "proguard-rules.pro",
+            )
+            if (keystorePropertiesFile.exists()) {
+                signingConfig = signingConfigs.getByName("release")
+            }
+        }
+    }
+
+    compileOptions {
+        sourceCompatibility = JavaVersion.VERSION_17
+        targetCompatibility = JavaVersion.VERSION_17
+    }
+
+    kotlinOptions {
+        jvmTarget = "17"
+    }
+
+    buildFeatures {
+        compose = true
+    }
+
+    packaging {
+        resources {
+            excludes += "/META-INF/{AL2.0,LGPL2.1}"
+            excludes += "/META-INF/INDEX.LIST"
+            excludes += "/META-INF/io.netty.*"
+        }
+    }
+}
+
+dependencies {
+    // AndroidX Core
+    implementation(libs.androidx.core.ktx)
+    implementation(libs.androidx.activity.compose)
+
+    // Compose
+    implementation(platform(libs.compose.bom))
+    implementation(libs.compose.ui)
+    implementation(libs.compose.ui.graphics)
+    implementation(libs.compose.ui.tooling.preview)
+    implementation(libs.compose.material3)
+    implementation(libs.compose.material.icons.extended)
+    debugImplementation(libs.compose.ui.tooling)
+
+    // Lifecycle
+    implementation(libs.lifecycle.runtime.ktx)
+    implementation(libs.lifecycle.viewmodel.compose)
+    implementation(libs.lifecycle.runtime.compose)
+
+    // DataStore
+    implementation(libs.datastore.preferences)
+
+    // Ktor Server
+    implementation(libs.ktor.server.core)
+    implementation(libs.ktor.server.netty)
+    implementation(libs.ktor.server.content.negotiation)
+    implementation(libs.ktor.server.auth)
+    implementation(libs.ktor.server.tls)
+    implementation(libs.ktor.serialization.kotlinx.json)
+
+    // MCP SDK
+    implementation(libs.mcp.kotlin.sdk)
+
+    // Kotlinx
+    implementation(libs.kotlinx.serialization.json)
+    implementation(libs.kotlinx.coroutines.core)
+    implementation(libs.kotlinx.coroutines.android)
+
+    // Hilt
+    implementation(libs.hilt.android)
+    ksp(libs.hilt.compiler)
+
+    // Accompanist
+    implementation(libs.accompanist.permissions)
+
+    // Unit Testing
+    testImplementation(libs.junit.jupiter.api)
+    testImplementation(libs.junit.jupiter.params)
+    testRuntimeOnly(libs.junit.jupiter.engine)
+    testImplementation(libs.mockk)
+    testImplementation(libs.turbine)
+    testImplementation(libs.kotlinx.coroutines.test)
+
+    // Android Instrumented Testing
+    androidTestImplementation(libs.test.core)
+    androidTestImplementation(libs.test.runner)
+    androidTestImplementation(libs.test.rules)
+    androidTestImplementation(libs.compose.ui.test.junit4)
+    androidTestImplementation(libs.mockk.android)
+    debugImplementation(libs.compose.ui.test.manifest)
+}
+
+tasks.withType<Test> {
+    useJUnitPlatform()
+}
```

> **Implementation Note on `versionNameProp` / `versionCodeProp`**: The `gradle.properties` file defines `VERSION_NAME` and `VERSION_CODE`. The diff above uses `project.findProperty()` with safe defaults, which is the correct approach for reading Gradle project properties with underscore-named keys. The `by project` delegation pattern does not work with these property names.

---

#### Action 1.2.6: Create `app/proguard-rules.pro`

**What**: Create an empty ProGuard rules file.

**Context**: ProGuard/R8 is not used (open source project, minify disabled), but the file is referenced in `build.gradle.kts` and must exist.

**File**: `app/proguard-rules.pro`

```diff
--- /dev/null
+++ b/app/proguard-rules.pro
@@ -0,0 +1,3 @@
+# ProGuard rules for Android Remote Control MCP
+# No ProGuard/R8 minification is used (open source project with MIT license).
+# This file is intentionally empty.
```

---

### Task 1.3: Create Android Manifest and Application Resources

**Description**: Create the Android manifest with all permissions, components, and the application resources (strings, theme, accessibility config, notification icon, launcher icons).

**Acceptance Criteria**:
- [ ] `AndroidManifest.xml` declares INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION, FOREGROUND_SERVICE_SPECIAL_USE, RECEIVE_BOOT_COMPLETED, POST_NOTIFICATIONS permissions
- [ ] `AndroidManifest.xml` declares MainActivity, McpServerService, ScreenCaptureService, McpAccessibilityService, BootCompletedReceiver
- [ ] `strings.xml` has app name, accessibility service description, notification strings
- [ ] `themes.xml` has basic Material 3 DayNight theme
- [ ] `accessibility_service_config.xml` has correct event types, feedback type, flags
- [ ] `ic_notification.xml` is a valid vector drawable
- [ ] Launcher icons exist in all required mipmap densities

**Tests**: No automated tests for this task. Validation is that the manifest parses correctly during build.

#### Action 1.3.1: Create `app/src/main/AndroidManifest.xml`

**What**: Create the full Android manifest with all declared components.

**Context**: The manifest declares permissions, the Application class, the main Activity, three Services (MCP server, screen capture, accessibility), and the boot receiver. Each foreground service declares its `foregroundServiceType`. The accessibility service uses `BIND_ACCESSIBILITY_SERVICE` permission and references its XML config.

**File**: `app/src/main/AndroidManifest.xml`

```diff
--- /dev/null
+++ b/app/src/main/AndroidManifest.xml
@@ -0,0 +1,74 @@
+<?xml version="1.0" encoding="utf-8"?>
+<manifest xmlns:android="http://schemas.android.com/apk/res/android"
+    xmlns:tools="http://schemas.android.com/tools">
+
+    <!-- Network -->
+    <uses-permission android:name="android.permission.INTERNET" />
+
+    <!-- Foreground services -->
+    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
+    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
+    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
+
+    <!-- Boot receiver -->
+    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
+
+    <!-- Notifications (Android 13+) -->
+    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
+
+    <application
+        android:name=".McpApplication"
+        android:allowBackup="true"
+        android:icon="@mipmap/ic_launcher"
+        android:label="@string/app_name"
+        android:roundIcon="@mipmap/ic_launcher_round"
+        android:supportsRtl="true"
+        android:theme="@style/Theme.AndroidRemoteControlMcp"
+        tools:targetApi="34">
+
+        <!-- Main Activity -->
+        <activity
+            android:name=".ui.MainActivity"
+            android:exported="true"
+            android:theme="@style/Theme.AndroidRemoteControlMcp">
+            <intent-filter>
+                <action android:name="android.intent.action.MAIN" />
+                <category android:name="android.intent.category.LAUNCHER" />
+            </intent-filter>
+        </activity>
+
+        <!-- MCP Server Foreground Service -->
+        <service
+            android:name=".services.mcp.McpServerService"
+            android:exported="false"
+            android:foregroundServiceType="specialUse">
+            <property
+                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
+                android:value="MCP server providing remote device control over HTTPS" />
+        </service>
+
+        <!-- Screen Capture Foreground Service -->
+        <service
+            android:name=".services.screencapture.ScreenCaptureService"
+            android:exported="false"
+            android:foregroundServiceType="mediaProjection" />
+
+        <!-- Accessibility Service -->
+        <service
+            android:name=".services.accessibility.McpAccessibilityService"
+            android:exported="false"
+            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
+            <intent-filter>
+                <action android:name="android.accessibilityservice.AccessibilityService" />
+            </intent-filter>
+            <meta-data
+                android:name="android.accessibilityservice"
+                android:resource="@xml/accessibility_service_config" />
+        </service>
+
+        <!-- Boot Completed Receiver -->
+        <receiver
+            android:name=".services.mcp.BootCompletedReceiver"
+            android:exported="false">
+            <intent-filter>
+                <action android:name="android.intent.action.BOOT_COMPLETED" />
+            </intent-filter>
+        </receiver>
+
+    </application>
+
+</manifest>
```

> **Implementation Note**: The `FOREGROUND_SERVICE_SPECIAL_USE` type requires API 34. The `<property>` tag with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` is mandatory for `specialUse` foreground service types on Android 14. This explains why the MCP server uses this type since there is no standard foreground service type for "running an HTTP server."

---

#### Action 1.3.2: Create `app/src/main/res/values/strings.xml`

**What**: Create string resources for the application.

**Context**: All user-visible strings should be in strings.xml for localization support. This includes app name, accessibility service description, and notification channel/content strings.

**File**: `app/src/main/res/values/strings.xml`

```diff
--- /dev/null
+++ b/app/src/main/res/values/strings.xml
@@ -0,0 +1,16 @@
+<resources>
+    <string name="app_name">Android Remote Control MCP</string>
+
+    <!-- Accessibility Service -->
+    <string name="accessibility_service_description">Provides remote control capabilities via MCP protocol. Allows introspecting UI elements and performing actions on the device.</string>
+
+    <!-- Notification Channel -->
+    <string name="notification_channel_mcp_server_id">mcp_server_channel</string>
+    <string name="notification_channel_mcp_server_name">MCP Server</string>
+    <string name="notification_channel_screen_capture_id">screen_capture_channel</string>
+    <string name="notification_channel_screen_capture_name">Screen Capture</string>
+
+    <!-- Notification Content -->
+    <string name="notification_mcp_server_title">MCP Server Running</string>
+    <string name="notification_screen_capture_title">Screen Capture Active</string>
+</resources>
```

---

#### Action 1.3.3: Create `app/src/main/res/values/themes.xml`

**What**: Create a basic Material 3 DayNight theme.

**Context**: A minimal theme is needed for the app to compile. The full theme with custom colors and typography will be implemented in Plan 3 (UI Layer). For now, we use the base Material 3 DayNight theme.

**File**: `app/src/main/res/values/themes.xml`

```diff
--- /dev/null
+++ b/app/src/main/res/values/themes.xml
@@ -0,0 +1,7 @@
+<?xml version="1.0" encoding="utf-8"?>
+<resources>
+
+    <style name="Theme.AndroidRemoteControlMcp" parent="Theme.Material3.DayNight.NoActionBar">
+    </style>
+
+</resources>
```

---

#### Action 1.3.4: Create `app/src/main/res/xml/accessibility_service_config.xml`

**What**: Create the accessibility service configuration XML.

**Context**: This XML is referenced from `AndroidManifest.xml` via the `<meta-data>` tag on the accessibility service. It defines what events the service listens to, its capabilities, and feedback type.

**File**: `app/src/main/res/xml/accessibility_service_config.xml`

```diff
--- /dev/null
+++ b/app/src/main/res/xml/accessibility_service_config.xml
@@ -0,0 +1,11 @@
+<?xml version="1.0" encoding="utf-8"?>
+<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
+    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
+    android:accessibilityFeedbackType="feedbackGeneric"
+    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
+    android:canPerformGestures="true"
+    android:canRetrieveWindowContent="true"
+    android:description="@string/accessibility_service_description"
+    android:notificationTimeout="100"
+    android:settingsActivity="com.danielealbano.androidremotecontrolmcp.ui.MainActivity" />
```

---

#### Action 1.3.5: Create `app/src/main/res/drawable/ic_notification.xml`

**What**: Create a simple vector drawable for notification icons.

**Context**: Foreground services require a notification icon. This provides a basic device/phone icon using Material Design paths. Notification icons must be monochrome (single color, typically white, the system tints them).

**File**: `app/src/main/res/drawable/ic_notification.xml`

```diff
--- /dev/null
+++ b/app/src/main/res/drawable/ic_notification.xml
@@ -0,0 +1,10 @@
+<vector xmlns:android="http://schemas.android.com/apk/res/android"
+    android:width="24dp"
+    android:height="24dp"
+    android:viewportWidth="24"
+    android:viewportHeight="24"
+    android:tint="?attr/colorControlNormal">
+    <path
+        android:fillColor="@android:color/white"
+        android:pathData="M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19H7V5h10v14z" />
+</vector>
```

> **Note**: This is the Material Design "smartphone" icon path. It displays a phone outline suitable for notification tray.

---

#### Action 1.3.6: Create launcher icon placeholders

**What**: Create placeholder launcher icons for all required mipmap densities.

**Context**: Android requires launcher icons in multiple densities. For this scaffolding plan, we generate simple placeholder icons. These can be replaced later with a proper design. The standard approach is to use Android Studio's Image Asset Studio or the `android-icon-generator` tool. Since we are working from the command line, we will create minimal valid PNG files.

**Implementation approach**: Use the `ic_launcher` XML adaptive icon approach with a simple vector background and foreground. This requires fewer files and works on all API levels.

**Files to create**:

1. `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
2. `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
3. `app/src/main/res/drawable/ic_launcher_foreground.xml`
4. `app/src/main/res/values/ic_launcher_background.xml`
5. Legacy fallback PNGs for pre-API-26 (one per density)

**File**: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`

```diff
--- /dev/null
+++ b/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
@@ -0,0 +1,5 @@
+<?xml version="1.0" encoding="utf-8"?>
+<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
+    <background android:drawable="@color/ic_launcher_background" />
+    <foreground android:drawable="@drawable/ic_launcher_foreground" />
+</adaptive-icon>
```

**File**: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`

```diff
--- /dev/null
+++ b/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
@@ -0,0 +1,5 @@
+<?xml version="1.0" encoding="utf-8"?>
+<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
+    <background android:drawable="@color/ic_launcher_background" />
+    <foreground android:drawable="@drawable/ic_launcher_foreground" />
+</adaptive-icon>
```

**File**: `app/src/main/res/drawable/ic_launcher_foreground.xml`

```diff
--- /dev/null
+++ b/app/src/main/res/drawable/ic_launcher_foreground.xml
@@ -0,0 +1,15 @@
+<vector xmlns:android="http://schemas.android.com/apk/res/android"
+    android:width="108dp"
+    android:height="108dp"
+    android:viewportWidth="108"
+    android:viewportHeight="108">
+    <group
+        android:scaleX="0.4"
+        android:scaleY="0.4"
+        android:translateX="32.4"
+        android:translateY="32.4">
+        <path
+            android:fillColor="#FFFFFF"
+            android:pathData="M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19H7V5h10v14z" />
+    </group>
+</vector>
```

**File**: `app/src/main/res/values/ic_launcher_background.xml`

```diff
--- /dev/null
+++ b/app/src/main/res/values/ic_launcher_background.xml
@@ -0,0 +1,4 @@
+<?xml version="1.0" encoding="utf-8"?>
+<resources>
+    <color name="ic_launcher_background">#1B5E20</color>
+</resources>
```

> **Implementation Note for legacy fallback**: Since `minSdk = 26` (API 26 = Android 8.0), adaptive icons are supported on all target devices. The `mipmap-anydpi-v26` directory will be used on all devices. Legacy PNG mipmap directories (`mipmap-hdpi`, `mipmap-mdpi`, `mipmap-xhdpi`, `mipmap-xxhdpi`, `mipmap-xxxhdpi`) are NOT required because `minSdk` is 26. However, if the build system or lint warns about missing legacy icons, generate simple 1x1 PNG placeholders in each density bucket at implementation time. Verify at build time whether these are needed.

---

### Task 1.4: Create Application Entry Point and Hilt DI

**Description**: Create the `McpApplication` class annotated with `@HiltAndroidApp` and the base Hilt `AppModule`.

**Acceptance Criteria**:
- [ ] `McpApplication.kt` compiles and extends `Application`
- [ ] `McpApplication.kt` has `@HiltAndroidApp` annotation
- [ ] `AppModule.kt` compiles as a valid Hilt module
- [ ] `AppModule.kt` provides `@ApplicationContext` binding

**Tests**: No automated tests for this task (Hilt setup is verified by the build compiling successfully). Unit tests for Hilt modules will be added in Plan 2.

#### Action 1.4.1: Create `McpApplication.kt`

**What**: Create the Application entry point with Hilt annotation.

**Context**: `@HiltAndroidApp` triggers Hilt's code generation. The `TAG` constant follows the project convention for log tags. This class is referenced in `AndroidManifest.xml` via `android:name=".McpApplication"`.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/McpApplication.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/McpApplication.kt
@@ -0,0 +1,12 @@
+package com.danielealbano.androidremotecontrolmcp
+
+import android.app.Application
+import dagger.hilt.android.HiltAndroidApp
+
+@HiltAndroidApp
+class McpApplication : Application() {
+
+    companion object {
+        private const val TAG = "MCP:Application"
+    }
+}
```

---

#### Action 1.4.2: Create `AppModule.kt`

**What**: Create the Hilt dependency injection module.

**Context**: This is the central DI module. For now it is minimal (just provides `@ApplicationContext`). Future plans will add bindings for `SettingsRepository`, services, etc. The `@Provides` function for `Context` is not actually needed because Hilt automatically provides `@ApplicationContext Context` -- but we include the module as an empty shell ready for future bindings.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt
@@ -0,0 +1,10 @@
+package com.danielealbano.androidremotecontrolmcp.di
+
+import dagger.Module
+import dagger.hilt.InstallIn
+import dagger.hilt.components.SingletonComponent
+
+@Module
+@InstallIn(SingletonComponent::class)
+object AppModule {
+}
```

> **Note**: Hilt already provides `@ApplicationContext` automatically. Additional `@Provides` or `@Binds` methods will be added in Plan 2 when `SettingsRepository` and other components are introduced.

---

#### Action 1.4.3: Create stub classes referenced in AndroidManifest.xml

**What**: Create minimal stub classes for all components declared in the manifest so the project compiles.

**Context**: The manifest references `McpServerService`, `ScreenCaptureService`, `McpAccessibilityService`, `BootCompletedReceiver`, and `MainActivity`. Without these classes, the build will fail with "class not found" errors. These are minimal stubs that will be fully implemented in later plans (Plan 3 for MainActivity, Plan 4 for AccessibilityService, Plan 5 for ScreenCaptureService, Plan 6 for McpServerService and BootCompletedReceiver).

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/MainActivity.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/MainActivity.kt
@@ -0,0 +1,22 @@
+package com.danielealbano.androidremotecontrolmcp.ui
+
+import android.os.Bundle
+import androidx.activity.ComponentActivity
+import androidx.activity.compose.setContent
+import androidx.compose.material3.Text
+import dagger.hilt.android.AndroidEntryPoint
+
+@AndroidEntryPoint
+class MainActivity : ComponentActivity() {
+
+    override fun onCreate(savedInstanceState: Bundle?) {
+        super.onCreate(savedInstanceState)
+        setContent {
+            Text("Android Remote Control MCP")
+        }
+    }
+
+    companion object {
+        private const val TAG = "MCP:MainActivity"
+    }
+}
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt
@@ -0,0 +1,14 @@
+package com.danielealbano.androidremotecontrolmcp.services.mcp
+
+import android.app.Service
+import android.content.Intent
+import android.os.IBinder
+
+class McpServerService : Service() {
+
+    override fun onBind(intent: Intent?): IBinder? = null
+
+    companion object {
+        private const val TAG = "MCP:ServerService"
+    }
+}
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenCaptureService.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenCaptureService.kt
@@ -0,0 +1,14 @@
+package com.danielealbano.androidremotecontrolmcp.services.screencapture
+
+import android.app.Service
+import android.content.Intent
+import android.os.IBinder
+
+class ScreenCaptureService : Service() {
+
+    override fun onBind(intent: Intent?): IBinder? = null
+
+    companion object {
+        private const val TAG = "MCP:ScreenCaptureService"
+    }
+}
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/McpAccessibilityService.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/McpAccessibilityService.kt
@@ -0,0 +1,18 @@
+package com.danielealbano.androidremotecontrolmcp.services.accessibility
+
+import android.accessibilityservice.AccessibilityService
+import android.view.accessibility.AccessibilityEvent
+
+class McpAccessibilityService : AccessibilityService() {
+
+    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
+        // Stub: initial scaffolding — implementation provided in a later plan (approved by user)
+    }
+
+    override fun onInterrupt() {
+    }
+
+    companion object {
+        private const val TAG = "MCP:AccessibilityService"
+    }
+}
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/BootCompletedReceiver.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/BootCompletedReceiver.kt
@@ -0,0 +1,15 @@
+package com.danielealbano.androidremotecontrolmcp.services.mcp
+
+import android.content.BroadcastReceiver
+import android.content.Context
+import android.content.Intent
+
+class BootCompletedReceiver : BroadcastReceiver() {
+
+    override fun onReceive(context: Context?, intent: Intent?) {
+        // Stub: initial scaffolding — implementation provided in a later plan (approved by user)
+    }
+
+    companion object {
+        private const val TAG = "MCP:BootReceiver"
+    }
+}
```

> **Important**: These stubs contain single-line comments acknowledging they are intentional scaffolding stubs approved by the user. These are NOT "TODO" comments. When each plan is implemented, these classes will be fully replaced with complete implementations. The stubs exist solely to satisfy the manifest class references and allow the project to compile.

---

### Task 1.5: Create Makefile

**Description**: Create the Makefile with ALL development workflow targets defined in PROJECT.md.

**Acceptance Criteria**:
- [ ] `make help` displays all available targets with descriptions
- [ ] `make check-deps` checks for Android SDK, Java, Gradle, adb, Docker
- [ ] `make build` runs `./gradlew assembleDebug`
- [ ] `make build-release` runs `./gradlew assembleRelease`
- [ ] `make clean` runs `./gradlew clean`
- [ ] `make test-unit` runs `./gradlew test`
- [ ] `make test-integration` runs `./gradlew connectedAndroidTest`
- [ ] `make test-e2e` runs `./gradlew :e2e-tests:test`
- [ ] `make test` runs all test targets
- [ ] `make coverage` runs `./gradlew jacocoTestReport`
- [ ] `make lint` runs `./gradlew ktlintCheck detekt`
- [ ] `make lint-fix` runs `./gradlew ktlintFormat`
- [ ] `make install`, `make install-release`, `make uninstall` work correctly
- [ ] `make grant-permissions`, `make start-server`, `make forward-port` work correctly
- [ ] `make setup-emulator`, `make start-emulator`, `make stop-emulator` work correctly
- [ ] `make logs`, `make logs-clear` work correctly
- [ ] `make version-bump-patch`, `make version-bump-minor`, `make version-bump-major` correctly modify `gradle.properties`
- [ ] `make all`, `make ci` chain targets correctly

**Tests**: Verify `make help` runs without error. Full target verification happens with `make build` and `make lint` at the end of the user story.

#### Action 1.5.1: Create `Makefile`

**What**: Create the Makefile with all targets from PROJECT.md.

**Context**: The Makefile is the primary developer interface for building, testing, linting, and managing the project. All targets use `./gradlew` (not a global Gradle installation) and `adb` from the Android SDK.

**File**: `Makefile` (project root)

```diff
--- /dev/null
+++ b/Makefile
@@ -0,0 +1,220 @@
+.PHONY: help check-deps build build-release clean \
+        test-unit test-integration test-e2e test coverage \
+        lint lint-fix \
+        install install-release uninstall grant-permissions start-server forward-port \
+        setup-emulator start-emulator stop-emulator \
+        logs logs-clear \
+        version-bump-patch version-bump-minor version-bump-major \
+        all ci
+
+# Variables
+GRADLE := ./gradlew
+ADB := adb
+APP_ID := com.danielealbano.androidremotecontrolmcp
+APP_ID_DEBUG := $(APP_ID).debug
+EMULATOR_NAME := mcp_test_emulator
+EMULATOR_DEVICE := pixel_6
+EMULATOR_API := 34
+EMULATOR_IMAGE := system-images;android-$(EMULATOR_API);google_apis;x86_64
+DEFAULT_PORT := 8080
+
+# ─────────────────────────────────────────────────────────────────────────────
+# Help
+# ─────────────────────────────────────────────────────────────────────────────
+
+help: ## Show this help message
+	@echo "Android Remote Control MCP - Development Targets"
+	@echo ""
+	@echo "Usage: make <target>"
+	@echo ""
+	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | \
+		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'
+
+# ─────────────────────────────────────────────────────────────────────────────
+# Environment & Dependencies
+# ─────────────────────────────────────────────────────────────────────────────
+
+check-deps: ## Check for required development tools
+	@echo "Checking required tools..."
+	@echo ""
+	@MISSING=0; \
+	if [ -z "$$ANDROID_HOME" ]; then \
+		echo "  [MISSING] ANDROID_HOME is not set"; \
+		echo "           Install Android SDK and set: export ANDROID_HOME=~/Android/Sdk"; \
+		MISSING=1; \
+	else \
+		echo "  [OK] ANDROID_HOME = $$ANDROID_HOME"; \
+	fi; \
+	if command -v java >/dev/null 2>&1; then \
+		JAVA_VER=$$(java -version 2>&1 | head -1 | awk -F'"' '{print $$2}'); \
+		echo "  [OK] Java $$JAVA_VER"; \
+	else \
+		echo "  [MISSING] Java (JDK 17 required)"; \
+		echo "           Install: https://adoptium.net/"; \
+		MISSING=1; \
+	fi; \
+	if [ -f "$(GRADLE)" ]; then \
+		echo "  [OK] Gradle wrapper found"; \
+	else \
+		echo "  [MISSING] Gradle wrapper (gradlew)"; \
+		echo "           Run: gradle wrapper --gradle-version 8.14.4"; \
+		MISSING=1; \
+	fi; \
+	if command -v $(ADB) >/dev/null 2>&1; then \
+		ADB_VER=$$($(ADB) version | head -1); \
+		echo "  [OK] $$ADB_VER"; \
+	else \
+		echo "  [MISSING] adb (Android Debug Bridge)"; \
+		echo "           Install Android SDK platform-tools"; \
+		MISSING=1; \
+	fi; \
+	if command -v docker >/dev/null 2>&1; then \
+		DOCKER_VER=$$(docker --version); \
+		echo "  [OK] $$DOCKER_VER"; \
+	else \
+		echo "  [MISSING] Docker (required for E2E tests)"; \
+		echo "           Install: https://docs.docker.com/get-docker/"; \
+		MISSING=1; \
+	fi; \
+	echo ""; \
+	if [ $$MISSING -eq 1 ]; then \
+		echo "Some dependencies are missing. Please install them."; \
+		exit 1; \
+	else \
+		echo "All dependencies are present."; \
+	fi
+
+# ─────────────────────────────────────────────────────────────────────────────
+# Build
+# ─────────────────────────────────────────────────────────────────────────────
+
+build: ## Build debug APK
+	$(GRADLE) assembleDebug
+
+build-release: ## Build release APK
+	$(GRADLE) assembleRelease
+
+clean: ## Clean build artifacts
+	$(GRADLE) clean
+
+# ─────────────────────────────────────────────────────────────────────────────
+# Testing
+# ─────────────────────────────────────────────────────────────────────────────
+
+test-unit: ## Run unit tests
+	$(GRADLE) test
+
+test-integration: ## Run integration tests (requires device/emulator)
+	$(GRADLE) connectedAndroidTest
+
+test-e2e: ## Run E2E tests (requires Docker)
+	$(GRADLE) :e2e-tests:test
+
+test: test-unit test-integration test-e2e ## Run all tests
+
+coverage: ## Generate code coverage report (Jacoco)
+	$(GRADLE) jacocoTestReport
+	@echo "Coverage report: app/build/reports/jacoco/index.html"
+
+# ─────────────────────────────────────────────────────────────────────────────
+# Linting
+# ─────────────────────────────────────────────────────────────────────────────
+
+lint: ## Run all linters (ktlint + detekt)
+	$(GRADLE) ktlintCheck detekt
+
+lint-fix: ## Auto-fix linting issues
+	$(GRADLE) ktlintFormat
+
+# ─────────────────────────────────────────────────────────────────────────────
+# Device Management
+# ─────────────────────────────────────────────────────────────────────────────
+
+install: ## Install debug APK on connected device/emulator
+	$(GRADLE) installDebug
+
+install-release: ## Install release APK on connected device/emulator
+	$(GRADLE) installRelease
+
+uninstall: ## Uninstall app from connected device/emulator
+	$(ADB) uninstall $(APP_ID) 2>/dev/null || true
+	$(ADB) uninstall $(APP_ID_DEBUG) 2>/dev/null || true
+
+grant-permissions: ## Display instructions for granting permissions
+	@echo "=== Permission Setup Instructions ==="
+	@echo ""
+	@echo "1. Accessibility Service (must be enabled manually):"
+	@echo "   Settings > Accessibility > Android Remote Control MCP > Enable"
+	@echo ""
+	@echo "   Or open Settings directly:"
+	@echo "   $(ADB) shell am start -a android.settings.ACCESSIBILITY_SETTINGS"
+	@echo ""
+	@echo "2. MediaProjection (granted when prompted in app):"
+	@echo "   Start the MCP server in the app and grant the screen capture permission"
+	@echo "   when the system dialog appears."
+	@echo ""
+	@echo "3. Notifications (Android 13+):"
+	@echo "   The app will request notification permission on first launch."
+	@echo ""
+
+# Note: start-server defaults to the debug application ID (APP_ID_DEBUG).
+# To launch the release build, use: make start-server APP_ID_TARGET=$(APP_ID)
+APP_ID_TARGET ?= $(APP_ID_DEBUG)
+
+start-server: ## Launch MainActivity on device (debug build by default)
+	$(ADB) shell am start -n $(APP_ID_TARGET)/.ui.MainActivity
+
+forward-port: ## Set up adb port forwarding (device -> host)
+	$(ADB) forward tcp:$(DEFAULT_PORT) tcp:$(DEFAULT_PORT)
+	@echo "Port forwarding: localhost:$(DEFAULT_PORT) -> device:$(DEFAULT_PORT)"
+
+# ─────────────────────────────────────────────────────────────────────────────
+# Emulator Management
+# ─────────────────────────────────────────────────────────────────────────────
+
+setup-emulator: ## Create AVD for testing
+	@echo "Creating AVD '$(EMULATOR_NAME)'..."
+	@echo "Ensure system image is installed: sdkmanager '$(EMULATOR_IMAGE)'"
+	avdmanager create avd \
+		-n $(EMULATOR_NAME) \
+		-k "$(EMULATOR_IMAGE)" \
+		--device "$(EMULATOR_DEVICE)" \
+		--force
+	@echo "AVD '$(EMULATOR_NAME)' created."
+
+start-emulator: ## Start emulator in background (headless)
+	@echo "Starting emulator '$(EMULATOR_NAME)'..."
+	emulator -avd $(EMULATOR_NAME) -no-snapshot -no-window -no-audio &
+	@echo "Waiting for emulator to boot..."
+	$(ADB) wait-for-device
+	$(ADB) shell getprop sys.boot_completed | grep -q 1 || \
+		(echo "Waiting for boot..."; while [ "$$($(ADB) shell getprop sys.boot_completed 2>/dev/null)" != "1" ]; do sleep 2; done)
+	@echo "Emulator is ready."
+
+stop-emulator: ## Stop running emulator
+	$(ADB) -s emulator-5554 emu kill 2>/dev/null || true
+	@echo "Emulator stopped."
+
+# ─────────────────────────────────────────────────────────────────────────────
+# Logging & Debugging
+# ─────────────────────────────────────────────────────────────────────────────
+
+logs: ## Show app logs (filtered by MCP tags)
+	$(ADB) logcat -s "MCP:*" "AndroidRemoteControl:*"
+
+logs-clear: ## Clear logcat buffer
+	$(ADB) logcat -c
+	@echo "Logcat buffer cleared."
+
+# ─────────────────────────────────────────────────────────────────────────────
+# Versioning
+# ─────────────────────────────────────────────────────────────────────────────
+
+version-bump-patch: ## Bump patch version (1.0.0 -> 1.0.1)
+	@CURRENT=$$(grep '^VERSION_NAME=' gradle.properties | cut -d= -f2); \
+	MAJOR=$$(echo $$CURRENT | cut -d. -f1); \
+	MINOR=$$(echo $$CURRENT | cut -d. -f2); \
+	PATCH=$$(echo $$CURRENT | cut -d. -f3); \
+	NEW_PATCH=$$((PATCH + 1)); \
+	NEW_VERSION="$$MAJOR.$$MINOR.$$NEW_PATCH"; \
+	sed -i "s/^VERSION_NAME=.*/VERSION_NAME=$$NEW_VERSION/" gradle.properties; \
+	CODE=$$(grep '^VERSION_CODE=' gradle.properties | cut -d= -f2); \
+	NEW_CODE=$$((CODE + 1)); \
+	sed -i "s/^VERSION_CODE=.*/VERSION_CODE=$$NEW_CODE/" gradle.properties; \
+	echo "Version bumped: $$CURRENT -> $$NEW_VERSION (code: $$CODE -> $$NEW_CODE)"
+
+version-bump-minor: ## Bump minor version (1.0.0 -> 1.1.0)
+	@CURRENT=$$(grep '^VERSION_NAME=' gradle.properties | cut -d= -f2); \
+	MAJOR=$$(echo $$CURRENT | cut -d. -f1); \
+	MINOR=$$(echo $$CURRENT | cut -d. -f2); \
+	NEW_MINOR=$$((MINOR + 1)); \
+	NEW_VERSION="$$MAJOR.$$NEW_MINOR.0"; \
+	sed -i "s/^VERSION_NAME=.*/VERSION_NAME=$$NEW_VERSION/" gradle.properties; \
+	CODE=$$(grep '^VERSION_CODE=' gradle.properties | cut -d= -f2); \
+	NEW_CODE=$$((CODE + 1)); \
+	sed -i "s/^VERSION_CODE=.*/VERSION_CODE=$$NEW_CODE/" gradle.properties; \
+	echo "Version bumped: $$CURRENT -> $$NEW_VERSION (code: $$CODE -> $$NEW_CODE)"
+
+version-bump-major: ## Bump major version (1.0.0 -> 2.0.0)
+	@CURRENT=$$(grep '^VERSION_NAME=' gradle.properties | cut -d= -f2); \
+	MAJOR=$$(echo $$CURRENT | cut -d. -f1); \
+	NEW_MAJOR=$$((MAJOR + 1)); \
+	NEW_VERSION="$$NEW_MAJOR.0.0"; \
+	sed -i "s/^VERSION_NAME=.*/VERSION_NAME=$$NEW_VERSION/" gradle.properties; \
+	CODE=$$(grep '^VERSION_CODE=' gradle.properties | cut -d= -f2); \
+	NEW_CODE=$$((CODE + 1)); \
+	sed -i "s/^VERSION_CODE=.*/VERSION_CODE=$$NEW_CODE/" gradle.properties; \
+	echo "Version bumped: $$CURRENT -> $$NEW_VERSION (code: $$CODE -> $$NEW_CODE)"
+
+# ─────────────────────────────────────────────────────────────────────────────
+# All-in-One
+# ─────────────────────────────────────────────────────────────────────────────
+
+all: clean build lint test-unit ## Run full workflow (clean, build, lint, test-unit)
+
+ci: check-deps lint test-unit build-release ## Run CI workflow
```

---

### Task 1.6: Create CI/CD Pipeline and .gitignore

**Description**: Create the GitHub Actions CI workflow and the project .gitignore file.

**Acceptance Criteria**:
- [ ] `.github/workflows/ci.yml` is valid YAML (passes `gh act --validate`)
- [ ] CI pipeline triggers on push to main and pull requests
- [ ] CI pipeline has lint, test-unit, and build jobs
- [ ] Build job uploads APK artifacts
- [ ] `.gitignore` excludes build/, .gradle/, .idea/, local.properties, keystore files, etc.

**Tests**: Run `gh act --validate` to verify workflow syntax.

#### Action 1.6.1: Create `.github/workflows/ci.yml`

**What**: Create the CI/CD pipeline configuration.

**Context**: The CI pipeline runs lint, unit tests, and build on every push and PR. Integration and E2E test jobs are commented out (stubs) because they require an Android emulator/Docker, which will be fully set up in Plan 10. The pipeline uses `actions/checkout`, `actions/setup-java`, and `gradle/actions/setup-gradle` for caching.

**File**: `.github/workflows/ci.yml`

```diff
--- /dev/null
+++ b/.github/workflows/ci.yml
@@ -0,0 +1,94 @@
+name: CI
+
+on:
+  push:
+    branches: [main]
+  pull_request:
+    branches: [main]
+
+concurrency:
+  group: ${{ github.workflow }}-${{ github.ref }}
+  cancel-in-progress: true
+
+jobs:
+  lint:
+    name: Lint
+    runs-on: ubuntu-latest
+    steps:
+      - name: Checkout
+        uses: actions/checkout@v4
+
+      - name: Set up JDK 17
+        uses: actions/setup-java@v4
+        with:
+          java-version: '17'
+          distribution: 'temurin'
+
+      - name: Set up Gradle
+        uses: gradle/actions/setup-gradle@v4
+
+      - name: Run ktlint
+        run: ./gradlew ktlintCheck
+
+      - name: Run detekt
+        run: ./gradlew detekt
+
+  test-unit:
+    name: Unit Tests
+    runs-on: ubuntu-latest
+    needs: lint
+    steps:
+      - name: Checkout
+        uses: actions/checkout@v4
+
+      - name: Set up JDK 17
+        uses: actions/setup-java@v4
+        with:
+          java-version: '17'
+          distribution: 'temurin'
+
+      - name: Set up Gradle
+        uses: gradle/actions/setup-gradle@v4
+
+      - name: Run unit tests
+        run: ./gradlew test
+
+      - name: Upload test results
+        if: always()
+        uses: actions/upload-artifact@v4
+        with:
+          name: unit-test-results
+          path: app/build/reports/tests/
+          retention-days: 14
+
+  build:
+    name: Build
+    runs-on: ubuntu-latest
+    needs: test-unit
+    steps:
+      - name: Checkout
+        uses: actions/checkout@v4
+
+      - name: Set up JDK 17
+        uses: actions/setup-java@v4
+        with:
+          java-version: '17'
+          distribution: 'temurin'
+
+      - name: Set up Gradle
+        uses: gradle/actions/setup-gradle@v4
+
+      - name: Build debug APK
+        run: ./gradlew assembleDebug
+
+      - name: Build release APK
+        run: ./gradlew assembleRelease
+
+      - name: Upload debug APK
+        uses: actions/upload-artifact@v4
+        with:
+          name: app-debug
+          path: app/build/outputs/apk/debug/app-debug.apk
+          retention-days: 14
+
+      - name: Upload release APK
+        uses: actions/upload-artifact@v4
+        with:
+          name: app-release
+          path: app/build/outputs/apk/release/app-release-unsigned.apk
+          retention-days: 14
+
+  # Stub: initial scaffolding — full CI chain (lint -> test-unit -> test-integration -> test-e2e -> build-release)
+  # will be completed in Plan 10 (approved by user). Currently only: lint -> test-unit -> build.
+  # test-integration:
+  #   ...
+  # test-e2e:
+  #   ...
```

> **Note**: The release APK artifact name is `app-release-unsigned.apk` because no signing config is available in CI by default. When release signing is configured (via GitHub Secrets for keystore), this should be updated to `app-release.apk`.

---

#### Action 1.6.2: Create `.gitignore`

**What**: Create the project .gitignore file.

**Context**: Standard Android .gitignore that excludes build artifacts, IDE files, local configuration, and sensitive files (keystores, credentials).

**File**: `.gitignore` (project root)

```diff
--- /dev/null
+++ b/.gitignore
@@ -0,0 +1,52 @@
+# Gradle
+.gradle/
+build/
+!gradle/wrapper/gradle-wrapper.jar
+
+# Android Studio / IntelliJ IDEA
+.idea/
+*.iml
+*.iws
+*.ipr
+.navigation/
+captures/
+
+# Local configuration
+local.properties
+keystore.properties
+
+# Keystores (sensitive)
+*.jks
+*.keystore
+
+# Environment / Secrets
+.env
+.env.*
+.secrets
+
+# OS files
+.DS_Store
+Thumbs.db
+
+# Kotlin
+*.class
+
+# APK files (build outputs)
+*.apk
+*.ap_
+*.aab
+
+# Generated files
+gen/
+out/
+
+# Android profiling
+*.hprof
+
+# Crash logs
+crash-reports/
+
+# Coverage reports
+jacoco/
+
+# Lint reports
+lint-results*
+
+# Test results (keep in CI artifacts, not in repo)
+test-results/
```

---

### Task 1.7: Create README.md

**Description**: Create the README.md skeleton with all required sections.

**Acceptance Criteria**:
- [ ] README.md exists with Project title, description, Features, Requirements, Quick Start, Building, Testing, MCP Tools, Architecture, Configuration, Security, Contributing, License sections
- [ ] License section references MIT

**Tests**: No automated tests for documentation.

#### Action 1.7.1: Create `README.md`

**What**: Create the README skeleton.

**Context**: The README provides a quick overview for developers and users. Placeholder sections will be filled in as features are implemented in later plans.

**File**: `README.md` (project root)

```diff
--- /dev/null
+++ b/README.md
@@ -0,0 +1,149 @@
+# Android Remote Control MCP
+
+An Android application that runs as an MCP (Model Context Protocol) server, enabling AI models to fully control an Android device remotely using accessibility services and screenshot capture.
+
+## Features
+
+- MCP server running directly on Android device over HTTPS
+- Full UI introspection via Android Accessibility Services
+- Screenshot capture via MediaProjection
+- Coordinate-based and element-based touch interactions
+- Text input and keyboard actions
+- System actions (back, home, recents, notifications)
+- Advanced gesture support (swipe, pinch, custom gestures)
+- Bearer token authentication
+- Configurable binding address (localhost or network)
+- Auto-start on boot
+- Material Design 3 configuration UI with dark mode
+
+## Requirements
+
+- Android device or emulator running Android 8.0+ (API 26+), targeting Android 14 (API 34)
+- JDK 17
+- Android SDK with API 34
+- Docker (for E2E tests only)
+
+## Quick Start
+
+1. Clone the repository:
+   ```bash
+   git clone https://github.com/danielealbano/android-remote-control-mcp.git
+   cd android-remote-control-mcp
+   ```
+
+2. Check dependencies:
+   ```bash
+   make check-deps
+   ```
+
+3. Build the debug APK:
+   ```bash
+   make build
+   ```
+
+4. Install on a connected device/emulator:
+   ```bash
+   make install
+   ```
+
+5. Open the app and follow the setup instructions:
+   - Enable the Accessibility Service in Android Settings
+   - Grant screen capture permission
+   - Start the MCP server
+
+6. Connect your MCP client:
+   ```bash
+   # If using localhost binding (default), set up port forwarding:
+   make forward-port
+   # Then connect to https://localhost:8080
+   ```
+
+## Building
+
+```bash
+make build           # Build debug APK
+make build-release   # Build release APK
+make clean           # Clean build artifacts
+```
+
+## Testing
+
+```bash
+make test-unit         # Run unit tests
+make test-integration  # Run integration tests (requires device/emulator)
+make test-e2e          # Run E2E tests (requires Docker)
+make test              # Run all tests
+make coverage          # Generate coverage report
+```
+
+## Linting
+
+```bash
+make lint       # Run ktlint and detekt
+make lint-fix   # Auto-fix linting issues
+```
+
+## MCP Tools
+
+The server exposes the following MCP tool categories:
+
+| Category | Tools | Description |
+|----------|-------|-------------|
+| Screen Introspection | `get_accessibility_tree`, `capture_screenshot`, `get_current_app`, `get_screen_info` | Query device screen state |
+| Touch Actions | `tap`, `long_press`, `double_tap`, `swipe`, `scroll` | Coordinate-based interactions |
+| Element Actions | `find_elements`, `click_element`, `long_click_element`, `set_text`, `scroll_to_element` | Accessibility node interactions |
+| Text Input | `input_text`, `clear_text`, `press_key` | Keyboard input |
+| System Actions | `press_back`, `press_home`, `press_recents`, `open_notifications`, `open_quick_settings`, `get_device_logs` | Global device actions |
+| Gestures | `pinch`, `custom_gesture` | Advanced multi-touch gestures |
+| Utilities | `get_clipboard`, `set_clipboard`, `wait_for_element`, `wait_for_idle` | Helper tools |
+
+See [MCP_TOOLS.md](docs/MCP_TOOLS.md) for detailed tool documentation.
+
+## Architecture
+
+The application is service-based with four main components:
+
+- **McpServerService** - Foreground service running Ktor HTTPS server
+- **McpAccessibilityService** - Android AccessibilityService for UI introspection and actions
+- **ScreenCaptureService** - Foreground service managing MediaProjection for screenshots
+- **MainActivity** - Jetpack Compose UI for configuration and control
+
+See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed architecture documentation.
+
+## Configuration
+
+| Setting | Default | Description |
+|---------|---------|-------------|
+| Port | 8080 | HTTPS server port |
+| Binding Address | 127.0.0.1 | Localhost only (use 0.0.0.0 for network access) |
+| Bearer Token | Auto-generated | Authentication token for MCP clients |
+| HTTPS | Always on | Self-signed certificate (auto-generated) or custom |
+| Auto-start | Disabled | Start MCP server on device boot |
+
+## Security
+
+- **HTTPS only**: All connections use TLS encryption
+- **Bearer token authentication**: Every MCP request requires a valid token
+- **Localhost by default**: Server binds to 127.0.0.1 (requires ADB port forwarding)
+- **No root required**: Application uses standard Android APIs only
+- **Minimal permissions**: Only requests permissions that are strictly necessary
+
+## Development
+
+```bash
+make help              # Show all available targets
+make check-deps        # Verify development environment
+make setup-emulator    # Create test emulator
+make start-emulator    # Start emulator (headless)
+make logs              # View app logs
+```
+
+## Contributing
+
+Contributions are welcome. Please follow the coding conventions defined in [PROJECT.md](docs/PROJECT.md) and the development workflow defined in [TOOLS.md](docs/TOOLS.md).
+
+## License
+
+MIT License. See [LICENSE](LICENSE) for details.
```

---

### Task 1.8: Verification and Commit

**Description**: Run all verification commands to ensure the scaffolding is correct, then create the commits.

**Acceptance Criteria**:
- [ ] `make build` succeeds (debug APK is generated)
- [ ] `make lint` succeeds (no linting errors or warnings)
- [ ] `make test-unit` succeeds (no test failures -- there are no tests yet, but the task should complete)
- [ ] `gh act --validate` succeeds (CI workflow YAML is valid)
- [ ] All 6 commits are created on the `feat/project-scaffolding` branch

**Tests**: These are the verification steps, not unit tests.

#### Action 1.8.1: Run `make build`

**What**: Verify the project compiles successfully.

**Command**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp
make build
```

**Expected outcome**: BUILD SUCCESSFUL, `app/build/outputs/apk/debug/app-debug.apk` exists.

**If it fails**: Inspect the last 150 lines of output, identify the root cause, and fix it before proceeding. Common issues:
- Missing dependency versions (check `libs.versions.toml`)
- Property delegation errors (check `versionNameProp` / `versionCodeProp` approach in `app/build.gradle.kts`)
- Missing resources (check all resource files exist)
- Manifest merge errors (check `AndroidManifest.xml`)

---

#### Action 1.8.2: Run `make lint`

**What**: Verify no linting errors.

**Command**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp
make lint
```

**Expected outcome**: BUILD SUCCESSFUL with no ktlint or detekt violations.

**If it fails**: Run `make lint-fix` for auto-fixable issues, then re-run `make lint`. For non-auto-fixable issues, manually fix the code.

---

#### Action 1.8.3: Run `make test-unit`

**What**: Verify the test runner works (even with no tests).

**Command**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp
make test-unit
```

**Expected outcome**: BUILD SUCCESSFUL. No tests run (0 tests), but the task completes without failure.

---

#### Action 1.8.4: Run `gh act --validate`

**What**: Validate the CI workflow YAML syntax.

**Command**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp
gh act --validate
```

**Expected outcome**: Workflow is valid, no syntax errors.

**If `gh act` is not installed**: Install via `gh extension install nektos/gh-act`, or manually validate the YAML using an online validator. Alternatively, skip this check if `act` cannot be installed without user consent.

---

#### Action 1.8.5: Create the feature branch and commits

**What**: Create the `feat/project-scaffolding` branch and make all 6 commits as defined in the commit strategy.

**Commands**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp

# Create and switch to feature branch
git checkout -b feat/project-scaffolding

# Commit 1: Gradle project configuration
git add settings.gradle.kts gradle.properties gradle/libs.versions.toml build.gradle.kts \
       app/build.gradle.kts app/proguard-rules.pro gradlew gradlew.bat gradle/wrapper/
git commit -m "$(cat <<'EOF'
chore: add Gradle project configuration with all dependencies

Set up root and app build.gradle.kts with Kotlin DSL, configure
libs.versions.toml version catalog with all project dependencies,
and define gradle.properties with version and build settings.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"

# Commit 2: Android manifest and resources
git add app/src/main/AndroidManifest.xml app/src/main/res/
git commit -m "$(cat <<'EOF'
feat: add Android manifest and application resources

Declare all permissions, services, receiver, and activity in manifest.
Add string resources, Material 3 theme, accessibility service config,
notification icon, and adaptive launcher icons.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"

# Commit 3: Application entry point and Hilt DI
git add app/src/main/kotlin/
git commit -m "$(cat <<'EOF'
feat: add Application entry point and Hilt DI setup

Create McpApplication with @HiltAndroidApp, AppModule for DI,
and minimal stub classes for all manifest-declared components
(MainActivity, McpServerService, ScreenCaptureService,
McpAccessibilityService, BootCompletedReceiver).

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"

# Commit 4: Makefile
git add Makefile
git commit -m "$(cat <<'EOF'
chore: add Makefile with development workflow targets

Implement all Makefile targets defined in PROJECT.md including
build, test, lint, device management, emulator, versioning,
and composite workflow targets.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"

# Commit 5: CI/CD and .gitignore
git add .github/workflows/ci.yml .gitignore
git commit -m "$(cat <<'EOF'
chore: add CI/CD pipeline and .gitignore

Create GitHub Actions CI workflow with lint, test-unit, and build
jobs. Add concurrency control for canceling stale runs. Include
standard Android .gitignore excluding build artifacts, IDE files,
keystores, and sensitive configuration.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"

# Commit 6: README
git add README.md
git commit -m "$(cat <<'EOF'
docs: add README.md skeleton

Add project README with sections for features, requirements,
quick start, building, testing, MCP tools overview, architecture,
configuration, security, development, contributing, and MIT license.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```

---

#### Action 1.8.6: Push and create PR

**What**: Push the branch and create the pull request.

**Commands**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp

# Push feature branch
git push -u origin feat/project-scaffolding

# Create PR
gh pr create --base main --title "Plan 1: Project scaffolding and build system" --body "$(cat <<'EOF'
## Summary

Complete Android project scaffolding from scratch. Establishes Gradle build system with Kotlin DSL, version catalog for all dependencies, Android manifest with all component declarations, Material 3 theme, Hilt DI setup, Makefile with all development workflow targets, GitHub Actions CI pipeline, and project README.

## Plan Reference

Implementation of Plan 1: Project Scaffolding & Build System from `docs/plans/1_project-scaffolding-build-system_20260211163214.md`.

## Changes

- Gradle wrapper (8.14.4), settings.gradle.kts, gradle.properties, libs.versions.toml
- Root and app build.gradle.kts with all plugins and dependencies
- AndroidManifest.xml with permissions, services, receiver, activity
- String resources, Material 3 DayNight theme, accessibility service config
- Notification icon and adaptive launcher icons
- McpApplication (@HiltAndroidApp) and AppModule (Hilt DI)
- Minimal stub classes for all manifest-declared components
- Makefile with all PROJECT.md targets (build, test, lint, device, emulator, versioning)
- GitHub Actions CI pipeline (lint, test-unit, build with APK artifact upload)
- .gitignore for Android project
- README.md skeleton

## Testing

- `make build` succeeds (debug APK generated)
- `make lint` succeeds (no ktlint/detekt violations)
- `make test-unit` succeeds (0 tests, no failures)
- `gh act --validate` succeeds (CI YAML is valid)

## Checklist

- [ ] All tests pass (`make test-unit`)
- [ ] Lint passes (`make lint`)
- [ ] Build succeeds (`make build`)
- [ ] CI validated locally (`gh act --validate`)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Performance, Security, and QA Review

### Performance

- **No concerns for this plan**: This plan only sets up project scaffolding. There is no runtime code to profile.
- **Gradle configuration**: JVM args set to `-Xmx2048m` which is appropriate for Android builds. The `gradle/actions/setup-gradle@v4` action in CI handles Gradle caching automatically.
- **CI concurrency**: The CI workflow uses `cancel-in-progress: true` to avoid wasting resources on stale pipeline runs.

### Security

- **Keystore exclusion**: `.gitignore` correctly excludes `*.jks`, `*.keystore`, `keystore.properties`, `.env`, `.secrets`. No risk of accidental credential commits.
- **Bearer token**: Not yet implemented in this plan (Plan 6), but the architecture is prepared for it (Ktor auth dependency included).
- **HTTPS dependencies**: Ktor TLS dependency (`ktor-server-tls`) is included in the version catalog, ready for Plan 6.
- **No secrets in code**: All stub classes are empty -- no hardcoded tokens, keys, or credentials.
- **CI secrets**: The CI workflow does not use any secrets. Release signing will need GitHub Secrets when enabled in Plan 10.

### QA

- **Build verification**: The plan explicitly includes `make build`, `make lint`, `make test-unit`, and `gh act --validate` as verification steps.
- **Manifest completeness**: All four services, the broadcast receiver, and the main activity are declared. Each service has the correct `foregroundServiceType`. The accessibility service has `BIND_ACCESSIBILITY_SERVICE` permission and meta-data reference.
- **Version catalog completeness**: All dependencies from PROJECT.md Tech Stack section are included. Each version has a "verify latest at implementation time" comment.
- **Stub classes**: Every class referenced in the manifest has a corresponding minimal stub to prevent `ClassNotFoundException` at build time. The stubs are intentionally minimal and will be fully implemented in their respective plans.
- **Gradle property delegation**: The `versionNameProp`/`versionCodeProp` properties use `project.findProperty()` with safe defaults, which is the correct approach for Gradle properties with underscore-named keys.
- **MCP SDK coordinates**: The Maven coordinates for `mcp-kotlin-sdk` (`io.modelcontextprotocol:kotlin-sdk`) must be verified at implementation time against the official repository.

---

## File Inventory

All files created in this plan:

| # | File Path | Task |
|---|-----------|------|
| 1 | `gradlew` | 1.1 |
| 2 | `gradlew.bat` | 1.1 |
| 3 | `gradle/wrapper/gradle-wrapper.jar` | 1.1 |
| 4 | `gradle/wrapper/gradle-wrapper.properties` | 1.1 |
| 5 | `settings.gradle.kts` | 1.2 |
| 6 | `gradle.properties` | 1.2 |
| 7 | `gradle/libs.versions.toml` | 1.2 |
| 8 | `build.gradle.kts` (root) | 1.2 |
| 9 | `app/build.gradle.kts` | 1.2 |
| 10 | `app/proguard-rules.pro` | 1.2 |
| 11 | `app/src/main/AndroidManifest.xml` | 1.3 |
| 12 | `app/src/main/res/values/strings.xml` | 1.3 |
| 13 | `app/src/main/res/values/themes.xml` | 1.3 |
| 14 | `app/src/main/res/xml/accessibility_service_config.xml` | 1.3 |
| 15 | `app/src/main/res/drawable/ic_notification.xml` | 1.3 |
| 16 | `app/src/main/res/drawable/ic_launcher_foreground.xml` | 1.3 |
| 17 | `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | 1.3 |
| 18 | `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | 1.3 |
| 19 | `app/src/main/res/values/ic_launcher_background.xml` | 1.3 |
| 20 | `app/src/main/kotlin/.../McpApplication.kt` | 1.4 |
| 21 | `app/src/main/kotlin/.../di/AppModule.kt` | 1.4 |
| 22 | `app/src/main/kotlin/.../ui/MainActivity.kt` | 1.4 |
| 23 | `app/src/main/kotlin/.../services/mcp/McpServerService.kt` | 1.4 |
| 24 | `app/src/main/kotlin/.../services/screencapture/ScreenCaptureService.kt` | 1.4 |
| 25 | `app/src/main/kotlin/.../services/accessibility/McpAccessibilityService.kt` | 1.4 |
| 26 | `app/src/main/kotlin/.../services/mcp/BootCompletedReceiver.kt` | 1.4 |
| 27 | `Makefile` | 1.5 |
| 28 | `.github/workflows/ci.yml` | 1.6 |
| 29 | `.gitignore` | 1.6 |
| 30 | `README.md` | 1.7 |

**Total**: 30 files

---

**End of Plan 1**
