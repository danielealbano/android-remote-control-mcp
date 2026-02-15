# Plan 17: Fix cloudflared Binary Execution — Move from Assets to jniLibs

**Created:** 2026-02-15
**Priority:** Critical (blocks Cloudflare remote access on real devices)
**Branch:** `fix/cloudflared-binary-assets-extraction`

---

## Context

### Problem

When users enable Cloudflare remote access on a real Android device, the cloudflared process fails to start with **EACCES (errno 13 — Permission denied)**.

The current implementation (`AndroidCloudflareBinaryResolver`) extracts the cloudflared binary from APK **assets** to `context.filesDir` at runtime, then calls `setExecutable(true, true)`. This fails because modern Android devices mount the app's data directory (`/data/data/<pkg>/files/`) with the `noexec` flag. The kernel refuses to execute any binary from that partition regardless of file permissions.

### History

- **Plan 13** originally placed cloudflared in `jniLibs/` as `libcloudflared.so` with `useLegacyPackaging = true` — this worked.
- **Plan 14** migrated from `jniLibs/` to `assets/` to set `useLegacyPackaging = false` for 16KB page alignment compliance. This broke execution on real devices because the extracted file cannot be executed from the data partition.

### Fix

Disguise the cloudflared executable as a native library (`libcloudflared.so`), place it in `jniLibs/<abi>/`, set `useLegacyPackaging = true`, and resolve the binary at runtime from `context.applicationInfo.nativeLibraryDir`. The native library directory (`/data/app/.../lib/<abi>/`) is on a partition that allows execution, so the binary can be launched via `ProcessBuilder`.

### Trade-offs

- **`useLegacyPackaging = true`**: May be deprecated in a future Android version, but is the pragmatic stop-gap. The binary itself is already compiled with 16KB page alignment linker flags (`-extldflags=-Wl,-z,max-page-size=16384`).
- **ngrok `.so`**: ngrok's `libngrokjava.so` already lives in `jniLibs/arm64-v8a/`. With `useLegacyPackaging = true`, it will be extracted at install time instead of mmap'd from the APK. This is transparent to JNI loading and works correctly.
- **APK install size**: `.so` files are extracted to disk at install time, roughly doubling disk usage for native libraries. Acceptable for this use case.

---

## User Story 1: Fix cloudflared Binary Execution on Real Devices

**As a** user
**I want** Cloudflare remote access to work on my Android device
**So that** I can expose my MCP server via a public `*.trycloudflare.com` URL

### Acceptance Criteria

- [x] cloudflared binary is packaged as `libcloudflared.so` in `jniLibs/<abi>/` (not in assets)
- [x] `useLegacyPackaging = true` is set in `app/build.gradle.kts`
- [x] `AndroidCloudflareBinaryResolver` resolves the binary from `nativeLibraryDir` (no extraction, no asset listing)
- [x] Unit tests for `AndroidCloudflareBinaryResolver` are rewritten to match the new implementation
- [x] Makefile `compile-cloudflared` target outputs to `jniLibs/<abi>/libcloudflared.so`
- [x] `.gitignore` updated (remove cloudflared assets entry, jniLibs entry already covers the new location)
- [x] `docs/PROJECT.md` reflects the new approach accurately
- [x] Lint passes (`make lint`)
- [x] All unit tests pass (`make test-unit`)
- [x] Build succeeds (`make build`)

---

### Task 1.1: Update Makefile `compile-cloudflared` target

Change the `compile-cloudflared` target to output binaries as `libcloudflared.so` into `jniLibs/<abi>/` directories instead of `assets/`.

**Actions:**

#### Action 1.1.1: Change output variable and paths in Makefile

**File:** `Makefile` (lines 265-293)

Change the variable name and both output paths:

