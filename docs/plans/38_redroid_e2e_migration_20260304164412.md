<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 38: Migrate E2E Tests from budtmo/docker-android to Redroid

## Context

Replace `budtmo/docker-android:emulator_14.0` (QEMU-based) with `redroid/redroid:13.0.0-latest` (native Android in containers via kernel modules). Faster boot, lower resource usage, no QEMU overhead.

**Key constraints** (user-confirmed):
- **NO `--privileged`** — MUST use `--cap-add=ALL` + security-opt overrides (prevents machine standby)
- Android 13 image: `redroid/redroid:13.0.0-latest` (standard, no custom build)
- No `ro.setupwizard.mode=DISABLED`
- Keep Testcontainers for container orchestration
- Local development support: tests try `sudo modprobe` if modules not loaded
- No fallback to budtmo/docker-android
- CI: `sudo modprobe` works on GitHub Actions runners; install `linux-modules-extra-$(uname -r)` for `binder_linux`

**Architectural differences**:
- No nested emulator — ADB connects from host via `adb connect` to container's mapped port
- No socat/adb-forward bridge — MCP server port directly exposed on container network
- Requires kernel modules: `binder_linux` (with `devices="binder,hwbinder,vndbinder"`) and `fuse`
- Requires binderfs mount at `/dev/binderfs/` and dynamic device major number detection
- No emulated cameras — redroid lacks QEMU camera HAL; camera E2E tests must gracefully skip
- Boot detection: Testcontainers log-based wait (container startup) + host-side `adb connect` polling (ADB connectivity) — intentional dual strategy for robustness

**Accepted exception — `sudo` in test infrastructure** (CLAUDE.md Section 3 conflict):
Loading kernel modules (`binder_linux`, `fuse`) and mounting binderfs require root. This is a host-OS operation that cannot be avoided for redroid. The `ensureKernelModules()` and `ensureBinderfs()` methods in test infrastructure code use `sudo modprobe` / `sudo mount`. The CI workflow also uses `sudo`. This is an accepted exception to the no-sudo rule for test infrastructure only.

**Vestigial `container` parameter cleanup**:
After migration, most `AndroidContainerSetup` methods no longer use the container directly (all ADB calls go through host-side `execAdb()`). Methods that still need `container`: `waitForEmulatorBoot()` (for `container.host`/`getMappedPort`) and `getMcpServerUrl()` (same). All other public methods drop the `container` parameter. `SharedAndroidContainer` call sites are updated accordingly.

---

## User Story 1: CI Workflow — Kernel Module Loading

**Why**: GitHub Actions runners do not have `binder_linux` loaded by default.

**Acceptance criteria**:
- [x] CI installs `linux-modules-extra-$(uname -r)` package
- [x] CI loads `binder_linux` (with devices param) and `fuse` modules via `sudo modprobe`
- [x] CI mounts binderfs at `/dev/binderfs/` if not already mounted
- [x] CI pre-pulls `redroid/redroid:13.0.0-latest` instead of `budtmo/docker-android:emulator_14.0`
- [ ] E2E tests run successfully on CI

### Task 1.1: Update CI workflow for redroid kernel module setup

**File**: `.github/workflows/ci.yml`

**Operation**: Modify the `test-e2e` job

Replace the "Pre-pull Docker Android image" step with two new steps:

```yaml
      - name: Set up redroid kernel modules
        run: |
          # Install kernel module package (binder_linux is in linux-modules-extra)
          sudo apt-get update -qq
          sudo apt-get install -y -qq linux-modules-extra-$(uname -r)
          # Load required kernel modules
          sudo modprobe binder_linux devices="binder,hwbinder,vndbinder"
          sudo modprobe fuse
          # Mount binderfs if not already mounted
          if ! mountpoint -q /dev/binderfs; then
            sudo mkdir -p /dev/binderfs
            sudo mount -t binder binder /dev/binderfs
          fi
          # Verify
          ls -la /dev/binderfs/
          lsmod | grep -E 'binder_linux|fuse'

      - name: Pre-pull redroid image
        run: docker pull redroid/redroid:13.0.0-latest
```

**Definition of Done**:
- [x] Old `docker pull budtmo/docker-android:emulator_14.0` step removed
- [x] New kernel module setup step loads binder_linux and fuse
- [x] New pre-pull step pulls `redroid/redroid:13.0.0-latest`
- [x] Binderfs mounted at `/dev/binderfs/`

---

## User Story 2: Rewrite AndroidContainerSetup for Redroid

**Why**: Container config, boot detection, port forwarding, APK install, and all adb interactions must change — redroid has no nested emulator and no adb binary inside the container.

