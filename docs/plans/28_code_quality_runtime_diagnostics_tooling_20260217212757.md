# Plan 28: Code Quality and Runtime Diagnostics Tooling

## Overview

Integrate static analysis, runtime diagnostics, and coverage tooling to improve code quality verification. This plan adds tools that can be run from CLI (for automated validation after code changes) and runtime diagnostics that detect memory leaks, crashes, StrictMode violations, and GC issues during E2E tests.

## Scope

### Included (as discussed)

**Static Analysis (CLI, no emulator):**
1. Android Lint — built-in AGP static analysis (memory leaks, performance, security, correctness)
2. Compose Compiler Metrics — composable stability/restartability reports
3. detekt-compose rules — Compose-specific static analysis rules
4. Dependency Analysis Plugin — detect unused/misused dependencies

**Runtime Diagnostics (emulator, E2E):**
5. StrictMode with `penaltyDropBox()` — disk/network violations persisted by Android system
6. LeakCanary 2.14 — runtime memory leak detection (auto-persists to disk)
7. UncaughtExceptionHandler — writes crash info to file before process death
8. Diagnostics collection in E2E tests — queries `dumpsys dropbox` + LeakCanary files + crash log
9. E2E teardown assertion — fails test if any diagnostics found (`ZZ_E2EDiagnosticsTest` — named to sort last alphabetically)

**Coverage Migration:**
10. JaCoCo → Kover migration — more accurate Kotlin coverage

### Explicitly Excluded (as discussed)

- ~~Runtime metrics periodic dump~~ — no actionable value
- ~~Logcat scraping~~ — too token-heavy / noisy
- ~~In-memory diagnostics HTTP endpoint~~ — flaky if app crashes, data lost
- ~~Android Studio Profiler / Perfetto / Layout Inspector~~ — GUI-only tools

## Dependency Versions

| Dependency | Version | Notes |
|---|---|---|
| LeakCanary (`com.squareup.leakcanary:leakcanary-android`) | 2.14 | Latest stable |
| detekt-compose (`io.nlopez.compose.rules:detekt`) | 0.4.23 | Compatible with detekt 1.23.8 |
| Dependency Analysis (`com.autonomousapps.dependency-analysis`) | 3.5.1 | Gradle plugin |
| Kover (`org.jetbrains.kotlinx.kover`) | 0.9.7 | Replaces JaCoCo |

---

## User Story 1: Static Analysis Tooling

**Description:** As a developer, I want Android Lint, Compose Compiler Metrics, detekt-compose rules, and Dependency Analysis available as CLI commands so I can validate code quality after every change.

**Acceptance Criteria / Definition of Done:**
- [ ] `./gradlew lintDebug` runs Android Lint and generates HTML/XML reports
- [ ] `make lint` runs Android Lint + ktlint + detekt (including compose rules)
- [ ] `make compose-metrics` generates Compose stability/restartability reports
- [ ] `make deps-health` runs dependency analysis and outputs advice
- [ ] All four tools pass on the current codebase without errors
- [ ] CI workflow includes Android Lint
- [ ] `make lint` passes
- [ ] `./gradlew :app:test` passes

---

### Task 1.1: Add detekt-compose rules dependency

**Definition of Done:** detekt-compose rules are loaded as a detekt plugin. Running `./gradlew detekt` includes Compose-specific rule checks.

**Acceptance Criteria:**
- [ ] `io.nlopez.compose.rules:detekt:0.4.23` added to version catalog and build config
- [ ] `./gradlew detekt` runs Compose rules alongside standard detekt rules
- [ ] No new detekt violations on current codebase (or fix any that appear)

#### Action 1.1.1: Add detekt-compose version and library to version catalog

**File:** `gradle/libs.versions.toml`

**Explanation:** Add the version and library entry for the detekt-compose rules plugin.

```diff
 [versions]
 # Linting
 ktlint-gradle = "14.0.1"
 detekt = "1.23.8"
+detekt-compose = "0.4.23"
 gradle-versions = "0.53.0"
 version-catalog-update = "1.0.1"
```

```diff
 [libraries]
+# Detekt Compose Rules
+detekt-compose-rules = { group = "io.nlopez.compose.rules", name = "detekt", version.ref = "detekt-compose" }
```

- [ ] Done

#### Action 1.1.2: Add detekt-compose plugin dependency in app build

**File:** `app/build.gradle.kts`

**Explanation:** Add the compose rules as a `detektPlugins` dependency so detekt loads them automatically. No config file needed — rules use their defaults.

```diff
 dependencies {
+    // Detekt Compose Rules
+    detektPlugins(libs.detekt.compose.rules)
+
     // AndroidX Core
     implementation(libs.androidx.core.ktx)
```

- [ ] Done

#### Action 1.1.3: Run detekt and fix any new violations

**Command:** `./gradlew detekt`

**Explanation:** The compose rules may flag existing code. Fix any violations found (these are Compose best practice issues like mutable state params, missing modifier defaults, etc.). If there are many, discuss with the user.

- [ ] Done

---

### Task 1.2: Configure Android Lint

**Definition of Done:** `./gradlew lintDebug` runs successfully with HTML/XML report generation. Lint is part of the `make lint` target and CI.

**Acceptance Criteria:**
- [ ] `lint {}` block configured in `app/build.gradle.kts`
- [ ] `./gradlew lintDebug` generates reports in `app/build/reports/lint-results-debug.html`
- [ ] Lint passes (zero errors) on current codebase, or existing issues are fixed
- [ ] Lint baseline generated if needed (to handle any pre-existing issues)

#### Action 1.2.1: Add lint configuration block to app build

**File:** `app/build.gradle.kts`

**Explanation:** Add a `lint {}` block inside `android {}` to configure report generation, error handling, and output. Set `abortOnError = true` so lint failures fail the build (consistent with ktlint/detekt behavior). Disable checking dependencies (we only care about our code).

Add after the `packaging {}` block (before the closing `}` of `android {}`):

```diff
     packaging {
         // ... existing packaging config ...
     }
+
+    lint {
+        xmlReport = true
+        htmlReport = true
+        abortOnError = true
+        checkDependencies = false
+        baseline = file("lint-baseline.xml")
+    }
 }
```

**Note:** The `baseline` file does not exist initially. On first run, if lint finds issues, run `./gradlew lintDebug -Dlint.baselines.continue=true` to generate it. Subsequent runs will only report NEW issues. If lint passes cleanly on first run, the baseline file is created empty and can be committed.

- [ ] Done

#### Action 1.2.2: Run Android Lint and handle results

**Command:** `./gradlew lintDebug`

**Explanation:** Run lint on the current codebase. If there are existing errors:
1. Run `./gradlew lintDebug -Dlint.baselines.continue=true` to generate `app/lint-baseline.xml`
2. Review the baseline — fix any critical/easy issues
3. Commit the baseline file (captures pre-existing issues)

If lint passes cleanly, no baseline is needed — remove the `baseline` line from the config.

- [ ] Done

---

### Task 1.3: Configure Compose Compiler Metrics

**Definition of Done:** Running a command generates Compose compiler stability/restartability reports in a known location.

**Acceptance Criteria:**
- [ ] `composeCompiler {}` block configured with conditional metrics flag
- [ ] `make compose-metrics` generates reports in `app/build/compose_compiler/`
- [ ] Reports include composable stability, restartability, and class stability data

#### Action 1.3.1: Add Compose Compiler metrics configuration

**File:** `app/build.gradle.kts`

**Explanation:** Configure the Compose compiler plugin to emit metrics/reports when a Gradle property is passed. This does not affect normal builds — metrics are only generated when explicitly requested.

Add after the `kotlin { ... }` block (around line 94-98):

```diff
 kotlin {
     compilerOptions {
         jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
     }
 }
+
+composeCompiler {
+    val enableReports = project.findProperty("enableComposeCompilerReports") == "true"
+    if (enableReports) {
+        val outputDir = layout.buildDirectory.dir("compose_compiler").get().asFile.absolutePath
+        metricsDestination.set(file(outputDir))
+        reportsDestination.set(file(outputDir))
+    }
+}
```

- [ ] Done

#### Action 1.3.2: Add Makefile target for Compose metrics

**File:** `Makefile`

**Explanation:** Add a `compose-metrics` target that builds with the metrics flag and prints the output location.

Add in the Linting section (after the `lint-fix` target):

```diff
 lint-fix: ## Auto-fix linting issues
 	$(GRADLE) ktlintFormat

+compose-metrics: ## Generate Compose compiler stability/restartability reports
+	$(GRADLE) assembleDebug -PenableComposeCompilerReports=true
+	@echo "Compose compiler reports: app/build/compose_compiler/"
+
```

- [ ] Done

---

### Task 1.4: Add Dependency Analysis Plugin

**Definition of Done:** `./gradlew buildHealth` runs and reports unused/misused dependencies.

**Acceptance Criteria:**
- [ ] Dependency analysis plugin applied in root `build.gradle.kts`
- [ ] `./gradlew buildHealth` produces actionable advice
- [ ] `make deps-health` target available

#### Action 1.4.1: Add dependency-analysis plugin to version catalog

**File:** `gradle/libs.versions.toml`

**Explanation:** Add the plugin version and entry.