```diff
 CLOUDFLARED_SRC_DIR := vendor/cloudflared
-CLOUDFLARED_ASSETS_DIR := app/src/main/assets
+CLOUDFLARED_JNILIBS_DIR := app/src/main/jniLibs

 compile-cloudflared: ## Cross-compile cloudflared for Android (requires Go + Android NDK)
 	@if [ ! -f "$(CLOUDFLARED_SRC_DIR)/cmd/cloudflared/main.go" ]; then \
 		echo "ERROR: cloudflared submodule not initialized."; \
 		echo "Run: git submodule update --init vendor/cloudflared"; \
 		exit 1; \
 	fi
 	@echo "Compiling cloudflared from submodule ($(CLOUDFLARED_SRC_DIR))..."
 	@echo ""
 	@echo "Compiling cloudflared for arm64-v8a..."
-	mkdir -p $(CLOUDFLARED_ASSETS_DIR)
+	mkdir -p $(CLOUDFLARED_JNILIBS_DIR)/arm64-v8a
 	cd $(CLOUDFLARED_SRC_DIR) && \
 		CGO_ENABLED=1 GOOS=android GOARCH=arm64 \
 		CC=$$(find $$ANDROID_HOME/ndk -name "aarch64-linux-android*-clang" | sort -V | tail -1) \
 		go build -a -installsuffix cgo -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" \
-		-o $(CURDIR)/$(CLOUDFLARED_ASSETS_DIR)/cloudflared-arm64-v8a \
+		-o $(CURDIR)/$(CLOUDFLARED_JNILIBS_DIR)/arm64-v8a/libcloudflared.so \
 		./cmd/cloudflared
 	@echo ""
 	@echo "Compiling cloudflared for x86_64..."
+	mkdir -p $(CLOUDFLARED_JNILIBS_DIR)/x86_64
 	cd $(CLOUDFLARED_SRC_DIR) && \
 		CGO_ENABLED=1 GOOS=android GOARCH=amd64 \
 		CC=$$(find $$ANDROID_HOME/ndk -name "x86_64-linux-android*-clang" | sort -V | tail -1) \
 		go build -a -installsuffix cgo -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" \
-		-o $(CURDIR)/$(CLOUDFLARED_ASSETS_DIR)/cloudflared-x86_64 \
+		-o $(CURDIR)/$(CLOUDFLARED_JNILIBS_DIR)/x86_64/libcloudflared.so \
 		./cmd/cloudflared
 	@echo ""
 	@echo "cloudflared compiled successfully for arm64-v8a and x86_64"
```

**Definition of Done:**
- [x] `CLOUDFLARED_ASSETS_DIR` variable is replaced with `CLOUDFLARED_JNILIBS_DIR := app/src/main/jniLibs`
- [x] arm64-v8a output path is `$(CURDIR)/$(CLOUDFLARED_JNILIBS_DIR)/arm64-v8a/libcloudflared.so`
- [x] x86_64 output path is `$(CURDIR)/$(CLOUDFLARED_JNILIBS_DIR)/x86_64/libcloudflared.so`
- [x] `mkdir -p` creates each ABI subdirectory before compilation
- [x] Section comment remains "Native Binary Compilation (cloudflared)"
- [x] Trailing echo message remains unchanged

---

### Task 1.2: Update `app/build.gradle.kts` — flip `useLegacyPackaging` to `true`

**Actions:**

#### Action 1.2.1: Change `useLegacyPackaging` from `false` to `true`

**File:** `app/build.gradle.kts` (line 89)

```diff
     jniLibs {
-        useLegacyPackaging = false
+        useLegacyPackaging = true
     }
```

This ensures that `.so` files (including `libcloudflared.so` and `libngrokjava.so`) are extracted from the APK to the app's native library directory at install time. The native library directory has execute permissions, allowing `ProcessBuilder` to execute `libcloudflared.so`.

**Definition of Done:**
- [x] Line 89 reads `useLegacyPackaging = true`
- [x] No other changes to `app/build.gradle.kts`

---

### Task 1.3: Rewrite `AndroidCloudflareBinaryResolver` — resolve from `nativeLibraryDir`

Replace the entire asset extraction logic with a simple lookup in `context.applicationInfo.nativeLibraryDir`.

**Actions:**