**Acceptance criteria**:
- [x] Container uses `redroid/redroid:13.0.0-latest` image
- [x] Container starts with `--cap-add=ALL` (no `--privileged`)
- [x] Container has correct security-opt, device-cgroup-rule, and device mounts
- [x] Boot detection polls via host-side `adb connect` + `getprop sys.boot_completed`
- [x] Port forwarding bridge (socat) removed — MCP port directly exposed
- [x] APK installation via host-side `adb install` (no container file copy)
- [x] All setup methods work: install APKs, grant permissions, configure server, enable accessibility
- [x] Local dev: kernel module auto-loading via `sudo modprobe` with clear error on failure
- [x] Vestigial `container` parameter removed from methods that no longer use it
- [x] All `ProcessBuilder.waitFor()` calls use timeouts to prevent indefinite hangs

### Task 2.1: Rewrite constants and createContainer()

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/AndroidContainerSetup.kt`

**Operation**: Modify

Remove old constants (`DOCKER_IMAGE`, `NOVNC_PORT`, `ADB_FORWARD_PORT`). Add new constants and imports (existing imports like `java.io.File`, `java.time.Duration` are retained):

```kotlin
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Capability
import com.github.dockerjava.api.model.Device
import com.github.dockerjava.api.model.Volume
import java.util.concurrent.TimeUnit

// Constants:
private const val DOCKER_IMAGE = "redroid/redroid:13.0.0-latest"
private const val ADB_PORT = 5555
private const val MCP_DEFAULT_PORT = 8080
private const val PROCESS_TIMEOUT_SECONDS = 30L
private const val MEMORY_BYTES = 4L * 1024 * 1024 * 1024 // 4 GB
```

New `createContainer()`:

```kotlin
fun createContainer(): GenericContainer<*> {
    println("[E2E Setup] Creating redroid container ($DOCKER_IMAGE)")

    ensureKernelModules()

    val binderDevMajor = detectBinderDevMajor()
    val fuseDevMajorMinor = detectFuseDevMajorMinor()

    return GenericContainer(DockerImageName.parse(DOCKER_IMAGE))
        .withExposedPorts(ADB_PORT, MCP_DEFAULT_PORT)
        .withCommand(
            "androidboot.redroid_width=1080",
            "androidboot.redroid_height=2400",
            "androidboot.redroid_gpu_mode=guest",
            "androidboot.redroid_dpi=420",
            "androidboot.use_memfd=true",
            "ro.product.model=Pixel_6",
            "ro.product.brand=google",
            "ro.product.manufacturer=Google",
            "ro.debuggable=1",
            "ro.secure=0",
        )
        .withStartupTimeout(Duration.ofSeconds(120))
        .waitingFor(
            Wait.forLogMessage(".*sys\\.boot_completed=1.*\\n", 1)
                .withStartupTimeout(Duration.ofSeconds(120))
        )
        .withCreateContainerCmdModifier { cmd ->
            cmd.hostConfig
                ?.withMemory(MEMORY_BYTES)
                ?.withMemorySwap(MEMORY_BYTES)
                ?.withCapAdd(Capability.values().toList())
                ?.withSecurityOpts(
                    listOf(
                        "seccomp=unconfined",
                        "apparmor=unconfined",
                    )
                )
                ?.withDevices(
                    listOf(
                        Device("rwm", "/dev/fuse", "/dev/fuse"),
                    )
                )
                ?.withDeviceCgroupRules(
                    listOf(
                        "c $binderDevMajor:* rwm",
                        "c $fuseDevMajorMinor rwm",
                    )
                )
                ?.withBinds(
                    Bind("/sys/fs/cgroup", Volume("/sys/fs/cgroup")),
                )
        }
}
```

Note: The Testcontainers log-based wait strategy (`Wait.forLogMessage`) ensures the container process is up. The subsequent `waitForEmulatorBoot()` polling ensures host-side ADB connectivity. This intentional dual strategy is robust: the log wait prevents premature access, the polling wait confirms end-to-end ADB reachability.

### Task 2.2: Add kernel module and device detection helpers

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/AndroidContainerSetup.kt`

**Operation**: Modify — add new private methods

All `ProcessBuilder.waitFor()` calls use `waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)` with `destroyForcibly()` on timeout.