```diff
 [versions]
 # Linting
 ktlint-gradle = "14.0.1"
 detekt = "1.23.8"
 detekt-compose = "0.4.23"
 gradle-versions = "0.53.0"
 version-catalog-update = "1.0.1"
+dependency-analysis = "3.5.1"
```

```diff
 [plugins]
 # ... existing plugins ...
 gradle-versions = { id = "com.github.ben-manes.versions", version.ref = "gradle-versions" }
 version-catalog-update = { id = "nl.littlerobots.version-catalog-update", version.ref = "version-catalog-update" }
+dependency-analysis = { id = "com.autonomousapps.dependency-analysis", version.ref = "dependency-analysis" }
```

- [ ] Done

#### Action 1.4.2: Apply dependency-analysis plugin in root build

**File:** `build.gradle.kts` (root)

**Explanation:** Apply the plugin in the root project.

```diff
 plugins {
     // ... existing plugins ...
     alias(libs.plugins.gradle.versions)
     alias(libs.plugins.version.catalog.update)
+    alias(libs.plugins.dependency.analysis)
 }
```

- [ ] Done

#### Action 1.4.3: Add Makefile target for dependency health

**File:** `Makefile`

**Explanation:** Add a `deps-health` target.

Add after the `compose-metrics` target:

```diff
 compose-metrics: ## Generate Compose compiler stability/restartability reports
 	$(GRADLE) assembleDebug -PenableComposeCompilerReports=true
 	@echo "Compose compiler reports: app/build/compose_compiler/"

+deps-health: ## Analyze dependency usage (unused, misused, etc.)
+	$(GRADLE) buildHealth
+
```

- [ ] Done

#### Action 1.4.4: Run dependency analysis and review

**Command:** `./gradlew buildHealth`

**Explanation:** Run the dependency analysis on the current codebase. Review the output for unused dependencies, misused `implementation` vs `api` declarations, etc. Address any actionable findings. Note: some findings may be false positives (e.g., dependencies used only at runtime via reflection/annotation processing). Use judgment.

- [ ] Done

---

### Task 1.5: Update Makefile lint target and CI workflow

**Definition of Done:** `make lint` runs all linters (ktlint + detekt + Android Lint). CI lint job includes Android Lint.

**Acceptance Criteria:**
- [ ] `make lint` target includes `lintDebug`
- [ ] CI lint job runs `lintDebug` and uploads report
- [ ] All linters pass in CI

#### Action 1.5.1: Update Makefile lint target

**File:** `Makefile`

**Explanation:** Add `lintDebug` to the `lint` target so Android Lint runs alongside ktlint and detekt.

```diff
-lint: ## Run all linters (ktlint + detekt)
-	$(GRADLE) ktlintCheck detekt
+lint: ## Run all linters (ktlint + detekt + Android Lint)
+	$(GRADLE) ktlintCheck detekt lintDebug
```

Also update the `.PHONY` line at the top to include the new targets:

```diff
 .PHONY: help check-deps check-deps-updates update-deps build build-release clean \
         test-unit test-integration test-e2e test coverage \
-        lint lint-fix \
+        lint lint-fix compose-metrics deps-health \
```

- [ ] Done

#### Action 1.5.2: Add Android Lint to CI workflow

**File:** `.github/workflows/ci.yml`

**Explanation:** Add a lint step for Android Lint in the lint job. Upload the HTML report as an artifact.

**Note:** Unlike ktlint and detekt (which are code-style checkers), `lintDebug` compiles the code before analyzing it. The lint job already runs `./gradlew ktlintCheck` which triggers compilation, so prerequisites are satisfied. If the CI job structure changes, ensure the lint job has the Android SDK setup and compilation step before `lintDebug`.

In the `lint` job, after the "Run detekt" step:

```diff
       - name: Run detekt
         run: ./gradlew detekt

+      - name: Run Android Lint
+        run: ./gradlew lintDebug
+
+      - name: Upload Android Lint report
+        if: always()
+        uses: actions/upload-artifact@v4
+        with:
+          name: lint-report
+          path: app/build/reports/lint-results-debug.html
+          retention-days: 14
```

- [ ] Done

#### Action 1.5.3: Verify all static analysis tools pass

**Commands:**
1. `make lint` — must pass (ktlint + detekt + Android Lint)
2. `make compose-metrics` — must generate reports
3. `make deps-health` — must run successfully

- [ ] Done

---

## User Story 2: Runtime Diagnostics in Debug Builds

**Description:** As a developer, I want the debug build to automatically detect memory leaks (LeakCanary), thread/VM violations (StrictMode), and persist crash data (UncaughtExceptionHandler) so that runtime issues are captured on disk for post-mortem analysis.

**Acceptance Criteria / Definition of Done:**
- [ ] LeakCanary auto-initializes in debug builds and writes leak traces to disk
- [ ] StrictMode configured with `penaltyDropBox()` in debug builds (disk I/O on main thread, leaked closeables, activity leaks, cleartext network detected)
- [ ] UncaughtExceptionHandler writes crash details to `files/diagnostics/crash_reports.log` before process death
- [ ] Debug APK builds and installs successfully
- [ ] No interference with release builds (all diagnostics are debug-only)
- [ ] `make lint` passes
- [ ] `./gradlew :app:test` passes

---

### Task 2.1: Add LeakCanary dependency

**Definition of Done:** LeakCanary is a `debugImplementation` dependency. It auto-initializes in debug builds via ContentProvider and stores heap dumps in `cache/leakcanary/` (app cache directory).

**Acceptance Criteria:**
- [ ] LeakCanary version and library in version catalog
- [ ] `debugImplementation` dependency in `app/build.gradle.kts`
- [ ] Debug APK builds successfully

#### Action 2.1.1: Add LeakCanary to version catalog

**File:** `gradle/libs.versions.toml`

**Explanation:** Add LeakCanary version and library entry.

```diff
 [versions]
 # Testing
 junit5 = "5.13.4"
 mockk = "1.14.9"
 turbine = "1.2.1"
 testcontainers = "1.20.4"
+
+# Debug tools
+leakcanary = "2.14"
```

```diff
 [libraries]
+# LeakCanary (debug-only memory leak detection)
+leakcanary-android = { group = "com.squareup.leakcanary", name = "leakcanary-android", version.ref = "leakcanary" }
+
 # AndroidX Core
```

- [ ] Done

#### Action 2.1.2: Add LeakCanary debugImplementation dependency

**File:** `app/build.gradle.kts`

**Explanation:** Add LeakCanary as a `debugImplementation` dependency. It auto-initializes via ContentProvider — no code changes needed for basic leak detection. The library is completely excluded from release builds.

```diff
     debugImplementation(libs.compose.ui.tooling)

+    // Debug-only: LeakCanary memory leak detection (auto-initializes via ContentProvider)
+    debugImplementation(libs.leakcanary.android)
+
     // Lifecycle
```

- [ ] Done

---

### Task 2.2: Add StrictMode with penaltyDropBox in debug builds

**Definition of Done:** StrictMode is configured in `McpApplication.onCreate()` for debug builds only. Thread policy detects disk/network on main thread. VM policy detects leaked closeables, activity leaks, cleartext network. All violations are sent to Android's DropBoxManager via `penaltyDropBox()`.

**Acceptance Criteria:**
- [ ] StrictMode setup gated behind `BuildConfig.DEBUG`
- [ ] ThreadPolicy: `detectDiskReads()`, `detectDiskWrites()`, `detectNetwork()`, `penaltyDropBox()`
- [ ] VmPolicy: `detectLeakedClosableObjects()`, `detectLeakedSqlLiteObjects()`, `detectActivityLeaks()`, `detectCleartextNetwork()`, `detectFileUriExposure()`, `penaltyDropBox()`
- [ ] Debug APK builds and runs without crash on startup

#### Action 2.2.1: Add StrictMode setup in McpApplication

**File:** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/McpApplication.kt`

**Explanation:** Add a `setupStrictMode()` private method called via `Handler(Looper.getMainLooper()).post {}` to delay activation until AFTER `Application.onCreate()`, `super.onCreate()`, Hilt/Dagger injection, ContentProvider initialization (including LeakCanary), DataStore init, and `createNotificationChannels()` complete. These framework operations legitimately perform disk I/O on the main thread. Setting up StrictMode immediately would flood DropBox with false positives from these known-safe init calls, making `ZZ_E2EDiagnosticsTest` permanently fail.

Uses `penaltyDropBox()` which sends violations to Android's DropBoxManager — these persist on disk and can be queried via `adb shell dumpsys dropbox --print data_app_strictmode`. The `penaltyLog()` is also included for logcat visibility during manual testing.

**Note on `penaltyListener()` (API 28+):** An alternative to `penaltyDropBox()` is `penaltyListener(executor) { violation -> ... }` which provides structured `Violation` objects. This could be used in the future for more precise collection. For now, `penaltyDropBox()` is simpler and works with the existing `dumpsys dropbox` collection approach.

```diff
 package com.danielealbano.androidremotecontrolmcp

 import android.app.Application
 import android.app.NotificationChannel
 import android.app.NotificationManager