#### Action 1.3.1: Rewrite `AndroidCloudflareBinaryResolver.kt`

**File:** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/AndroidCloudflareBinaryResolver.kt`

Replace the **entire file contents** with:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Resolves the cloudflared binary from the app's native library directory.
 *
 * The binary is packaged as `libcloudflared.so` in `jniLibs/<abi>/` and extracted
 * by the Android package manager to `nativeLibraryDir` at install time (requires
 * `useLegacyPackaging = true`). The native library directory has execute permissions,
 * allowing the binary to be run as a child process via `ProcessBuilder`.
 */
class AndroidCloudflareBinaryResolver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : CloudflaredBinaryResolver {
        override fun resolve(): String? {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val binaryFile = File(nativeLibDir, LIBRARY_NAME)

            if (!binaryFile.exists()) {
                Log.e(TAG, "cloudflared binary not found at: ${binaryFile.absolutePath}")
                return null
            }

            if (!binaryFile.canExecute()) {
                Log.e(TAG, "cloudflared binary is not executable: ${binaryFile.absolutePath}")
                return null
            }

            return binaryFile.absolutePath
        }

        companion object {
            private const val TAG = "MCP:CloudflaredResolver"
            internal const val LIBRARY_NAME = "libcloudflared.so"
        }
    }
```