```kotlin
/**
 * Ensures binder_linux and fuse kernel modules are loaded.
 *
 * On CI, modules are pre-loaded by the workflow. On local dev, attempts
 * `sudo modprobe` and provides a clear error on failure.
 *
 * NOTE: Uses `sudo` — accepted exception to CLAUDE.md no-sudo rule.
 * Kernel module loading is a host-OS operation required by redroid.
 */
private fun ensureKernelModules() {
    if (isModuleLoaded("binder_linux") && isModuleLoaded("fuse")) {
        println("[E2E Setup] Kernel modules already loaded")
        return
    }

    println("[E2E Setup] Loading kernel modules via sudo modprobe...")
    try {
        runProcess("sudo", "modprobe", "binder_linux", "devices=binder,hwbinder,vndbinder",
            timeoutSeconds = 60L)
        runProcess("sudo", "modprobe", "fuse", timeoutSeconds = 60L)
        ensureBinderfs()
        println("[E2E Setup] Kernel modules loaded successfully")
    } catch (e: Exception) {
        throw IllegalStateException(
            "Failed to load kernel modules required by redroid. " +
                "On Linux, ensure 'binder_linux' and 'fuse' modules are available. " +
                "Install with: sudo apt-get install linux-modules-extra-\$(uname -r) && " +
                "sudo modprobe binder_linux devices=\"binder,hwbinder,vndbinder\" && " +
                "sudo modprobe fuse. Error: ${e.message}",
            e,
        )
    }
}

/**
 * Mounts binderfs at /dev/binderfs/ if not already mounted.
 * NOTE: Uses `sudo` — same accepted exception as ensureKernelModules.
 */
private fun ensureBinderfs() {
    try {
        runProcess("mountpoint", "-q", "/dev/binderfs")
        return // Already mounted
    } catch (_: Exception) {
        // Not mounted, proceed
    }

    println("[E2E Setup] Mounting binderfs at /dev/binderfs/...")
    runProcess("sudo", "mkdir", "-p", "/dev/binderfs")
    runProcess("sudo", "mount", "-t", "binder", "binder", "/dev/binderfs")
}

private fun isModuleLoaded(moduleName: String): Boolean =
    try {
        File("/proc/modules").readText().contains(moduleName)
    } catch (_: Exception) {
        false
    }

private fun detectBinderDevMajor(): String =
    try {
        val output = runProcess("stat", "-c", "%Hr", "/dev/binderfs/binder-control")
        if (output.isNotEmpty()) {
            println("[E2E Setup] Binder device major: $output")
            output
        } else {
            println("[E2E Setup] Could not detect binder device major, using default 10")
            "10"
        }
    } catch (e: Exception) {
        println("[E2E Setup] Binder device major detection failed: ${e.message}, using default 10")
        "10"
    }

private fun detectFuseDevMajorMinor(): String =
    try {
        val content = File("/sys/class/misc/fuse/dev").readText().trim()
        if (content.isNotEmpty()) {
            println("[E2E Setup] Fuse device major:minor: $content")
            content
        } else {
            println("[E2E Setup] Could not detect fuse device, using default 10:229")
            "10:229"
        }
    } catch (e: Exception) {
        println("[E2E Setup] Fuse device detection failed: ${e.message}, using default 10:229")
        "10:229"
    }

/**
 * Runs a process with a timeout. Returns trimmed stdout on success.
 * Destroys the process forcibly on timeout.
 *
 * Stdout is read in a separate thread to avoid deadlock: if the process
 * hangs without closing stdout (e.g., `sudo` waiting for password),
 * `readText()` would block forever, preventing `waitFor()` from firing.
 * By reading in a thread, `waitFor()` can time out and `destroyForcibly()`.
 */
private fun runProcess(
    vararg command: String,
    timeoutSeconds: Long = PROCESS_TIMEOUT_SECONDS,
): String {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val outputBuilder = StringBuilder()
    val readerThread = Thread {
        try {
            outputBuilder.append(process.inputStream.bufferedReader().readText())
        } catch (_: Exception) { }
    }.apply { isDaemon = true; start() }

    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        readerThread.join(1_000)
        throw IllegalStateException(
            "Process timed out after ${timeoutSeconds}s: ${command.joinToString(" ")}"
        )
    }
    readerThread.join(5_000)
    val output = outputBuilder.toString().trim()

    if (process.exitValue() != 0) {
        throw IllegalStateException(
            "Process '${command.joinToString(" ")}' failed (exit ${process.exitValue()}): $output"
        )
    }
    return output
}
```

### Task 2.3: Add ADB serial state and rewrite boot detection

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/AndroidContainerSetup.kt`

**Operation**: Modify

Add ADB serial state at class level:

```kotlin
@Volatile
private var _adbSerial: String? = null

val adbSerial: String
    get() = _adbSerial ?: error("Container not booted yet — call waitForEmulatorBoot first")
```

Replace `waitForEmulatorBoot()`:

```kotlin
/**
 * Wait for the redroid container to boot and establish host-side ADB connection.
 *
 * Redroid has no adb binary inside the container. We connect from the host
 * via `adb connect` to the container's mapped ADB port, then poll
 * `getprop sys.boot_completed`.
 */