+import android.os.Handler
+import android.os.Looper
+import android.os.StrictMode
 import android.util.Log
 import dagger.hilt.android.HiltAndroidApp

 @HiltAndroidApp
 class McpApplication : Application() {
     override fun onCreate() {
         super.onCreate()
+        if (BuildConfig.DEBUG) {
+            // Delay StrictMode activation until after framework init completes.
+            // Application.onCreate(), Hilt, ContentProviders (LeakCanary), DataStore,
+            // and createNotificationChannels() all perform legitimate disk I/O.
+            Handler(Looper.getMainLooper()).post {
+                setupStrictMode()
+                Log.d(TAG, "StrictMode enabled with penaltyDropBox for debug build")
+            }
+        }
         createNotificationChannels()
         Log.i(TAG, "Application initialized, notification channels created")
     }
+
+    private fun setupStrictMode() {
+        StrictMode.setThreadPolicy(
+            StrictMode.ThreadPolicy.Builder()
+                .detectDiskReads()
+                .detectDiskWrites()
+                .detectNetwork()
+                .penaltyLog()
+                .penaltyDropBox()
+                .build(),
+        )
+        StrictMode.setVmPolicy(
+            StrictMode.VmPolicy.Builder()
+                .detectLeakedClosableObjects()
+                .detectLeakedSqlLiteObjects()
+                .detectActivityLeaks()
+                .detectCleartextNetwork()
+                .detectFileUriExposure()
+                .penaltyLog()
+                .penaltyDropBox()
+                .build(),
+        )
+    }
```

- [ ] Done

---

### Task 2.3: Add UncaughtExceptionHandler in debug builds

**Definition of Done:** A custom `UncaughtExceptionHandler` is installed in debug builds that writes crash details (timestamp, thread, exception, stack trace) to `files/diagnostics/crash_reports.log` before the process dies. The original handler is called afterwards so normal crash behavior is preserved.

**Acceptance Criteria:**
- [ ] Handler installed in `McpApplication.onCreate()` gated behind `BuildConfig.DEBUG`
- [ ] Crash details written to `files/diagnostics/crash_reports.log` (app internal storage)
- [ ] Uses `CrashReportFormatter` shared utility (single source of truth for format)
- [ ] File write uses `synchronized` + `FileOutputStream` + `flush()` + `fd.sync()` for durability
- [ ] Original default handler called after writing (preserves system crash dialog + DropBoxManager collection)
- [ ] File is append-mode (multiple crashes accumulate)

#### Action 2.3.1: Create CrashReportFormatter shared utility

**File:** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/diagnostics/CrashReportFormatter.kt` (new file)

**Explanation:** Extract the crash report format construction into a shared utility so the format contract is defined in one place. Both `McpApplication` (writer) and `CrashReportFormatTest` (reader) use this, preventing format drift.

```kotlin
package com.danielealbano.androidremotecontrolmcp.diagnostics

import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Formats crash reports for the custom UncaughtExceptionHandler.
 *
 * This is the single source of truth for the crash report format.
 * Both the writer (McpApplication) and parser (E2E DiagnosticsCollector)
 * depend on this format.
 */
object CrashReportFormatter {

    /** Separator used between crash entries in the log file. */
    const val ENTRY_SEPARATOR = "=== CRASH:"

    /**
     * Format a crash report entry.
     *
     * @param timestamp the time of the crash
     * @param threadName the name of the thread that crashed
     * @param throwable the uncaught exception
     * @return formatted crash report string (includes trailing newline)
     */
    fun format(timestamp: Instant, threadName: String, throwable: Throwable): String =
        buildString {
            appendLine("$ENTRY_SEPARATOR $timestamp ===")
            appendLine("Thread: $threadName")
            appendLine("Exception: ${throwable.javaClass.name}")
            appendLine("Message: ${throwable.message}")
            appendLine("Stack:")
            val writer = StringWriter()
            throwable.printStackTrace(PrintWriter(writer))
            appendLine(writer.toString())
            appendLine()
        }
}
```

- [ ] Done

#### Action 2.3.2: Add UncaughtExceptionHandler setup in McpApplication

**File:** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/McpApplication.kt`

**Explanation:** Add a `setupCrashHandler()` private method that installs a custom `UncaughtExceptionHandler`. On crash, it writes a structured entry (via `CrashReportFormatter`) to a log file, then delegates to the original handler. The file write uses `synchronized` to prevent concurrent writes, `FileOutputStream` in append mode with explicit `flush()` and `fd.sync()` to ensure data is durably persisted before the process dies. Bare `appendText()` does NOT guarantee durability — the OS may buffer the write and the process could be killed before it reaches disk.

Add the call in `onCreate()` and the method implementation:

```diff
         if (BuildConfig.DEBUG) {
-            // Delay StrictMode activation until after framework init completes.
+            setupCrashHandler()
+            // Delay StrictMode activation until after framework init completes.
             // Application.onCreate(), Hilt, ContentProviders (LeakCanary), DataStore,
             // and createNotificationChannels() all perform legitimate disk I/O.
             Handler(Looper.getMainLooper()).post {
```

**Note:** `setupCrashHandler()` is called BEFORE the `Handler.post {}` for StrictMode so it is active immediately.

Add the new method after `setupStrictMode()`:

```kotlin
    private fun setupCrashHandler() {
        val crashWriteLock = Any()
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val diagnosticsDir = java.io.File(filesDir, "diagnostics")
                diagnosticsDir.mkdirs()
                val crashFile = java.io.File(diagnosticsDir, "crash_reports.log")
                val report = CrashReportFormatter.format(
                    timestamp = java.time.Instant.now(),
                    threadName = thread.name,
                    throwable = throwable,
                )
                synchronized(crashWriteLock) {
                    java.io.FileOutputStream(crashFile, true).use { fos ->
                        fos.write(report.toByteArray(Charsets.UTF_8))
                        fos.flush()
                        fos.fd.sync()
                    }
                }
                Log.e(TAG, "Crash report written to ${crashFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash report", e)
            }
            originalHandler?.uncaughtException(thread, throwable)
        }
        Log.d(TAG, "Debug crash handler installed")
    }
```

Add import at top of file:

```diff
+import com.danielealbano.androidremotecontrolmcp.diagnostics.CrashReportFormatter
```

- [ ] Done

#### Action 2.3.3: Add required imports

**File:** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/McpApplication.kt`

**Explanation:** Ensure all necessary imports are present. The `Handler`, `Looper`, `StrictMode` imports were added in Task 2.2. The `CrashReportFormatter` import was added in Action 2.3.2. The `java.io.File`, `java.io.FileOutputStream`, `java.time.Instant` are used with fully qualified names to keep imports minimal.

Verify these imports are at the top of the file:

```kotlin
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import com.danielealbano.androidremotecontrolmcp.diagnostics.CrashReportFormatter
```

- [ ] Done

#### Action 2.3.4: Verify debug build

**Command:** `./gradlew assembleDebug`

**Explanation:** Ensure the debug APK builds successfully with StrictMode, LeakCanary, and the crash handler.

- [ ] Done

---

### Task 2.4: Unit tests for CrashReportFormatter and crash handler format

**Definition of Done:** Unit tests verify the `CrashReportFormatter` produces correct, parseable output. Tests call the shared production code — no format duplication between writer and test.

**Acceptance Criteria:**
- [ ] Unit test verifies crash report contains expected sections (timestamp, thread, exception, stack)
- [ ] Tests use `CrashReportFormatter.format()` (production code), not duplicate format logic
- [ ] Test runs as part of `./gradlew :app:test`

#### Action 2.4.1: Create unit test for CrashReportFormatter

**File:** `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/diagnostics/CrashReportFormatTest.kt`

**Explanation:** Tests call `CrashReportFormatter.format()` (the same production code used by `McpApplication`) and verify the output contains expected sections. This prevents format drift between writer and reader — if the format changes, both the crash handler and these tests follow automatically.

```kotlin
package com.danielealbano.androidremotecontrolmcp.diagnostics

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class CrashReportFormatTest {

    @Test
    fun `crash report format contains required sections`() {
        val exception = RuntimeException("Test crash")
        val timestamp = Instant.now()

        val report = CrashReportFormatter.format(
            timestamp = timestamp,
            threadName = "main",
            throwable = exception,
        )

        assertTrue(report.contains(CrashReportFormatter.ENTRY_SEPARATOR))
        assertTrue(report.contains(timestamp.toString()))
        assertTrue(report.contains("Thread: main"))
        assertTrue(report.contains("Exception: java.lang.RuntimeException"))
        assertTrue(report.contains("Message: Test crash"))
        assertTrue(report.contains("Stack:"))
        assertTrue(report.contains("CrashReportFormatTest"))
    }

    @Test
    fun `crash report format handles null message`() {
        val exception = NullPointerException()

        val report = CrashReportFormatter.format(
            timestamp = Instant.now(),
            threadName = "worker-1",
            throwable = exception,
        )

        assertTrue(report.contains("Exception: java.lang.NullPointerException"))
        assertTrue(report.contains("Message: null"))
    }

    @Test
    fun `crash report format handles nested exceptions`() {
        val cause = IllegalArgumentException("root cause")
        val exception = RuntimeException("wrapper", cause)

        val report = CrashReportFormatter.format(
            timestamp = Instant.now(),
            threadName = "io-thread",
            throwable = exception,
        )

        assertTrue(report.contains("Caused by: java.lang.IllegalArgumentException: root cause"))
    }

    @Test
    fun `crash report ENTRY_SEPARATOR matches format output`() {
        val report = CrashReportFormatter.format(
            timestamp = Instant.now(),
            threadName = "test",
            throwable = RuntimeException("test"),
        )

        // Verify the report starts with the separator (used by E2E parser to split entries)
        assertTrue(report.startsWith(CrashReportFormatter.ENTRY_SEPARATOR))
    }
}
```

- [ ] Done

#### Action 2.4.2: Run unit tests

**Command:** `./gradlew :app:test`

**Explanation:** Verify the new test passes and no existing tests are broken.

- [ ] Done

---

## User Story 3: E2E Diagnostics Collection

**Description:** As a developer, I want E2E tests to automatically collect runtime diagnostics (crashes, ANRs, StrictMode violations, memory leaks, crash handler logs) from the Docker Android emulator after test execution and fail if any issues are found.

**Acceptance Criteria / Definition of Done:**
- [ ] `DiagnosticsCollector` utility class created in E2E test module
- [ ] Collects: DropBox crashes, DropBox ANRs, DropBox StrictMode violations, LeakCanary heap dump names, crash handler log
- [ ] All collection uses `container.execInContainer()` with `adb shell` commands (no logcat)
- [ ] `SharedAndroidContainer` calls diagnostics collector on JVM shutdown hook (before container stops)
- [ ] Output is small and structured (not logcat)
- [ ] `ZZ_E2EDiagnosticsTest` fails if any diagnostics contain issues for the app package
- [ ] `ZZ_E2EDiagnosticsTest` asserts non-null result (no silent pass if container not initialized)
- [ ] `make lint` passes
- [ ] `./gradlew :app:test` passes

---

### Task 3.1: Create DiagnosticsCollector utility

**Definition of Done:** A utility class that executes adb commands inside the Docker container to collect runtime diagnostics. Returns structured results.

**Acceptance Criteria:**
- [ ] Collects from `dumpsys dropbox` (crashes, ANRs, StrictMode)
- [ ] Collects LeakCanary heap dump file names (`.hprof` in `cache/leakcanary/`) — list only, not binary content
- [ ] Collects crash handler log file
- [ ] Filters by app package name (`com.danielealbano.androidremotecontrolmcp.debug`)
- [ ] Returns structured `DiagnosticsResult` with categorized findings
- [ ] Parsing logic extracted into `internal` methods with unit tests
- [ ] Output is compact (small strings, not raw logcat)

#### Action 3.1.1: Create DiagnosticsResult data class

**File:** `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/DiagnosticsResult.kt` (new file)

**Explanation:** Data class holding categorized diagnostics findings.

```kotlin
package com.danielealbano.androidremotecontrolmcp.e2e

/**
 * Structured results from runtime diagnostics collection.
 *
 * Each list contains string entries describing individual findings.
 * Empty lists mean no issues found for that category.
 */
data class DiagnosticsResult(
    val crashes: List<String>,
    val anrs: List<String>,
    val strictModeViolations: List<String>,
    val memoryLeaks: List<String>,
    val crashHandlerEntries: List<String>,
) {
    /** True if any diagnostics were found. */
    val hasIssues: Boolean
        get() = crashes.isNotEmpty() ||
            anrs.isNotEmpty() ||
            strictModeViolations.isNotEmpty() ||
            memoryLeaks.isNotEmpty() ||
            crashHandlerEntries.isNotEmpty()

    /** Human-readable summary of all findings. */
    fun summary(): String = buildString {
        if (!hasIssues) {
            appendLine("No runtime diagnostics issues found.")
            return@buildString
        }
        appendLine("=== Runtime Diagnostics Issues ===")
        if (crashes.isNotEmpty()) {
            appendLine("CRASHES (${crashes.size}):")
            crashes.forEach { appendLine("  - $it") }
        }
        if (anrs.isNotEmpty()) {
            appendLine("ANRs (${anrs.size}):")
            anrs.forEach { appendLine("  - $it") }
        }
        if (strictModeViolations.isNotEmpty()) {
            appendLine("STRICTMODE VIOLATIONS (${strictModeViolations.size}):")
            strictModeViolations.forEach { appendLine("  - $it") }
        }
        if (memoryLeaks.isNotEmpty()) {
            appendLine("MEMORY LEAKS (${memoryLeaks.size}):")
            memoryLeaks.forEach { appendLine("  - $it") }
        }
        if (crashHandlerEntries.isNotEmpty()) {
            appendLine("CRASH HANDLER ENTRIES (${crashHandlerEntries.size}):")
            crashHandlerEntries.forEach { appendLine("  - $it") }
        }
    }
}
```

- [ ] Done

#### Action 3.1.2: Create DiagnosticsCollector class

**File:** `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/DiagnosticsCollector.kt` (new file)

**Explanation:** Utility that executes adb commands inside the Docker container to collect diagnostics from DropBoxManager, LeakCanary heap dumps, and the custom crash handler log. All commands use `container.execInContainer()`. The output from `dumpsys dropbox` is small and structured. LeakCanary heap dumps are binary `.hprof` files — we only list them (not read contents) since their existence alone proves leaks were detected. Crash logs are read directly via `adb shell run-as`.

Parsing logic is extracted into `internal` companion methods (`parseDropBoxOutput`, `parseLeakCanaryFileList`, `parseCrashHandlerLog`) so they can be unit-tested independently without mocking the Docker container.

```kotlin
package com.danielealbano.androidremotecontrolmcp.e2e

import org.testcontainers.containers.GenericContainer

/**
 * Collects runtime diagnostics from the Android emulator inside a Docker container.
 *
 * Uses system-level collection mechanisms:
 * - `dumpsys dropbox` for crashes, ANRs, and StrictMode violations (persisted by Android system)
 * - LeakCanary heap dump files in cache/leakcanary/ (persisted by LeakCanary)
 * - Custom crash handler log file (persisted by UncaughtExceptionHandler)
 *
 * All data is on-disk and survives app crashes. No logcat scraping.
 */
object DiagnosticsCollector {

    internal const val APP_PACKAGE = "com.danielealbano.androidremotecontrolmcp.debug"

    /**
     * Collect all runtime diagnostics from the container.
     *
     * @param container the running Docker Android container
     * @return structured diagnostics results
     */
    fun collect(container: GenericContainer<*>): DiagnosticsResult {
        println("[Diagnostics] Collecting runtime diagnostics...")

        val crashes = collectDropBox(container, "data_app_crash")
        val anrs = collectDropBox(container, "data_app_anr")
        val strictMode = collectDropBox(container, "data_app_strictmode")
        val leaks = collectLeakCanaryFiles(container)
        val crashHandler = collectCrashHandlerLog(container)

        val result = DiagnosticsResult(
            crashes = crashes,
            anrs = anrs,
            strictModeViolations = strictMode,
            memoryLeaks = leaks,
            crashHandlerEntries = crashHandler,
        )

        println("[Diagnostics] Collection complete: " +
            "${crashes.size} crashes, ${anrs.size} ANRs, " +
            "${strictMode.size} StrictMode violations, " +
            "${leaks.size} leaks, ${crashHandler.size} crash handler entries")

        return result
    }

    /**
     * Parse dumpsys dropbox output into individual entries filtered by app package.
     *
     * @param output raw output from `dumpsys dropbox --print <tag>`
     * @param appPackage the app package to filter for
     * @return list of truncated entry strings
     */
    internal fun parseDropBoxOutput(output: String, appPackage: String): List<String> {
        if (output.isBlank()) return emptyList()

        return output
            .split(Regex("(?=\\d{4}-\\d{2}-\\d{2})"))
            .filter { it.contains(appPackage) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.length > 500) it.take(500) + "..." else it }
    }

    /**
     * Parse file listing output to extract heap dump file names.
     *
     * @param output raw output from `find cache/leakcanary -name '*.hprof'`
     * @return list of "Heap dump: <filename>" entries
     */
    internal fun parseLeakCanaryFileList(output: String): List<String> {
        if (output.isBlank()) return emptyList()

        return output.trim().lines()
            .filter { it.isNotBlank() && it.endsWith(".hprof") }
            .map { filePath ->
                val fileName = filePath.substringAfterLast("/")
                "Heap dump detected: $fileName (leak analysis triggered)"
            }
    }

    /**
     * Parse crash handler log into individual crash entries.
     *
     * @param output raw content of crash_reports.log
     * @return list of truncated crash entry strings
     */
    internal fun parseCrashHandlerLog(output: String): List<String> {
        if (output.isBlank()) return emptyList()

        return output
            .split("=== CRASH:")
            .filter { it.isNotBlank() }
            .map { "CRASH:${it.trim()}" }
            .map { if (it.length > 500) it.take(500) + "..." else it }
    }

    /**
     * Query Android's DropBoxManager for entries of the given tag,
     * filtered to our app package.
     */
    private fun collectDropBox(container: GenericContainer<*>, tag: String): List<String> {
        return try {
            val result = container.execInContainer(
                "sh", "-c",
                "adb shell dumpsys dropbox --print $tag 2>/dev/null"
            )
            if (result.exitCode != 0 || result.stdout.isBlank()) {
                return emptyList()
            }

            parseDropBoxOutput(result.stdout, APP_PACKAGE)
        } catch (e: Exception) {
            println("[Diagnostics] Failed to collect $tag from dropbox: ${e.message}")
            emptyList()
        }
    }

    /**
     * Collect LeakCanary heap dump files from app cache storage.
     *
     * LeakCanary 2.14 stores .hprof heap dump files in cache/leakcanary/.
     * These are binary files — we only list them (not read contents) since
     * their existence alone proves that leaks were detected and heap was dumped.
     */
    private fun collectLeakCanaryFiles(container: GenericContainer<*>): List<String> {
        return try {
            val listResult = container.execInContainer(
                "sh", "-c",
                "adb shell run-as $APP_PACKAGE find cache/leakcanary -type f -name '*.hprof' 2>/dev/null || true"
            )

            parseLeakCanaryFileList(listResult.stdout)
        } catch (e: Exception) {
            println("[Diagnostics] Failed to collect LeakCanary files: ${e.message}")
            emptyList()
        }
    }

    /**
     * Collect crash handler log file written by the custom UncaughtExceptionHandler.
     * The file is at files/diagnostics/crash_reports.log.
     */
    private fun collectCrashHandlerLog(container: GenericContainer<*>): List<String> {
        return try {
            val result = container.execInContainer(
                "sh", "-c",
                "adb shell run-as $APP_PACKAGE cat files/diagnostics/crash_reports.log 2>/dev/null || true"
            )

            parseCrashHandlerLog(result.stdout)
        } catch (e: Exception) {
            println("[Diagnostics] Failed to collect crash handler log: ${e.message}")
            emptyList()
        }
    }
}
```

- [ ] Done

#### Action 3.1.3: Create unit tests for DiagnosticsCollector parsing logic

**File:** `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/DiagnosticsCollectorParsingTest.kt` (new file)

**Explanation:** Unit tests for the extracted parsing methods (`parseDropBoxOutput`, `parseLeakCanaryFileList`, `parseCrashHandlerLog`). These are pure JVM tests with no Docker dependency — they test string parsing only. This prevents parsing bugs from silently discarding real diagnostics.

```kotlin
package com.danielealbano.androidremotecontrolmcp.e2e

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticsCollectorParsingTest {

    // --- parseDropBoxOutput ---

    @Test
    fun `parseDropBoxOutput returns empty for blank input`() {
        assertEquals(emptyList<String>(), DiagnosticsCollector.parseDropBoxOutput("", "com.test"))
        assertEquals(emptyList<String>(), DiagnosticsCollector.parseDropBoxOutput("  \n  ", "com.test"))
    }

    @Test
    fun `parseDropBoxOutput filters entries by app package`() {
        val output = """
            2026-02-17 12:00:00 data_app_crash
            Process: com.danielealbano.androidremotecontrolmcp.debug
            java.lang.RuntimeException: test crash
            
            2026-02-17 12:01:00 data_app_crash
            Process: com.other.app
            java.lang.NullPointerException
        """.trimIndent()

        val entries = DiagnosticsCollector.parseDropBoxOutput(output, DiagnosticsCollector.APP_PACKAGE)
        assertEquals(1, entries.size)
        assertTrue(entries[0].contains("com.danielealbano.androidremotecontrolmcp.debug"))
    }

    @Test
    fun `parseDropBoxOutput truncates long entries at 500 chars`() {
        val longEntry = "2026-02-17 12:00:00 data_app_crash\n" +
            "Process: ${DiagnosticsCollector.APP_PACKAGE}\n" +
            "A".repeat(600)

        val entries = DiagnosticsCollector.parseDropBoxOutput(longEntry, DiagnosticsCollector.APP_PACKAGE)
        assertEquals(1, entries.size)
        assertTrue(entries[0].length <= 503) // 500 + "..."
        assertTrue(entries[0].endsWith("..."))
    }

    @Test
    fun `parseDropBoxOutput returns empty when no entries match package`() {
        val output = """
            2026-02-17 12:00:00 data_app_crash
            Process: com.other.app
            java.lang.RuntimeException: test
        """.trimIndent()

        val entries = DiagnosticsCollector.parseDropBoxOutput(output, DiagnosticsCollector.APP_PACKAGE)
        assertTrue(entries.isEmpty())
    }

    // --- parseLeakCanaryFileList ---

    @Test
    fun `parseLeakCanaryFileList returns empty for blank input`() {
        assertEquals(emptyList<String>(), DiagnosticsCollector.parseLeakCanaryFileList(""))
        assertEquals(emptyList<String>(), DiagnosticsCollector.parseLeakCanaryFileList("  \n  "))
    }

    @Test
    fun `parseLeakCanaryFileList extracts hprof filenames`() {
        val output = """
            cache/leakcanary/2026-02-17_12-00-00_001.hprof
            cache/leakcanary/2026-02-17_12-01-00_002.hprof
        """.trimIndent()

        val entries = DiagnosticsCollector.parseLeakCanaryFileList(output)
        assertEquals(2, entries.size)
        assertTrue(entries[0].contains("2026-02-17_12-00-00_001.hprof"))
        assertTrue(entries[1].contains("2026-02-17_12-01-00_002.hprof"))
        assertTrue(entries.all { it.startsWith("Heap dump detected:") })
    }

    @Test
    fun `parseLeakCanaryFileList ignores non-hprof files`() {
        val output = """
            cache/leakcanary/leaks.db
            cache/leakcanary/2026-02-17_12-00-00_001.hprof
            cache/leakcanary/some-other-file.txt
        """.trimIndent()

        val entries = DiagnosticsCollector.parseLeakCanaryFileList(output)
        assertEquals(1, entries.size)
    }

    // --- parseCrashHandlerLog ---

    @Test
    fun `parseCrashHandlerLog returns empty for blank input`() {
        assertEquals(emptyList<String>(), DiagnosticsCollector.parseCrashHandlerLog(""))
        assertEquals(emptyList<String>(), DiagnosticsCollector.parseCrashHandlerLog("  \n  "))
    }

    @Test
    fun `parseCrashHandlerLog splits entries by separator`() {
        val output = """
            === CRASH: 2026-02-17T12:00:00Z ===
            Thread: main
            Exception: java.lang.RuntimeException
            Message: test
            Stack:
            at com.test.Foo.bar(Foo.kt:10)

            === CRASH: 2026-02-17T12:01:00Z ===
            Thread: worker-1
            Exception: java.lang.NullPointerException
            Message: null
            Stack:
            at com.test.Baz.qux(Baz.kt:20)
        """.trimIndent()

        val entries = DiagnosticsCollector.parseCrashHandlerLog(output)
        assertEquals(2, entries.size)
        assertTrue(entries[0].startsWith("CRASH:"))
        assertTrue(entries[1].startsWith("CRASH:"))
    }

    @Test
    fun `parseCrashHandlerLog truncates long entries at 500 chars`() {
        val output = "=== CRASH: 2026-02-17T12:00:00Z ===\n" +
            "Thread: main\n" +
            "Stack:\n" +
            "A".repeat(600)

        val entries = DiagnosticsCollector.parseCrashHandlerLog(output)
        assertEquals(1, entries.size)
        assertTrue(entries[0].length <= 503)
        assertTrue(entries[0].endsWith("..."))
    }
}
```

- [ ] Done

---

### Task 3.2: Integrate diagnostics into E2E test lifecycle

**Definition of Done:** The `SharedAndroidContainer` JVM shutdown hook collects diagnostics before stopping the container. If any issues are found, they are printed to stderr. A shared diagnostics result is accessible to test classes for assertions.

**Acceptance Criteria:**
- [ ] Diagnostics collected before container shutdown
- [ ] Diagnostics summary printed to console
- [ ] Diagnostics result accessible via `SharedAndroidContainer.diagnosticsResult`
- [ ] `collectDiagnostics()` is thread-safe via `synchronized(lock)`
- [ ] `ZZ_E2EDiagnosticsTest` asserts no runtime diagnostics issues found (with `assertNotNull` on result)

#### Action 3.2.1: Add diagnostics collection to SharedAndroidContainer

**File:** `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/SharedAndroidContainer.kt`

**Explanation:** Add a `diagnosticsResult` property that is populated during the JVM shutdown hook, BEFORE the container is stopped. Also add a `collectDiagnostics()` method that can be called explicitly.

Add a volatile field for the diagnostics result:

```diff
     @Volatile
     private var initError: Throwable? = null

+    @Volatile
+    private var _diagnosticsResult: DiagnosticsResult? = null
+
     private val lock = Any()
```

Add a public accessor:

```diff
     val mcpClient: McpClient
         get() {
             ensureInitialized()
             return _mcpClient!!
         }

+    /**
+     * Runtime diagnostics collected from the container.
+     * Available after [collectDiagnostics] is called (automatically called on JVM shutdown).
+     */
+    val diagnosticsResult: DiagnosticsResult?
+        get() = _diagnosticsResult
+
+    /**
+     * Collect runtime diagnostics from the container.
+     * Can be called explicitly or is called automatically during JVM shutdown.
+     * Thread-safe: uses synchronized(lock) to prevent double collection when
+     * both ZZ_E2EDiagnosticsTest and the JVM shutdown hook call this concurrently.
+     */
+    fun collectDiagnostics() {
+        synchronized(lock) {
+            if (_diagnosticsResult != null) return
+            val container = _container ?: return
+            if (!container.isRunning) return
+
+            _diagnosticsResult = DiagnosticsCollector.collect(container)
+            val result = _diagnosticsResult!!
+
+            if (result.hasIssues) {
+                System.err.println(result.summary())
+            } else {
+                println("[SharedAndroidContainer] No runtime diagnostics issues found")
+            }
+        }
+    }
```

Update the shutdown hook to collect diagnostics before stopping:

```diff
     init {
         Runtime.getRuntime().addShutdownHook(Thread {
             _container?.let { c ->
+                // Collect diagnostics BEFORE stopping the container
+                println("[SharedAndroidContainer] Collecting diagnostics before shutdown...")
+                collectDiagnostics()
+
                 println("[SharedAndroidContainer] Stopping shared container...")
                 if (c.isRunning) {
                     c.stop()
                 }
                 println("[SharedAndroidContainer] Shared container stopped")
             }
         })
     }
```

- [ ] Done

#### Action 3.2.2: Create E2E diagnostics assertion test

**File:** `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/ZZ_E2EDiagnosticsTest.kt` (new file)

**Explanation:** A test class named `ZZ_E2EDiagnosticsTest` so it sorts LAST alphabetically among the `E2E*Test` classes (existing: `E2ECalculatorTest`, `E2EErrorHandlingTest`, `E2EScreenshotTest`). Combined with `ClassOrderer$ClassName` in `junit-platform.properties`, this guarantees it runs after all other E2E tests, ensuring the full set of runtime diagnostics is collected.

Uses `assertNotNull` instead of `?: return` to avoid silently passing when the container wasn't initialized — if no container exists, something went wrong and the test should fail explicitly.

```kotlin
package com.danielealbano.androidremotecontrolmcp.e2e

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * E2E test that asserts no runtime diagnostics issues were found.
 *
 * NAMING CONVENTION: Prefixed with "ZZ_" to sort LAST alphabetically
 * among E2E test classes (E2ECalculatorTest, E2EErrorHandlingTest,
 * E2EScreenshotTest all sort before this). Combined with ClassOrderer$ClassName,
 * this guarantees diagnostics collection happens after all other E2E tests.
 *
 * It collects:
 * - Crashes from Android DropBoxManager
 * - ANRs from Android DropBoxManager
 * - StrictMode violations from Android DropBoxManager
 * - Memory leaks from LeakCanary heap dumps
 * - Crash handler entries from the custom UncaughtExceptionHandler
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZZ_E2EDiagnosticsTest {

    @Test
    fun `no runtime diagnostics issues found during E2E tests`() {
        // Trigger collection if not already done (thread-safe via synchronized)
        SharedAndroidContainer.collectDiagnostics()

        val result = SharedAndroidContainer.diagnosticsResult
        assertNotNull(result, "Diagnostics result is null — container may not have been initialized")

        assertFalse(
            result!!.hasIssues,
            "Runtime diagnostics issues found during E2E tests:\n${result.summary()}",
        )
    }
}
```

- [ ] Done

#### Action 3.2.3: Configure E2E test class ordering

**Explanation:** JUnit 5 test class execution order is not guaranteed by default. To ensure `ZZ_E2EDiagnosticsTest` runs last (after all other E2E tests), configure JUnit 5 class ordering via `ClassOrderer$ClassName` which sorts alphabetically. Existing test classes (`E2ECalculatorTest`, `E2EErrorHandlingTest`, `E2EScreenshotTest`) all start with `E2E` and sort before `ZZ_`.

**File:** `e2e-tests/src/test/resources/junit-platform.properties` (new file)

```properties
# Ensure test classes run in a predictable alphabetical order.
# ZZ_E2EDiagnosticsTest sorts LAST after all E2E*Test classes,
# guaranteeing diagnostics collection happens after all other tests.
junit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer$ClassName
```

- [ ] Done

---

### Task 3.3: Update CI workflow for diagnostics

**Definition of Done:** CI uploads diagnostics summary as an artifact when E2E tests run.

**Acceptance Criteria:**
- [ ] E2E test diagnostics summary printed in CI output
- [ ] `ZZ_E2EDiagnosticsTest` failure causes CI to fail

#### Action 3.3.1: Diagnostics are already captured

**Explanation:** No additional CI changes needed beyond what exists. The `ZZ_E2EDiagnosticsTest` runs as part of `./gradlew :e2e-tests:test`. If it fails (diagnostics found), the test suite fails and CI reports it. The diagnostics summary is printed to stdout/stderr which is already captured in CI logs and the test results artifact.

- [ ] Done (verified — no changes needed)

---

## User Story 4: JaCoCo to Kover Migration

**Description:** As a developer, I want to replace JaCoCo with Kover for more accurate Kotlin code coverage, with a 60% coverage threshold and report generation.

**Acceptance Criteria / Definition of Done:**
- [ ] JaCoCo plugin and all JaCoCo configuration removed from `app/build.gradle.kts`
- [ ] Kover plugin applied and configured with equivalent exclusion filters and verification rules
- [ ] `make coverage` generates Kover HTML report
- [ ] Coverage verification enforces 60% minimum line coverage
- [ ] CI workflow uses Kover for coverage reports and verification
- [ ] `make lint` passes
- [ ] `./gradlew :app:test` passes

---

### Task 4.1: Add Kover plugin to version catalog and root build

**Definition of Done:** Kover plugin is declared in the version catalog and applied in the root and app builds.

**Acceptance Criteria:**
- [ ] Kover version and plugin in `libs.versions.toml`
- [ ] Plugin applied in root `build.gradle.kts`
- [ ] Plugin applied in `app/build.gradle.kts`

#### Action 4.1.1: Add Kover to version catalog

**File:** `gradle/libs.versions.toml`

```diff
 [versions]
+# Coverage
+kover = "0.9.7"
+
 # Build tools
 agp = "8.13.2"
```

```diff
 [plugins]
+kover = { id = "org.jetbrains.kotlinx.kover", version.ref = "kover" }
 android-application = { id = "com.android.application", version.ref = "agp" }
```

- [ ] Done

#### Action 4.1.2: Apply Kover plugin in root build

**File:** `build.gradle.kts` (root)

```diff
 plugins {
     // ... existing plugins ...
     alias(libs.plugins.gradle.versions)
     alias(libs.plugins.version.catalog.update)
     alias(libs.plugins.dependency.analysis)
+    alias(libs.plugins.kover)
 }
```

- [ ] Done

#### Action 4.1.3: Apply Kover plugin in app build

**File:** `app/build.gradle.kts`

```diff
 plugins {
     alias(libs.plugins.android.application)
     alias(libs.plugins.kotlin.android)
     alias(libs.plugins.kotlin.compose)
     alias(libs.plugins.hilt)
     alias(libs.plugins.kotlin.serialization)
     alias(libs.plugins.ksp)
     alias(libs.plugins.ktlint)
     alias(libs.plugins.detekt)
-    jacoco
+    alias(libs.plugins.kover)
 }
```

- [ ] Done

---

### Task 4.2: Configure Kover and remove JaCoCo

**Definition of Done:** Kover is configured with exclusion filters equivalent to the current JaCoCo excludes. JaCoCo configuration is completely removed. Coverage verification enforces 60% minimum line coverage.

**Acceptance Criteria:**
- [ ] Kover `reports {}` block with exclusion filters
- [ ] Kover verification rule: 60% minimum line coverage (explicit `LINE` / `COVERED_PERCENTAGE`)
- [ ] All JaCoCo-related code removed (plugin, version, tasks, excludes)
- [ ] `./gradlew :app:koverHtmlReportDebug` generates HTML report
- [ ] `./gradlew :app:koverVerifyDebug` enforces coverage threshold

#### Action 4.2.1: Remove JaCoCo configuration from app build

**File:** `app/build.gradle.kts`

**Explanation:** Remove the `jacoco {}` block, `jacocoExcludes` list, `jacocoTestReport` task, and `jacocoTestCoverageVerification` task. These are lines 203-284 in the current file.

Remove the following blocks entirely:

```diff
-jacoco {
-    toolVersion = "0.8.14"
-}
-
-val jacocoExcludes =
-    listOf(
-        // Android generated
-        "**/R.class",
-        "**/R$*.class",
-        "**/BuildConfig.*",
-        "**/Manifest*.*",
-        // Hilt / Dagger generated
-        "**/*_HiltModules*",
-        "**/*_Factory*",
-        "**/*_MembersInjector*",
-        "**/Hilt_*",
-        "**/dagger/**",
-        "**/*Module_*",
-        "**/*_Impl*",
-        // Compose generated
-        "**/*ComposableSingletons*",
-        // Android framework classes (require device/emulator, not unit-testable)
-        "**/McpApplication*",
-        "**/services/mcp/McpServerService*",
-        "**/services/mcp/BootCompletedReceiver*",
-        "**/services/screencapture/ScreenCaptureService*",
-        "**/services/accessibility/McpAccessibilityService*",
-        // UI layer (requires instrumented/Compose tests)
-        "**/ui/**",
-        // Dependency injection configuration
-        "**/di/**",
-    )
-
-tasks.register<JacocoReport>("jacocoTestReport") {
-    dependsOn("testDebugUnitTest")
-
-    reports {
-        html.required.set(true)
-        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/jacocoTestReport/html"))
-        xml.required.set(true)
-        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacocoTestReport/jacocoTestReport.xml"))
-        csv.required.set(false)
-    }
-
-    val debugTree =
-        fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
-            exclude(jacocoExcludes)
-        }
-
-    classDirectories.setFrom(debugTree)
-    sourceDirectories.setFrom(files("src/main/kotlin"))
-    executionData.setFrom(
-        fileTree(layout.buildDirectory) {
-            include("jacoco/testDebugUnitTest.exec")
-        },
-    )
-}
-
-tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
-    dependsOn("jacocoTestReport")
-
-    val debugTree =
-        fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
-            exclude(jacocoExcludes)
-        }
-
-    classDirectories.setFrom(debugTree)
-    sourceDirectories.setFrom(files("src/main/kotlin"))
-    executionData.setFrom(
-        fileTree(layout.buildDirectory) {
-            include("jacoco/testDebugUnitTest.exec")
-        },
-    )
-
-    violationRules {
-        rule {
-            limit {
-                minimum = "0.50".toBigDecimal()
-            }
-        }
-    }
-}
```

- [ ] Done

#### Action 4.2.2: Add Kover configuration block

**File:** `app/build.gradle.kts`

**Explanation:** Add Kover configuration at the end of the file (where JaCoCo config was). Uses `androidGeneratedClasses()` helper (built into Kover) to exclude `R`, `R$*`, `BuildConfig`, `Manifest*`, and other Android-generated classes automatically. Remaining exclusion filters mirror the previous JaCoCo excludes, adapted to Kover's fully-qualified class name pattern syntax (`*` matches any sequence of characters including dots). The debug variant verification rule enforces 60% minimum line coverage with explicit `LINE` coverage units and `COVERED_PERCENTAGE` aggregation.

**Pattern translation notes (JaCoCo → Kover):**
- JaCoCo uses file path globs (`**/dagger/**`), Kover uses fully-qualified class names (`dagger.*`)
- `*` in Kover matches any characters including `.`, so `dagger.*` matches `dagger.internal.Foo`
- `$*` handles Kotlin inner/companion/lambda classes (e.g., `*.McpApplication$*`)
- `androidGeneratedClasses()` replaces manual `*.R`, `*.R$*`, `*.BuildConfig`, `*.Manifest*` patterns
- During implementation, run a Kover HTML report and verify excluded classes match JaCoCo excludes

Add at the end of the file:

```kotlin
kover {
    reports {
        filters {
            excludes {
                // Android generated classes (R, BuildConfig, Manifest, etc.)
                androidGeneratedClasses()
                classes(
                    // Hilt / Dagger generated
                    "*_HiltModules*",
                    "*_Factory*",
                    "*_MembersInjector*",
                    "*Hilt_*",
                    "dagger.*",
                    "*Module_*",
                    "*_Impl*",
                    // Compose generated
                    "*ComposableSingletons*",
                    // Android framework classes (require device/emulator)
                    "*.McpApplication",
                    "*.McpApplication$*",
                    "*.services.mcp.McpServerService",
                    "*.services.mcp.McpServerService$*",
                    "*.services.mcp.BootCompletedReceiver",
                    "*.services.screencapture.ScreenCaptureService",
                    "*.services.accessibility.McpAccessibilityService",
                    "*.services.accessibility.McpAccessibilityService$*",
                    // UI layer
                    "*.ui.*",
                    // Dependency injection configuration
                    "*.di.*",
                )
            }
        }
        variant("debug") {
            verify {
                rule {
                    minBound(60)
                }
            }
        }
    }
}
```

**Verification step during implementation:** After applying Kover, generate an HTML report (`./gradlew :app:koverHtmlReportDebug`) and compare the included/excluded classes against the previous JaCoCo report. Adjust patterns if any classes are incorrectly included or excluded.

- [ ] Done

---

### Task 4.3: Update Makefile and CI for Kover

**Definition of Done:** `make coverage` uses Kover. CI generates Kover reports and enforces coverage threshold.

**Acceptance Criteria:**
- [ ] `make coverage` runs `koverHtmlReportDebug`
- [ ] CI runs Kover report and verification instead of JaCoCo
- [ ] Coverage report uploaded as CI artifact

#### Action 4.3.1: Update Makefile coverage target

**File:** `Makefile`

```diff
-coverage: ## Generate code coverage report (Jacoco)
-	$(GRADLE) jacocoTestReport
-	@echo "Coverage report: app/build/reports/jacoco/jacocoTestReport/html/index.html"
+coverage: ## Generate code coverage report (Kover)
+	$(GRADLE) :app:koverHtmlReportDebug
+	@echo "Coverage report: app/build/reports/kover/htmlDebug/index.html"
```

- [ ] Done

#### Action 4.3.2: Update CI workflow for Kover

**File:** `.github/workflows/ci.yml`

**Explanation:** Replace JaCoCo tasks with Kover tasks in the test-unit job. Update artifact upload paths.

In the `test-unit` job:

```diff
       - name: Run unit tests with coverage
         env:
           NGROK_AUTHTOKEN: ${{ secrets.NGROK_AUTHTOKEN }}
-        run: ./gradlew :app:test jacocoTestReport jacocoTestCoverageVerification
+        run: ./gradlew :app:test :app:koverHtmlReportDebug :app:koverXmlReportDebug :app:koverVerifyDebug
```

```diff
-      - name: Upload Jacoco coverage report
+      - name: Upload Kover coverage report
         if: always()
         uses: actions/upload-artifact@v4
         with:
           name: coverage-report
           path: |
-            app/build/reports/jacoco/jacocoTestReport/html/
-            app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml
+            app/build/reports/kover/htmlDebug/
+            app/build/reports/kover/reportDebug.xml
           retention-days: 14
```

- [ ] Done

#### Action 4.3.3: Verify Kover reports and verification

**Commands:**
1. `./gradlew :app:koverHtmlReportDebug` — must generate HTML report
2. `./gradlew :app:koverVerifyDebug` — must pass (60% threshold)
3. `make coverage` — must work end-to-end

- [ ] Done

---

## User Story 5: Documentation Updates

**Description:** As a developer, I want all project documentation (PROJECT.md, TOOLS.md, CLAUDE.md) updated to reflect the new tooling.

**Acceptance Criteria / Definition of Done:**
- [ ] PROJECT.md updated: Tech Stack (Kover, LeakCanary, StrictMode), Testing Strategy (coverage tool), Build & Deployment (lint commands), Makefile Targets (new targets)
- [ ] TOOLS.md not changed (no git/PR workflow changes)
- [ ] CLAUDE.md updated: linting commands, Definition of Done (new tools)
- [ ] `make lint` passes

---

### Task 5.1: Update PROJECT.md

**Definition of Done:** PROJECT.md reflects all new tools, commands, and conventions.

**Acceptance Criteria:**
- [ ] Tech Stack section lists Kover, LeakCanary, detekt-compose, dependency-analysis
- [ ] Testing Strategy section references Kover instead of JaCoCo
- [ ] Build Tools section references Android Lint
- [ ] Makefile Targets section includes new targets
- [ ] Debug tools section added (StrictMode, LeakCanary, UncaughtExceptionHandler)

#### Action 5.1.1: Update Tech Stack section

**File:** `docs/PROJECT.md`

**Explanation:** Add new tools to the Build Tools and Testing subsections.

In the "Build Tools" subsection under Tech Stack:

```diff
 ### Build Tools

 - **Gradle 8.x**: Build system (Kotlin DSL)
 - **Makefile**: Development workflow automation
-- **ktlint** and **detekt**: Kotlin linting
+- **ktlint**, **detekt**, and **Android Lint**: Kotlin and Android linting
+- **detekt-compose** (`io.nlopez.compose.rules`): Compose-specific static analysis rules
+- **Dependency Analysis Plugin** (`com.autonomousapps.dependency-analysis`): Detect unused/misused dependencies
```

In the "Testing" subsection under Tech Stack:

```diff
 ### Testing

 - **JUnit 5**: Unit test framework
 - **MockK**: Mocking framework for Kotlin
 - **Turbine**: Flow testing library
 - **Compose UI Test**: Jetpack Compose testing
 - **Testcontainers Kotlin**: Container-based E2E tests
 - **MCP Kotlin SDK Client**: SDK `Client` + `StreamableHttpClientTransport` for E2E tests
+- **Kover**: Kotlin code coverage (replaces JaCoCo for more accurate Kotlin coverage)
+- **LeakCanary 2.14**: Runtime memory leak detection (debug builds only)
```

- [ ] Done

#### Action 5.1.2: Update Testing Strategy section

**File:** `docs/PROJECT.md`

**Explanation:** Update the Coverage subsection to reference Kover instead of JaCoCo.

```diff
 ### Coverage

-- **Target**: Minimum 80% code coverage for unit tests (Jacoco, enforced via `jacocoTestCoverageVerification`)
-- **Report**: `make coverage` or `./gradlew jacocoTestReport`
+- **Target**: Minimum 60% code coverage for unit tests (Kover, enforced via `koverVerifyDebug`)
+- **Report**: `make coverage` or `./gradlew :app:koverHtmlReportDebug`
```

- [ ] Done

#### Action 5.1.3: Update Makefile Targets section

**File:** `docs/PROJECT.md`

**Explanation:** Update the Linting and Testing tables to include new targets and update existing ones.

In the Linting table:

```diff
 ### Linting

 | Target | Description | Underlying Command |
 |--------|-------------|-------------------|
-| `lint` | Run all linters | `./gradlew ktlintCheck detekt` |
+| `lint` | Run all linters | `./gradlew ktlintCheck detekt lintDebug` |
 | `lint-fix` | Auto-fix linting issues | `./gradlew ktlintFormat` |
+| `compose-metrics` | Generate Compose compiler reports | `./gradlew assembleDebug -PenableComposeCompilerReports=true` |
+| `deps-health` | Analyze dependency usage | `./gradlew buildHealth` |
```

In the Testing table:

```diff
-| `coverage` | Generate Jacoco coverage report | `./gradlew jacocoTestReport` |
+| `coverage` | Generate Kover coverage report | `./gradlew :app:koverHtmlReportDebug` |
```

- [ ] Done

#### Action 5.1.4: Add Debug Diagnostics section

**File:** `docs/PROJECT.md`

**Explanation:** Add a new section documenting the debug-only runtime diagnostics tools.

Add before the "Default Configuration" section:

```markdown
### Debug Build Diagnostics

The debug build includes runtime diagnostics tools that are completely excluded from release builds:

- **LeakCanary 2.14**: Automatically detects memory leaks (Activity, Fragment, View, ViewModel, Service). Stores heap dumps (`.hprof` files) in `cache/leakcanary/` in app cache directory. Auto-initializes via ContentProvider — no code changes needed. **Note:** Heap dumps may temporarily freeze all threads for 1-5 seconds during analysis.
- **StrictMode**: Detects disk I/O on main thread, network on main thread, leaked closeable objects, activity leaks, cleartext network, file URI exposure. Violations are sent to Android's DropBoxManager via `penaltyDropBox()` and also logged to logcat via `penaltyLog()`. Setup is delayed via `Handler.post {}` to avoid false positives from framework initialization.
- **UncaughtExceptionHandler**: Writes crash details (timestamp, thread, exception, stack trace) to `files/diagnostics/crash_reports.log` before the process dies. Uses `FileOutputStream` with `flush()` + `fd.sync()` for durable writes. The original handler is called afterwards, preserving normal crash behavior.

**Security note:** Heap dumps may contain sensitive data (bearer tokens, keys). Crash logs may contain sensitive exception messages. All diagnostics are **debug-only** and excluded from release builds. Debug APKs should never be deployed on shared or production devices.

**Collecting diagnostics** (E2E tests):
- Crashes/ANRs/StrictMode: `adb shell dumpsys dropbox --print data_app_crash|data_app_anr|data_app_strictmode`
- LeakCanary: `adb shell run-as <package> find cache/leakcanary -type f -name '*.hprof'`
- Crash handler: `adb shell run-as <package> cat files/diagnostics/crash_reports.log`

The E2E test suite includes `ZZ_E2EDiagnosticsTest` which automatically collects and asserts no runtime diagnostics issues were found.
```

- [ ] Done

---

### Task 5.2: Update CLAUDE.md

**Definition of Done:** CLAUDE.md reflects updated linting commands and Definition of Done.

**Acceptance Criteria:**
- [ ] Linting commands section includes Android Lint
- [ ] Definition of Done mentions Android Lint, Kover, and runtime diagnostics (E2E)

#### Action 5.2.1: Update linting commands in CLAUDE.md

**File:** `CLAUDE.md`

**Explanation:** Update the linting commands section to include Android Lint.

```diff
 ### Linting commands
-- Run all linters: `make lint`
+- Run all linters: `make lint` (ktlint + detekt + Android Lint)
 - Fix auto-fixable issues: `make lint-fix`
 - Kotlin only: `./gradlew ktlintCheck` or `./gradlew ktlintFormat`
 - Detekt: `./gradlew detekt`
+- Android Lint: `./gradlew lintDebug`
+- Compose compiler metrics: `make compose-metrics`
+- Dependency health: `make deps-health`
```

- [ ] Done

#### Action 5.2.2: Update Definition of Done in CLAUDE.md

**File:** `CLAUDE.md`

**Explanation:** Update the quality gates to reference the new tools.

```diff
 A change is DONE only if all are true:

 - All relevant automated tests are written AND passing (unit, integration, e2e as appropriate).
-- No linting warnings/errors (ktlint or detekt for Kotlin).
+- No linting warnings/errors (ktlint, detekt, Android Lint).
 - The project builds without errors and without warnings (`./gradlew build` succeeds).
 - All Android Services (AccessibilityService, McpServerService) handle lifecycle correctly (no memory leaks, proper cleanup).
 - No TODOs, no commented-out dead code, no "temporary hacks".
 - Changes are small, readable, and aligned with existing Kotlin/Android patterns.
 - MCP protocol compliance verified (if MCP tools are modified).
+- Kover coverage verification passes (`./gradlew :app:koverVerifyDebug`).
```

- [ ] Done

#### Action 5.2.3: Update coverage reference in CLAUDE.md

**File:** `CLAUDE.md`

**Explanation:** The testing section mentions JaCoCo — update to Kover. Replace any explicit JaCoCo references with Kover equivalents. Ensure coverage target is documented as 60%.

- [ ] Done

---

## User Story 6: Final Validation

**Description:** As a developer, I want to verify that ALL changes from this plan work correctly from a clean state, end-to-end.

**Acceptance Criteria / Definition of Done:**
- [ ] Clean build succeeds (`./gradlew clean build`)
- [ ] All linters pass (`make lint` — ktlint + detekt with compose rules + Android Lint)
- [ ] All unit and integration tests pass (`make test-unit`)
- [ ] Kover coverage report generates successfully (`make coverage`)
- [ ] Kover coverage verification passes (60% threshold)
- [ ] Compose compiler metrics generate successfully (`make compose-metrics`)
- [ ] Dependency analysis runs successfully (`make deps-health`)
- [ ] Debug APK includes LeakCanary, StrictMode, and crash handler
- [ ] E2E tests pass including `ZZ_E2EDiagnosticsTest` (`make test-e2e`)
- [ ] CI workflow validates locally (`gh act --validate`)
- [ ] All documentation is consistent with the implemented changes
- [ ] No regressions in existing functionality

---

### Task 6.1: Full validation from clean state

**Definition of Done:** Every quality gate passes from a clean build. No regressions.

#### Action 6.1.1: Clean build

**Command:** `./gradlew clean build`

**Explanation:** Full clean build to verify no compilation errors, no warnings, and all tasks succeed.

- [ ] Done

#### Action 6.1.2: Run all linters

**Command:** `make lint`

**Explanation:** Verify ktlint + detekt (with compose rules) + Android Lint all pass.

- [ ] Done

#### Action 6.1.3: Run all unit and integration tests

**Command:** `make test-unit`

**Explanation:** Verify all unit tests and JVM integration tests pass, including the new `CrashReportFormatTest`.

- [ ] Done

#### Action 6.1.4: Generate and verify coverage

**Command:** `make coverage && ./gradlew :app:koverVerifyDebug`

**Explanation:** Verify Kover HTML report generates and 60% coverage threshold is met.

- [ ] Done

#### Action 6.1.5: Generate Compose compiler metrics

**Command:** `make compose-metrics`

**Explanation:** Verify reports are generated in `app/build/compose_compiler/`.

- [ ] Done

#### Action 6.1.6: Run dependency analysis

**Command:** `make deps-health`

**Explanation:** Verify dependency analysis runs and outputs advice.

- [ ] Done

#### Action 6.1.7: Run E2E tests (if Docker available)

**Command:** `make test-e2e`

**Explanation:** Verify E2E tests pass, including the new `ZZ_E2EDiagnosticsTest` which asserts no runtime diagnostics issues.

- [ ] Done

#### Action 6.1.8: Validate CI workflow

**Command:** `gh act --validate`

**Explanation:** Verify CI workflow YAML is valid with the updated lint and coverage steps.

- [ ] Done

#### Action 6.1.9: Documentation review

**Explanation:** Re-read all updated documentation (PROJECT.md, CLAUDE.md) and verify consistency with implemented changes. Cross-check:
- All Makefile targets documented
- All tool versions documented
- Coverage tool references updated (no stale JaCoCo references)
- Debug diagnostics documented
- CLAUDE.md quality gates updated

- [ ] Done

#### Action 6.1.10: Final sign-off

**Explanation:** Confirm all checkboxes in this plan are marked as done. Report any discrepancies or issues encountered to the user.

- [ ] Done

---

## Execution Order Summary

```
US1 (Static Analysis) ─── T1.1 ─── T1.2 ─── T1.3 ─── T1.4 ─── T1.5
                                                                    │
US2 (Runtime Debug) ────── T2.1 ─── T2.2 ─── T2.3 ─── T2.4 ──────┤
                                                                    │
US3 (E2E Diagnostics) ──── T3.1 ─── T3.2 ─── T3.3 ────────────────┤
                                                                    │
US4 (JaCoCo→Kover) ─────── T4.1 ─── T4.2 ─── T4.3 ────────────────┤
                                                                    │
US5 (Documentation) ─────── T5.1 ─── T5.2 ─────────────────────────┤
                                                                    │
US6 (Final Validation) ──── T6.1 ───────────────────────────────────┘
```

**Dependencies:**
- US1, US2, US4 are independent of each other (can be implemented in parallel if no file conflicts)
- US3 depends on US2 (runtime diagnostics must be in debug build before E2E collection makes sense)
- US5 depends on US1, US2, US3, US4 (documentation reflects all changes)
- US6 depends on all preceding user stories

---

**End of Plan 28**