Key changes from the current implementation:
- **Removed**: `android.os.Build` import, `java.io.IOException` import, `AssetManager` usage
- **Removed**: `supportedAbis` field (no longer needed — Android's package manager handles ABI selection when extracting from `jniLibs/`)
- **Removed**: `resolveAssetName()` method (no asset listing)
- **Removed**: `extractAsset()` method (no extraction, no `setExecutable`)
- **Removed**: `ASSET_PREFIX` and `EXTRACTED_BINARY_NAME` constants
- **Added**: `LIBRARY_NAME = "libcloudflared.so"` constant
- **Added**: Existence check (`binaryFile.exists()`)
- **Added**: Execute permission check (`binaryFile.canExecute()`)
- **Logic**: Simply constructs the path `nativeLibraryDir/libcloudflared.so`, verifies it exists and is executable, returns the absolute path or null

**Definition of Done:**
- [x] File contains only the new implementation (no asset extraction logic remains)
- [x] `resolve()` returns `context.applicationInfo.nativeLibraryDir + "/libcloudflared.so"` when the file exists and is executable
- [x] `resolve()` returns `null` with a log error when the file does not exist
- [x] `resolve()` returns `null` with a log error when the file is not executable
- [x] No `supportedAbis` field (ABI selection is handled by Android package manager)
- [x] No I/O operations (no extraction, no writing, no asset listing)
- [x] Hilt `@Inject` constructor and `@ApplicationContext` qualifier preserved
- [x] Implements `CloudflaredBinaryResolver` interface (unchanged)

---

### Task 1.4: Update `CloudflaredBinaryResolver` interface KDoc

The interface KDoc currently references "extracts the binary from APK assets". Update it to match the new approach.

**Actions:**

#### Action 1.4.1: Update KDoc in `CloudflaredBinaryResolver.kt`

**File:** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/CloudflaredBinaryResolver.kt`

```diff
 /**
  * Resolves the filesystem path to the cloudflared binary.
  *
- * Production implementation extracts the binary from APK assets into the
- * app's files directory. Test implementations can point to a host-native binary.
+ * Production implementation resolves the binary from the app's native library
+ * directory (extracted by the package manager at install time).
+ * Test implementations can point to a host-native binary.
  */
```

**Definition of Done:**
- [x] KDoc no longer references "APK assets" or "app's files directory"
- [x] KDoc accurately describes the native library directory approach
- [x] Interface signature unchanged (`fun resolve(): String?`)

---

### Task 1.5: Rewrite `AndroidCloudflareBinaryResolverTest`

The entire test class must be rewritten because the implementation fundamentally changed (no more asset extraction, no more ABI selection, no more `AssetManager`).

**Actions:**

#### Action 1.5.1: Rewrite test class

**File:** `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/AndroidCloudflareBinaryResolverTest.kt`

Replace the **entire file contents** with:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("AndroidCloudflareBinaryResolver")
class AndroidCloudflareBinaryResolverTest {
    private val mockContext = mockk<Context>()
    private val mockApplicationInfo = ApplicationInfo()

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        mockApplicationInfo.nativeLibraryDir = tempDir.absolutePath
        every { mockContext.applicationInfo } returns mockApplicationInfo
    }

    private fun createResolver(): AndroidCloudflareBinaryResolver =
        AndroidCloudflareBinaryResolver(mockContext)

    @Nested
    @DisplayName("resolve")
    inner class Resolve {
        @Test
        fun `returns path when binary exists and is executable`() {
            val binaryFile = File(tempDir, "libcloudflared.so")
            binaryFile.writeBytes(byteArrayOf(0x7F, 0x45, 0x4C, 0x46))
            binaryFile.setExecutable(true, true)

            val resolver = createResolver()
            val result = resolver.resolve()

            assertEquals(binaryFile.absolutePath, result)
        }

        @Test
        fun `returns null when binary does not exist`() {
            val resolver = createResolver()
            val result = resolver.resolve()

            assertNull(result)
        }

        @Test
        fun `returns null when binary exists but is not executable`() {
            val binaryFile = File(tempDir, "libcloudflared.so")
            binaryFile.writeBytes(byteArrayOf(0x7F, 0x45, 0x4C, 0x46))
            binaryFile.setExecutable(false, false)

            val resolver = createResolver()
            val result = resolver.resolve()

            assertNull(result)
        }
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun mockAndroidLog() {
            mockkStatic(Log::class)
            every { Log.e(any(), any()) } returns 0
            every { Log.e(any(), any(), any()) } returns 0
        }

        @JvmStatic
        @AfterAll
        fun unmockAndroidLog() {
            unmockkStatic(Log::class)
        }
    }
}
```

Key changes from the current test class:
- **Removed**: `AssetManager` mock (no longer used)
- **Removed**: `ByteArrayInputStream` import (no longer used)
- **Removed**: `IOException` import (no longer used)
- **Removed**: All asset-related test cases (asset listing, extraction, overwriting, ABI fallback)
- **Removed**: `createResolver(vararg abis: String)` helper (no ABI parameter)
- **Added**: `ApplicationInfo` mock with `nativeLibraryDir` pointing to `@TempDir`
- **Added**: Test: `returns path when binary exists and is executable` — creates a file named `libcloudflared.so` in the temp dir, sets it executable, verifies `resolve()` returns the path
- **Added**: Test: `returns null when binary does not exist` — empty temp dir, verifies `resolve()` returns null
- **Added**: Test: `returns null when binary exists but is not executable` — creates file but does NOT set executable, verifies `resolve()` returns null
- **Preserved**: `Log` mocking in companion object (`@BeforeAll` / `@AfterAll`)

**Definition of Done:**
- [x] 3 test cases: binary exists + executable, binary not found, binary not executable
- [x] No references to `AssetManager`, `IOException`, `ByteArrayInputStream`, `supportedAbis`
- [x] Uses `ApplicationInfo.nativeLibraryDir` pointing to `@TempDir`
- [x] All 3 tests pass

---

### Task 1.6: Update `.gitignore`

Remove the now-obsolete cloudflared assets entry. The `jniLibs/` entry already exists and covers the new output location.

**Actions:**

#### Action 1.6.1: Remove cloudflared assets gitignore entry

**File:** `.gitignore` (lines 61-65)

```diff
-# Native binaries in jniLibs (build artifacts — ngrok from Gradle extractNgrokNative)
+# Native binaries in jniLibs (build artifacts — cloudflared from make compile-cloudflared, ngrok from Gradle extractNgrokNative)
 app/src/main/jniLibs/
-
-# Cloudflared binaries in assets (build artifacts from make compile-cloudflared)
-app/src/main/assets/cloudflared-*
```

**Definition of Done:**
- [x] `app/src/main/assets/cloudflared-*` entry removed
- [x] Separate "Cloudflared binaries in assets" comment removed
- [x] `jniLibs/` comment updated to mention both cloudflared and ngrok
- [x] `app/src/main/jniLibs/` entry preserved (unchanged)

---

### Task 1.7: Update `docs/PROJECT.md`

The Remote Access / Tunnel section at line 470 currently says the binary is "bundled as a native library (`libcloudflared.so`)". While this phrasing happens to already match the target state, the current actual implementation (assets) didn't match. After this fix, the documentation and implementation will be consistent. No change needed for PROJECT.md line 470 — it already correctly describes the target state.

**Actions:**

#### Action 1.7.1: Verify PROJECT.md consistency (no edit needed)

**File:** `docs/PROJECT.md` (line 470)

Current text:
> The cloudflared binary is bundled as a native library (`libcloudflared.so`) via a git submodule in `vendor/cloudflared/`.

This is **already correct** for the new implementation. No edit required.

**Definition of Done:**
- [x] Verified that line 470 of `docs/PROJECT.md` accurately describes the native library approach
- [x] No edit performed (text already matches)

---

### Task 1.8: Run lint, tests, and build

**Actions:**

#### Action 1.8.1: Run linting

```bash
make lint
```

**Definition of Done:**
- [x] `make lint` passes with no errors and no new warnings

#### Action 1.8.2: Run unit tests

```bash
make test-unit
```

**Definition of Done:**
- [x] All unit tests pass (including rewritten `AndroidCloudflareBinaryResolverTest`)

#### Action 1.8.3: Run build

```bash
make build
```

**Definition of Done:**
- [x] Build succeeds without errors or warnings

---

### Task 1.9: Final Verification — Double-check Everything from the Ground Up

Go through every change made and verify correctness against this plan.

**Actions:**

#### Action 1.9.1: Verify Makefile output paths

- [x] Run `grep -n 'CLOUDFLARED' Makefile` and confirm:
  - Variable is `CLOUDFLARED_JNILIBS_DIR := app/src/main/jniLibs`
  - arm64-v8a output: `$(CURDIR)/$(CLOUDFLARED_JNILIBS_DIR)/arm64-v8a/libcloudflared.so`
  - x86_64 output: `$(CURDIR)/$(CLOUDFLARED_JNILIBS_DIR)/x86_64/libcloudflared.so`
  - Both `mkdir -p` create the correct ABI subdirectories
  - No references to `CLOUDFLARED_ASSETS_DIR` or `app/src/main/assets` remain

#### Action 1.9.2: Verify `app/build.gradle.kts`

- [x] Confirm `useLegacyPackaging = true` (not `false`)
- [x] No other changes to the file

#### Action 1.9.3: Verify `AndroidCloudflareBinaryResolver.kt`

- [x] No import of `android.os.Build`, `java.io.IOException`, or `android.content.res.AssetManager`
- [x] No `supportedAbis` field
- [x] No `resolveAssetName()` method
- [x] No `extractAsset()` method
- [x] No `ASSET_PREFIX` or `EXTRACTED_BINARY_NAME` constant
- [x] `resolve()` uses `context.applicationInfo.nativeLibraryDir`
- [x] `resolve()` checks `binaryFile.exists()` and `binaryFile.canExecute()`
- [x] `LIBRARY_NAME = "libcloudflared.so"`

#### Action 1.9.4: Verify `CloudflaredBinaryResolver.kt`

- [x] KDoc does NOT reference "APK assets" or "app's files directory"
- [x] KDoc references "native library directory"
- [x] Interface signature unchanged

#### Action 1.9.5: Verify `AndroidCloudflareBinaryResolverTest.kt`

- [x] No references to `AssetManager`, `IOException`, `ByteArrayInputStream`, `supportedAbis`
- [x] Uses `ApplicationInfo.nativeLibraryDir` with `@TempDir`
- [x] 3 test cases covering: exists + executable, not found, not executable
- [x] All tests pass

#### Action 1.9.6: Verify `.gitignore`

- [x] No `app/src/main/assets/cloudflared-*` entry
- [x] `app/src/main/jniLibs/` entry exists with updated comment
- [x] No stale "Cloudflared binaries in assets" comment

#### Action 1.9.7: Verify `docs/PROJECT.md` line 470

- [x] Text mentions `libcloudflared.so` and native library
- [x] No reference to "assets"

#### Action 1.9.8: Verify no stale references to the old approach remain in source

- [x] Run a project-wide search for `ASSET_PREFIX`, `EXTRACTED_BINARY_NAME`, `cloudflared-arm64`, `cloudflared-x86_64`, `CLOUDFLARED_ASSETS_DIR`, `assets/cloudflared` in non-plan files
- [x] Confirm no production code or config references the old assets-based approach
- [x] Plan documents (docs/plans/) may reference the old approach — that is expected and acceptable

#### Action 1.9.9: Final lint + test + build pass

- [x] `make lint` passes
- [x] `make test-unit` passes
- [x] `make build` succeeds

---

## Git Workflow

1. **Branch creation**: This fix uses the existing branch `fix/cloudflared-binary-assets-extraction` (already created from `main`).
2. **Commits**: Commit changes in an ordered, logical, and sensible sequence. Each commit is a coherent, self-contained unit of work following TOOLS.md conventions. Commits are pushed regularly.
3. **Pull Request**: When all work is complete and all quality gates pass, push any remaining unpushed commits, create the PR via `gh pr create` (following TOOLS.md PR convention), and request Copilot as reviewer (`gh pr edit <PR#> --add-reviewer copilot`). Report the PR URL to the user.
4. **AI attribution prohibition**: Commits and PRs MUST NOT contain any references to Claude Code, Claude, Anthropic, or any AI tooling.

---

## Execution Order Summary

| Order | Task | Description |
|-------|------|-------------|
| 1 | 1.1 | Update Makefile `compile-cloudflared` output paths |
| 2 | 1.2 | Flip `useLegacyPackaging` to `true` in `build.gradle.kts` |
| 3 | 1.3 | Rewrite `AndroidCloudflareBinaryResolver` |
| 4 | 1.4 | Update `CloudflaredBinaryResolver` interface KDoc |
| 5 | 1.5 | Rewrite `AndroidCloudflareBinaryResolverTest` |
| 6 | 1.6 | Update `.gitignore` |
| 7 | 1.7 | Verify `PROJECT.md` consistency (no edit needed) |
| 8 | 1.8 | Run lint, tests, build |
| 9 | 1.9 | Final verification — double-check everything from the ground up |

---

## Performance, Security, and QA Review

### Performance

- **No runtime I/O overhead**: The old approach extracted the binary from assets on every `resolve()` call (~25 MB of I/O each time). The new approach is a simple file path lookup with existence + permission check — negligible overhead.
- **Install size**: `useLegacyPackaging = true` causes native libraries to be extracted at install time, roughly doubling disk usage for `.so` files. This is an acceptable trade-off for functionality.
- **Startup time**: No change. Binary resolution is lazy (only on tunnel start).

### Security

- **No new permissions**: The binary runs from the app's own `nativeLibraryDir`, which is within the app's sandbox. No root access, no reflection, no bypass of Android security.
- **Binary integrity**: The binary is extracted by the Android package manager from the signed APK, ensuring it has not been tampered with. This is actually more secure than the old approach (extracting from assets to a writable directory where the file could theoretically be modified).

### QA

- **Unit tests**: 3 focused tests covering the three code paths (success, file missing, file not executable).
- **Integration tests**: `CloudflareTunnelIntegrationTest` uses a host-native binary (not the Android resolver) and is unaffected by this change.
- **E2E tests**: Would need a real device/emulator with the compiled binary installed. Out of scope for this fix — manual verification is needed on the actual phone.
- **Manual verification needed**: After installing the updated APK on the real device, verify that Cloudflare remote access starts successfully (the "Permission denied" error no longer occurs).