fun waitForEmulatorBoot(
    container: GenericContainer<*>,
    timeoutMs: Long = DEFAULT_EMULATOR_BOOT_TIMEOUT_MS,
) {
    val host = container.host
    val adbPort = container.getMappedPort(ADB_PORT)
    val serial = "$host:$adbPort"

    println("[E2E Setup] Waiting for redroid boot via adb connect $serial (timeout: ${timeoutMs}ms)...")
    val startTime = System.currentTimeMillis()

    // Phase 1: wait for adb connect to succeed
    while (System.currentTimeMillis() - startTime < timeoutMs) {
        try {
            val output = runProcess("adb", "connect", serial, timeoutSeconds = 10L)
            if (output.contains("connected")) break
        } catch (_: Exception) { }
        Thread.sleep(POLL_INTERVAL_MS)
    }

    // Phase 2: poll sys.boot_completed
    while (System.currentTimeMillis() - startTime < timeoutMs) {
        try {
            val output = runProcess(
                "adb", "-s", serial, "shell", "getprop", "sys.boot_completed",
                timeoutSeconds = 10L,
            )
            if (output == "1") {
                println("[E2E Setup] Redroid boot completed (${System.currentTimeMillis() - startTime}ms)")
                _adbSerial = serial
                return
            }
        } catch (_: Exception) { }
        Thread.sleep(POLL_INTERVAL_MS)
    }

    throw IllegalStateException("Redroid did not boot within ${timeoutMs}ms. ADB serial: $serial")
}
```

### Task 2.4: Add execAdb helper, delete setupPortForwarding and execInContainer

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/AndroidContainerSetup.kt`

**Operation**: Modify

1. **Delete** `setupPortForwarding()` entirely.
2. **Delete** the private `execInContainer()` helper.
3. **Add** `execAdb()` helper with timeout:

```kotlin
/**
 * Execute an adb command on the host targeting the redroid container.
 * Returns stdout on success. Uses [PROCESS_TIMEOUT_SECONDS] timeout.
 */
private fun execAdb(vararg args: String): String {
    val command = arrayOf("adb", "-s", adbSerial) + args
    return runProcess(*command)
}
```

Note: `getMcpServerUrl(container)` is **retained unchanged** — it still takes `container` (needs `container.host`/`getMappedPort`). The methods listed in Tasks 2.5–2.9 provide the full replacement code for each method that previously used `execInContainer`.

### Task 2.5: Rewrite APK installation methods (drop container param)

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/AndroidContainerSetup.kt`

**Operation**: Modify

```kotlin
fun installApk(apkPath: String) {
    val apkFile = File(apkPath)
    require(apkFile.exists()) { "APK file not found: $apkPath" }
    println("[E2E Setup] Installing APK: $apkPath")
    val result = execAdb("install", "-r", apkPath)
    if (!result.contains("Success")) {
        throw IllegalStateException("APK installation failed: $result")
    }
    println("[E2E Setup] APK installed successfully")
}

fun installCalculatorApk() {
    println("[E2E Setup] Installing calculator APK for testing...")
    val resourceStream = AndroidContainerSetup::class.java.getResourceAsStream(CALCULATOR_APK_RESOURCE)
        ?: throw IllegalStateException("Calculator APK not found in test resources: $CALCULATOR_APK_RESOURCE")
    val tempFile = File.createTempFile("calculator", ".apk")
    tempFile.deleteOnExit()
    resourceStream.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
    val result = execAdb("install", "-r", tempFile.absolutePath)
    if (!result.contains("Success")) {
        throw IllegalStateException("Calculator APK installation failed: $result")
    }
    println("[E2E Setup] Calculator APK installed successfully")
}
```

### Task 2.6: Rewrite permissions and accessibility methods (drop container param)

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/AndroidContainerSetup.kt`

**Operation**: Modify

```kotlin
fun grantCameraPermissions() {
    println("[E2E Setup] Granting camera and microphone permissions...")
    execAdb("shell", "pm", "grant", APP_PACKAGE, "android.permission.CAMERA")
    execAdb("shell", "pm", "grant", APP_PACKAGE, "android.permission.RECORD_AUDIO")
    println("[E2E Setup] Camera and microphone permissions granted")
}

fun enableAccessibilityService(timeoutMs: Long = 30_000L) {
    println("[E2E Setup] Enabling accessibility service...")
    val serviceComponent = "$APP_PACKAGE/$ACCESSIBILITY_SERVICE_CLASS"

    execAdb("shell", "settings", "put", "secure", "enabled_accessibility_services", serviceComponent)
    execAdb("shell", "settings", "put", "secure", "accessibility_enabled", "1")

    println("[E2E Setup] Waiting for accessibility service to connect (timeout: ${timeoutMs}ms)...")
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < timeoutMs) {
        try {
            val dumpsys = execAdb("shell", "dumpsys", "accessibility")
            if (dumpsys.contains("McpAccessibilityService") && dumpsys.contains("Service")) {
                println("[E2E Setup] Accessibility service connected (${System.currentTimeMillis() - startTime}ms)")
                return
            }
        } catch (_: Exception) { }
        Thread.sleep(POLL_INTERVAL_MS)
    }

    throw IllegalStateException(
        "Accessibility service did not connect within ${timeoutMs}ms. Component: $serviceComponent"
    )
}

fun isAccessibilityServiceConnected(): Boolean =
    try {
        val dumpsys = execAdb("shell", "dumpsys", "accessibility")
        dumpsys.contains("McpAccessibilityService") && dumpsys.contains("Service")
    } catch (_: Exception) {
        false
    }

fun ensureAccessibilityService() {
    if (isAccessibilityServiceConnected()) return
    println("[E2E Setup] Accessibility service not connected, re-enabling...")
    enableAccessibilityService()
}
```

### Task 2.7: Rewrite server config and start methods (drop container param)

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/AndroidContainerSetup.kt`

**Operation**: Modify

```kotlin
fun configureServerSettings() {
    println("[E2E Setup] Configuring MCP server settings...")

    execAdb("shell", "am", "start", "-n", "$APP_PACKAGE/$MAIN_ACTIVITY_CLASS")
    Thread.sleep(5_000)

    execAdb("shell", "am", "force-stop", APP_PACKAGE)
    Thread.sleep(1_000)

    val configAction = "$APP_PACKAGE.E2E_CONFIGURE"
    execAdb(
        "shell", "am", "broadcast",
        "--include-stopped-packages",
        "-a", configAction,
        "-n", "$APP_PACKAGE/$E2E_CONFIG_RECEIVER_CLASS",
        "--es", "bearer_token", E2E_BEARER_TOKEN,
        "--es", "binding_address", "0.0.0.0",
        "--ei", "port", MCP_DEFAULT_PORT.toString(),
    )
    Thread.sleep(3_000)

    println("[E2E Setup] Server settings configured")
}

fun startMcpServer() {
    println("[E2E Setup] Starting MCP server...")

    execAdb("shell", "am", "start", "-n", "$APP_PACKAGE/$MAIN_ACTIVITY_CLASS")
    Thread.sleep(5_000)

    val startServerAction = "$APP_PACKAGE.E2E_START_SERVER"
    execAdb(
        "shell", "am", "broadcast",
        "-a", startServerAction,
        "-n", "$APP_PACKAGE/$E2E_CONFIG_RECEIVER_CLASS",
    )
    Thread.sleep(5_000)

    println("[E2E Setup] MCP server start commands sent")
}

fun launchCalculator() {
    execAdb("shell", "monkey", "-p", CALCULATOR_PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
    Thread.sleep(2_000)
}
```

### Task 2.8: Rewrite waitForServerReady and dumpDiagnostics (drop container param from dumpDiagnostics)

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/AndroidContainerSetup.kt`

**Operation**: Modify

`waitForServerReady` drops `container` param — the caller passes `baseUrl` already computed from `getMcpServerUrl(c)`, and `dumpDiagnostics` now uses `execAdb()` instead of `container.execInContainer()`. Remove `container` from both:

```kotlin
fun waitForServerReady(
    baseUrl: String,
    timeoutMs: Long = DEFAULT_SERVER_READY_TIMEOUT_MS,
) {
    println("[E2E Setup] Waiting for MCP server to be ready at $baseUrl (timeout: ${timeoutMs}ms)...")

    val startTime = System.currentTimeMillis()
    var lastError: String? = null

    while (System.currentTimeMillis() - startTime < timeoutMs) {
        var conn: java.net.HttpURLConnection? = null
        try {
            conn = java.net.URI("$baseUrl/mcp").toURL()
                .openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 2_000
            conn.readTimeout = 2_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $E2E_BEARER_TOKEN")
            conn.doOutput = true
            conn.outputStream.use {
                it.write("""{"jsonrpc":"2.0","method":"ping","id":1}""".toByteArray())
            }
            val responseCode = conn.responseCode
            if (responseCode > 0) {
                println(
                    "[E2E Setup] MCP server is ready " +
                        "(HTTP $responseCode, ${System.currentTimeMillis() - startTime}ms)"
                )
                return
            }
        } catch (e: Exception) {
            val errorMsg = "${e.javaClass.simpleName}: ${e.message}"
            if (errorMsg != lastError) {
                println("[E2E Setup] Server readiness poll: $errorMsg")
                lastError = errorMsg
            }
        } finally {
            conn?.disconnect()
        }
        Thread.sleep(SERVER_READY_POLL_INTERVAL_MS)
    }

    dumpDiagnostics()

    throw IllegalStateException(
        "MCP server did not become ready within ${timeoutMs}ms at $baseUrl"
    )
}

private fun dumpDiagnostics() {
    System.err.println("[E2E Diagnostics] === Server readiness timeout — dumping diagnostics ===")

    try {
        val ss = execAdb("shell", "ss", "-tlnp")
        System.err.println("[E2E Diagnostics] LISTEN ports: $ss")
    } catch (e: Exception) {
        System.err.println("[E2E Diagnostics] ss failed: ${e.message}")
    }

    try {
        val logcat = execAdb("shell", "logcat", "-d", "-t", "50")
        val filtered = logcat.lines()
            .filter {
                it.contains("MCP", ignoreCase = true) ||
                    it.contains("E2E", ignoreCase = true) ||
                    it.contains("FATAL", ignoreCase = true)
            }
            .takeLast(20)
            .joinToString("\n")
        System.err.println("[E2E Diagnostics] logcat (MCP/E2E): $filtered")
    } catch (e: Exception) {
        System.err.println("[E2E Diagnostics] logcat failed: ${e.message}")
    }

    System.err.println("[E2E Diagnostics] === End diagnostics dump ===")
}
```

### Task 2.9: Add ADB disconnection cleanup

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/AndroidContainerSetup.kt`

**Operation**: Modify — add a cleanup method

```kotlin
fun disconnectAdb() {
    val serial = _adbSerial ?: return
    try {
        runProcess("adb", "disconnect", serial, timeoutSeconds = 10L)
        println("[E2E Setup] Disconnected adb from $serial")
    } catch (_: Exception) {
        // Best-effort cleanup
    }
    _adbSerial = null
}
```

**Definition of Done (Task 2.1–2.9)**:
- [x] `createContainer()` uses `redroid/redroid:13.0.0-latest` with `--cap-add=ALL` and proper security-opts
- [x] Kernel module loading + binderfs mount works on local dev and CI
- [x] Boot detection uses host-side `adb connect` + `getprop` polling with timeouts
- [x] `setupPortForwarding()` and `execInContainer()` deleted
- [x] All adb commands execute from the host via `execAdb()` → `runProcess()` with timeouts
- [x] APK installation uses direct `adb install` (no container file copy)
- [x] `disconnectAdb()` cleans up adb connection on shutdown
- [x] Vestigial `container` parameter removed from methods that don't use it
- [x] `ensureAccessibilityService()` updated with new signature (no container param)

---

## User Story 3: Update SharedAndroidContainer

**Why**: The initialization sequence must remove `setupPortForwarding()`, update call sites for methods that dropped the `container` parameter, and add adb cleanup on shutdown.

**Acceptance criteria**:
- [ ] `setupPortForwarding()` call removed from initialization sequence
- [ ] All call sites updated to match new method signatures (no `container` param)
- [ ] `disconnectAdb()` called in JVM shutdown hook before stopping the container
- [ ] `ensureAccessibilityService()` call updated (no `container` param)

### Task 3.1: Update SharedAndroidContainer initialization and shutdown

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/SharedAndroidContainer.kt`

**Operation**: Modify

In `ensureInitialized()`, remove `setupPortForwarding()` and update method calls:

```kotlin
// Old initialization sequence (change lines):
// AndroidContainerSetup.setupPortForwarding(c)     — DELETE
// AndroidContainerSetup.installApk(c, apkPath)     → AndroidContainerSetup.installApk(apkPath)
// AndroidContainerSetup.installCalculatorApk(c)    → AndroidContainerSetup.installCalculatorApk()
// AndroidContainerSetup.grantCameraPermissions(c)  → AndroidContainerSetup.grantCameraPermissions()
// AndroidContainerSetup.configureServerSettings(c) → AndroidContainerSetup.configureServerSettings()
// AndroidContainerSetup.startMcpServer(c)          → AndroidContainerSetup.startMcpServer()
// AndroidContainerSetup.waitForServerReady(c, url) → AndroidContainerSetup.waitForServerReady(url)
// AndroidContainerSetup.enableAccessibilityService(c) → AndroidContainerSetup.enableAccessibilityService()
```

New initialization sequence:
1. `createContainer()` + `start()`
2. `waitForEmulatorBoot(c)` — still takes container (needs host/port)
3. `installApk(apkPath)`
4. `installCalculatorApk()`
5. `grantCameraPermissions()`
6. `configureServerSettings()`
7. `startMcpServer()`
8. `waitForServerReady(url)`
9. `enableAccessibilityService()`
10. Create and connect `McpClient`

Update `ensureAccessibilityService()`:

```kotlin
fun ensureAccessibilityService() {
    ensureInitialized()
    AndroidContainerSetup.ensureAccessibilityService()
}
```

Remove the public `container` property — after Task 4.2 removes the only consumer (`E2ECalculatorTest.container`), it becomes dead code:

```kotlin
// DELETE these lines (the public container getter):
//    val container: GenericContainer<*>
//        get() {
//            ensureInitialized()
//            return _container!!
//        }
```

Update JVM shutdown hook:

```kotlin
Runtime.getRuntime().addShutdownHook(Thread {
    _container?.let { c ->
        println("[SharedAndroidContainer] Stopping shared container...")
        AndroidContainerSetup.disconnectAdb()
        if (c.isRunning) {
            c.stop()
        }
        println("[SharedAndroidContainer] Shared container stopped")
    }
})
```

**Definition of Done**:
- [ ] `setupPortForwarding()` call removed
- [ ] All method calls updated to new signatures (no container param)
- [ ] `ensureAccessibilityService()` uses new no-arg signature
- [ ] `disconnectAdb()` called before container stop in shutdown hook
- [ ] Dead `container` public property removed

---

## User Story 4: Handle Camera Tests and Verify E2E Assertions

**Why**: Redroid lacks QEMU camera emulation. Camera E2E tests (16 tests) will fail unless gracefully skipped. Non-camera test assertions must be verified for Android 13 compatibility.

**Acceptance criteria**:
- [ ] Camera tests gracefully skip (not fail) when no cameras available
- [ ] All non-camera E2E tests pass on Android 13 redroid
- [ ] Tool count assertion verified (app-defined, not OS-dependent)

### Task 4.1: Add camera availability guard to E2ECameraTest

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/E2ECameraTest.kt`

**Operation**: Modify

Add import:
```kotlin
import org.junit.jupiter.api.Assumptions
```

Update the KDoc class comment to note redroid camera limitation.

In the first test (`@Order(1)` — `list_cameras returns at least one camera with correct fields`), replace `assertTrue(cameras.size >= 1, ...)` with:

```kotlin
Assumptions.assumeTrue(
    cameras.isNotEmpty(),
    "Skipping camera tests: redroid container does not have emulated cameras",
)
```

In the `@Order(2)` test (`list_cameras returns cameras with correct facing values`), add an `Assumptions.assumeTrue` guard before the facings assertion. After parsing `cameras`, add:

```kotlin
Assumptions.assumeTrue(
    cameras.isNotEmpty(),
    "Skipping: redroid container does not have emulated cameras",
)
```

In the `requireCameraId()` helper, replace `assertTrue(cameras.isNotEmpty(), ...)` with:

```kotlin
Assumptions.assumeTrue(
    cameras.isNotEmpty(),
    "Skipping: no cameras available in redroid container",
)
```

This causes all 16 camera tests to be reported as **skipped** (not failed) when cameras are unavailable. JUnit 5 `Assumptions.assumeTrue` throws `TestAbortedException` which marks the test as skipped.

### Task 4.2: Update E2ECalculatorTest for new signatures

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/E2ECalculatorTest.kt`

**Operation**: Modify

1. **Remove** the unused `container` property (line 43) — it is only used in `launchCalculator(container)` which drops the param:

```kotlin
// DELETE this line:
// private val container = SharedAndroidContainer.container
```

2. **Update** `launchCalculator` call site (line 108):

```kotlin
// Old: AndroidContainerSetup.launchCalculator(container)
// New:
AndroidContainerSetup.launchCalculator()
```

3. **Verify** tool count assertion (`result.tools.size >= 27`) — app-defined, not OS-dependent. Verify after implementation.

**Definition of Done**:
- [ ] Camera tests use `Assumptions.assumeTrue` — skip gracefully on redroid
- [ ] `requireCameraId()` uses `Assumptions.assumeTrue` — skip gracefully on redroid
- [ ] Non-camera E2E tests pass on Android 13 redroid
- [ ] `launchCalculator()` call updated to new signature
- [ ] Unused `container` property removed from E2ECalculatorTest

---

## User Story 5: Update Documentation References

**Why**: Multiple project documentation files reference `budtmo/docker-android`. After migration, these become stale and misleading. The `plan-reviewer.md` agent config specifically mandates `budtmo/docker-android-x86` — it will incorrectly flag future plans.

**Acceptance criteria**:
- [ ] All `budtmo/docker-android` references updated to `redroid/redroid:13.0.0-latest`
- [ ] Documentation accurately describes kernel module requirements
- [ ] Agent config (`plan-reviewer.md`) updated to reference redroid

### Task 5.1: Update docs/PROJECT.md

**File**: `docs/PROJECT.md`

**Operation**: Modify line 517

```
# Old:
- **Framework**: Testcontainers Kotlin (`budtmo/docker-android-x86:emulator_14.0`), JUnit 5, MCP Kotlin SDK Client

# New:
- **Framework**: Testcontainers Kotlin (`redroid/redroid:13.0.0-latest`), JUnit 5, MCP Kotlin SDK Client
```

### Task 5.2: Update CLAUDE.md

**File**: `CLAUDE.md`

**Operation**: Modify lines 512 and 561

```
# Line 512 old:
- Use **budtmo/docker-android-x86** Docker image (Android emulator in container).
# Line 512 new:
- Use **redroid/redroid:13.0.0-latest** Docker image (native Android in container via kernel modules).

# Line 561 old:
- **Docker**: Required for E2E tests (budtmo/docker-android-x86 image).
# Line 561 new:
- **Docker**: Required for E2E tests (redroid/redroid image). Requires `binder_linux` and `fuse` kernel modules.
```

### Task 5.3: Update README.md

**File**: `README.md`

**Operation**: Modify line 68

```
# Old:
- **Docker** (for `budtmo/docker-android-x86` emulator image)
# New:
- **Docker** (for `redroid/redroid` Android container image)
```

### Task 5.4: Update .claude/agents/plan-reviewer.md

**File**: `.claude/agents/plan-reviewer.md`

**Operation**: Modify line 83

```
# Old:
- You MUST verify: E2E tests use Testcontainers Kotlin with `budtmo/docker-android-x86` — NOT pre-running emulators or Docker Compose services.
# New:
- You MUST verify: E2E tests use Testcontainers Kotlin with `redroid/redroid` — NOT pre-running emulators or Docker Compose services.
```

**Definition of Done**:
- [ ] `docs/PROJECT.md` references redroid
- [ ] `CLAUDE.md` references redroid with kernel module note
- [ ] `README.md` references redroid
- [ ] `.claude/agents/plan-reviewer.md` references redroid

---

## User Story 6: Quality Gates

**Acceptance criteria**:
- [ ] `make lint` passes with zero warnings/errors
- [ ] `./gradlew :app:test` passes (unit + integration tests unaffected)
- [ ] `./gradlew :e2e-tests:test` passes locally with redroid
- [ ] CI pipeline passes end-to-end

### Task 6.1: Run linting

Run `make lint` and fix any issues.

### Task 6.2: Run unit and integration tests

Run `./gradlew :app:test` and verify no regressions.

### Task 6.3: Run E2E tests locally

Run `make test-e2e` and verify all non-camera tests pass with redroid. Camera tests should be skipped.

### Task 6.4: Verify CI pipeline

Push changes and verify the CI pipeline passes.

### Task 6.5: Code review

Spawn `code-reviewer` subagent in plan compliance mode.

**Definition of Done**:
- [ ] All quality gates pass
- [ ] Code reviewer reports no issues (or all issues addressed)

---

## Test Impact Summary

No new test files needed. Existing E2E tests exercise the new container setup implicitly:

| Test Class | Tests | Expected Impact |
|---|---|---|
| `E2ECalculatorTest` | 3 | Tool listing, calculator interaction, screenshot — pass unchanged |
| `E2EErrorHandlingTest` | 5 | Auth, invalid params, unknown tool — pass unchanged |
| `E2EScreenshotTest` | 1 | Screenshot capture — pass unchanged |
| `E2ECameraTest` | 16 | Camera operations — **skipped** via `Assumptions.assumeTrue` (no QEMU camera in redroid) |

Unit and integration tests (`./gradlew :app:test`) are unaffected — no Docker containers.

**Infrastructure helper methods** (`ensureKernelModules`, `ensureBinderfs`, `detectBinderDevMajor`, `detectFuseDevMajorMinor`, `runProcess`, `execAdb`, `disconnectAdb`): These are host-OS shell wrappers in test infrastructure. Unit testing them with mocks provides minimal value since they wrap `ProcessBuilder` and filesystem reads. Their correctness is verified implicitly by E2E tests passing (if modules fail to load or adb fails to connect, all E2E tests fail immediately with clear error messages).

**Local dev kernel module auto-loading** (`ensureKernelModules` with `sudo modprobe`): Not testable in CI since CI pre-loads modules. Verified manually during local development.
