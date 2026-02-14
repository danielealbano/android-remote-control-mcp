# Plan 14: Dependency Updates & 16KB Page Alignment

**Created:** 2026-02-14
**Priority:** Non-urgent
**Branch:** `feat/dependency-updates-16kb-alignment`

---

## Context

The project has 19 outdated dependencies (some significantly behind) and 4 native `.so` libraries that are not 16KB page-aligned, which will be required for Android 16+ and Google Play compliance. Additionally, the project lacks tooling to automatically detect outdated dependencies.

### Native .so files requiring 16KB alignment

| Library | Source | Current Alignment | Fix Strategy |
|---------|--------|-------------------|--------------|
| `libdatastore_shared_counter.so` | `androidx.datastore:datastore-core-android:1.1.1` | 4KB | Update DataStore to 1.2.0 |
| `libandroidx_graphics_path.so` | Transitive via Compose BOM | 4KB | Update Compose BOM |
| `libngrokjava.so` | `com.ngrok:ngrok-java-native:1.1.1` | 4KB (likely) | Verify; file upstream issue if needed |
| `libcloudflared.so` | Go cross-compiled binary | 4KB | Recompile with 16KB linker flag |

### Dependency audit summary

**Up to date (10):** AGP 8.13.2, Kotlin 2.3.10, KSP 2.3.5, Ktor 3.4.0, MCP SDK 0.8.3, ngrok-java 1.1.1, Bouncy Castle 1.83, Hilt Navigation Compose 1.3.0, Material 1.13.0, SLF4J Android 1.7.36

**Outdated — safe to update (12):**

| Library | Current | Latest | Risk |
|---------|---------|--------|------|
| Compose BOM | 2024.12.01 | 2026.02.00 | Low — BOM manages Compose versions coherently |
| Activity Compose | 1.9.3 | 1.12.3 | Low — additive API |
| Core KTX | 1.15.0 | 1.17.0 | Low — minor updates |
| Lifecycle | 2.8.7 | 2.10.0 | Low — note: raises minSdk to 23 (our minSdk is 26, safe) |
| DataStore | 1.1.1 | 1.2.0 | Low — additive API, fixes 16KB alignment |
| kotlinx-serialization | 1.7.3 | 1.10.0 | Low — stabilizes experimental APIs |
| kotlinx-coroutines | 1.9.0 | 1.10.2 | Low — minor updates |
| Accompanist | 0.36.0 | 0.37.3 | Low — permissions module still active |
| MockK | 1.13.14 | 1.14.9 | Low — test-only dependency |
| Turbine | 1.2.0 | 1.2.1 | Low — patch release |
| Detekt | 1.23.7 | 1.23.8 | Low — patch release |
| AndroidX Test (Core/Runner/Rules) | 1.6.x | 1.7.0 | Low — test-only dependency |

**Outdated — requires caution (3):**

| Library | Current | Latest | Risk | Decision |
|---------|---------|--------|------|----------|
| Hilt | 2.58 | 2.59.1 | **High** — 2.59 reportedly requires AGP 9 (needs verification) | **Skip** — stay on 2.58 until AGP 9 migration |
| ktlint plugin | 12.1.2 | 14.0.1 | **Medium** — two major version jump, new ktlint rules may cause failures | Update cautiously, run `ktlintFormat` |
| Testcontainers | 1.20.4 | 2.0.3 | **High** — major breaking changes (class relocations, module renames) | **Skip** — not worth the migration risk |

**Outdated — major version jump, defer (1):**

| Library | Current | Latest | Decision |
|---------|---------|--------|----------|
| JUnit 5 | 5.11.4 | 5.13.4 | Update within 5.x line; skip JUnit 6.0 |

**Flagged — deprecated but no clean replacement (1):**

| Library | Current | Status | Decision |
|---------|---------|--------|----------|
| SLF4J Android | 1.7.36 | Abandoned, not ported to SLF4J 2.x | **Keep** — works, MCP SDK requires SLF4J API |

---

## User Story 1: Add Dependency Version Management Tooling

**As a** developer
**I want** automated tooling to detect outdated dependencies
**So that** I can keep dependencies current without manual version checking

### Acceptance Criteria
- `nl.littlerobots.version-catalog-update` plugin (v1.0.1) is configured
- `./gradlew versionCatalogUpdate --interactive` shows available updates
- `com.github.ben-manes.versions` plugin (v0.53.0) is configured as a companion
- `./gradlew dependencyUpdates` generates an update report
- Stable-only filter is configured (reject alpha/beta/rc/dev/snapshot versions)
- Both plugins are declared in `libs.versions.toml`
- Lint and existing tests pass

### Task 1.1: Add version management plugins to version catalog

**Actions:**

1. **Edit `gradle/libs.versions.toml`** — Add plugin versions and declarations:

```toml
# In [versions] section, after detekt:
gradle-versions = "0.53.0"
version-catalog-update = "1.0.1"

# In [plugins] section, after detekt:
gradle-versions = { id = "com.github.ben-manes.versions", version.ref = "gradle-versions" }
version-catalog-update = { id = "nl.littlerobots.version-catalog-update", version.ref = "version-catalog-update" }
```

2. **Edit root `build.gradle.kts`** — Apply both plugins (without `apply false` since they are root-level tools providing tasks):

The current root `build.gradle.kts` has all plugins with `apply false`. The version management plugins should be applied directly at the root since they provide project-wide tasks:

```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.gradle.versions)
    alias(libs.plugins.version.catalog.update)
}

tasks.withType<DependencyUpdatesTask> {
    rejectVersionIf {
        val version = candidate.version.uppercase()
        listOf("ALPHA", "BETA", "RC", "DEV", "SNAPSHOT", "M1", "M2", "M3").any { version.contains(it) }
    }
}
```

3. **Update Makefile** — Add convenience targets:

```makefile
check-deps-updates: ## Check for outdated dependencies
	$(GRADLE) dependencyUpdates --no-parallel

update-deps: ## Update version catalog with latest stable versions (interactive)
	$(GRADLE) versionCatalogUpdate --interactive
```

4. **Update `.PHONY`** declaration to include `check-deps-updates` and `update-deps`.

**Acceptance Criteria:** `./gradlew dependencyUpdates --no-parallel` runs and produces a report. `./gradlew versionCatalogUpdate --interactive` shows available updates.

---

## User Story 2: Update All Outdated Dependencies

**As a** developer
**I want** all dependencies updated to their latest stable versions
**So that** the project benefits from bug fixes, performance improvements, and 16KB-aligned native libraries

### Acceptance Criteria
- All safe-to-update dependencies are updated to latest stable versions
- Compose BOM, DataStore, and AndroidX Graphics are updated (fixes 16KB for 2 of 4 .so files)
- Lint passes with no new warnings/errors
- All existing tests pass (463+ tests)
- Build succeeds without errors or warnings

### Task 2.1: Update AndroidX dependencies

**Actions:**

1. **Edit `gradle/libs.versions.toml`** — Update versions:

```diff
-compose-bom = "2024.12.01"
+compose-bom = "2026.02.00"

-activity-compose = "1.9.3"
+activity-compose = "1.12.3"

-core-ktx = "1.15.0"
+core-ktx = "1.17.0"

-lifecycle = "2.8.7"
+lifecycle = "2.10.0"

-datastore = "1.1.1"
+datastore = "1.2.0"
```

2. **Edit `gradle/libs.versions.toml`** — Remove pinned `compose-ui-test` version since BOM manages it:

```diff
-compose-ui-test = "1.7.6"
```

Update the library declarations to remove `version.ref`:
```diff
-compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4", version.ref = "compose-ui-test" }
-compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest", version.ref = "compose-ui-test" }
+compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
+compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
```

3. **Edit `gradle/libs.versions.toml`** — Update AndroidX Test:

```diff
-test-core = "1.6.1"
+test-core = "1.7.0"

-test-runner = "1.6.2"
+test-runner = "1.7.0"

-test-rules = "1.6.1"
+test-rules = "1.7.0"
```

4. **Edit `gradle/libs.versions.toml`** — Update Accompanist:

```diff
-accompanist = "0.36.0"
+accompanist = "0.37.3"
```

**Acceptance Criteria:** Build succeeds. `./gradlew :app:testDebugUnitTest` passes. `./gradlew ktlintCheck detekt` passes.

### Task 2.2: Update Kotlin ecosystem dependencies

**Actions:**

1. **Edit `gradle/libs.versions.toml`** — Update versions:

```diff
-kotlinx-serialization = "1.7.3"
+kotlinx-serialization = "1.10.0"

-kotlinx-coroutines = "1.9.0"
+kotlinx-coroutines = "1.10.2"
```

**Acceptance Criteria:** Build succeeds. All tests pass.

### Task 2.3: Update testing dependencies

**Actions:**

1. **Edit `gradle/libs.versions.toml`** — Update versions:

```diff
-junit5 = "5.11.4"
+junit5 = "5.13.4"

-mockk = "1.13.14"
+mockk = "1.14.9"

-turbine = "1.2.0"
+turbine = "1.2.1"
```

**Note:** Testcontainers stays at 1.20.4 (2.0.x has breaking changes not worth the migration). Hilt stays at 2.58 (2.59.x requires AGP 9).

**Acceptance Criteria:** All tests pass. No compilation errors from API changes.

### Task 2.4: Update linting tools

**Actions:**

1. **Edit `gradle/libs.versions.toml`** — Update versions:

```diff
-ktlint-gradle = "12.1.2"
+ktlint-gradle = "14.0.1"

-detekt = "1.23.7"
+detekt = "1.23.8"
```

2. **Run `./gradlew ktlintFormat`** to auto-fix any new rule violations introduced by the ktlint upgrade.

3. **Run `./gradlew ktlintCheck detekt`** to verify no remaining lint issues.

**Acceptance Criteria:** Lint passes with zero warnings/errors.

### Task 2.5: Remove stale version comments

**Actions:**

1. **Edit `gradle/libs.versions.toml`** — Remove all `# verify latest at implementation time` comments since we now have tooling to check versions:

```diff
-compose-bom = "2026.02.00"             # verify latest at implementation time
+compose-bom = "2026.02.00"
```

(Apply to all entries that have this comment.)

**Acceptance Criteria:** Clean TOML file with no stale comments.

### Task 2.6: Full verification

**Actions:**

1. Run `./gradlew clean build` to verify full build succeeds.
2. Run `set -a && source .env && set +a && ./gradlew :app:testDebugUnitTest` to verify all tests pass.
3. Run `./gradlew ktlintCheck detekt` to verify lint passes.

**Acceptance Criteria:** Build succeeds. All tests pass (excluding tunnel integration tests that require external services). Lint passes.

---

## User Story 3: Fix 16KB Page Alignment for Native Libraries

**As a** developer
**I want** all bundled native `.so` libraries to be 16KB page-aligned
**So that** the APK is compatible with Android 16+ devices and meets Google Play requirements

### Acceptance Criteria
- `libdatastore_shared_counter.so` is 16KB aligned (via DataStore 1.2.0 update in US2)
- `libandroidx_graphics_path.so` is 16KB aligned (via Compose BOM update in US2)
- `libcloudflared.so` is 16KB aligned (recompiled with linker flag)
- `libngrokjava.so` alignment status is verified and documented
- Verification script/command is documented for checking alignment
- APK installs correctly on 16KB page-size emulator (manual verification)

### Task 3.1: Verify alignment of updated AndroidX libraries

After US2 dependency updates are applied:

**Actions:**

1. **Build the APK**: `./gradlew assembleDebug`

2. **Extract and verify alignment** of the updated `.so` files:

```bash
# Extract .so files from APK
unzip -o app/build/outputs/apk/debug/app-debug.apk -d /tmp/apk-check "lib/*"

# Check alignment of each .so file
for so in /tmp/apk-check/lib/**/*.so; do
    echo "=== $so ==="
    llvm-objdump -p "$so" 2>/dev/null | grep -A1 LOAD | head -20
    echo ""
done
```

The `LOAD` segments should show alignment of `0x4000` (16384 = 16KB) or higher. If they show `0x1000` (4096 = 4KB), the library is NOT 16KB aligned.

3. **Document results** for each `.so` file.

**Acceptance Criteria:** `libdatastore_shared_counter.so` and `libandroidx_graphics_path.so` show 16KB alignment after the AndroidX/Compose BOM updates.

### Task 3.2: Recompile cloudflared with 16KB page alignment

**Actions:**

1. **Edit `Makefile`** — Update the `compile-cloudflared` target to include the 16KB linker flag:

```diff
-	go build -a -installsuffix cgo -ldflags="-s -w" \
+	go build -a -installsuffix cgo -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" \
```

Apply this change to both the arm64-v8a and x86_64 compilation commands.

2. **Recompile cloudflared**: `make compile-cloudflared`

3. **Verify alignment**:

```bash
llvm-objdump -p app/src/main/jniLibs/arm64-v8a/libcloudflared.so | grep -A1 LOAD | head -20
```

**Acceptance Criteria:** `libcloudflared.so` shows `0x4000` alignment for LOAD segments.

### Task 3.3: Verify ngrok-java native library alignment

**Actions:**

1. **Extract the ngrok native .so from the Gradle cache or APK**:

```bash
# From the debug APK after build
unzip -o app/build/outputs/apk/debug/app-debug.apk -d /tmp/apk-check "lib/arm64-v8a/libngrokjava.so"
llvm-objdump -p /tmp/apk-check/lib/arm64-v8a/libngrokjava.so | grep -A1 LOAD | head -20
```

2. **If NOT 16KB aligned** (shows `0x1000` for LOAD segments):
   - Document the finding
   - File a GitHub issue on `ngrok/ngrok-java` requesting 16KB-aligned native builds
   - Add a TODO comment in `build.gradle.kts` referencing the issue
   - This is a **vendor dependency** — we cannot fix it ourselves (Rust cross-compilation)

3. **If 16KB aligned** — document and mark as resolved.

**Acceptance Criteria:** Alignment status of `libngrokjava.so` is verified and documented. If unaligned, upstream issue is filed.

### Task 3.4: Update packaging configuration

**Actions:**

1. **Edit `app/build.gradle.kts`** — Change `useLegacyPackaging` to `false`:

```diff
     jniLibs {
-        useLegacyPackaging = true
+        useLegacyPackaging = false
     }
```

With `useLegacyPackaging = false`, `.so` files are stored uncompressed in the APK. On Android 6.0+, the OS can load them directly via `mmap` without extracting to the device filesystem. This is the recommended setting for 16KB alignment compliance — the OS handles page-aligned loading from the APK.

**Note:** This increases APK size (uncompressed `.so` files) but reduces install size and improves startup time. App Bundles (AAB) handle compression at the Play Store level, so this only affects debug APKs and direct APK distribution.

2. **Edit `gradle.properties`** — Add:

```
android.bundle.enableUncompressedNativeLibs=true
```

This ensures App Bundles also store `.so` files uncompressed for proper 16KB-aligned loading.

**Acceptance Criteria:** Build succeeds. APK contains uncompressed `.so` files.

### Task 3.5: Add Makefile target for alignment verification

**Actions:**

1. **Edit `Makefile`** — Add a verification target:

```makefile
check-so-alignment: ## Check 16KB page alignment of native .so libraries in debug APK
	@APK="app/build/outputs/apk/debug/app-debug.apk"; \
	if [ ! -f "$$APK" ]; then \
		echo "Debug APK not found. Run 'make build' first."; \
		exit 1; \
	fi; \
	TMPDIR=$$(mktemp -d); \
	unzip -q -o "$$APK" "lib/*" -d "$$TMPDIR" 2>/dev/null; \
	FAIL=0; \
	for so in $$(find "$$TMPDIR/lib" -name "*.so" 2>/dev/null); do \
		ALIGN=$$(llvm-objdump -p "$$so" 2>/dev/null | awk '/LOAD/{getline; if($$NF+0 < 16384) print "4KB"; else print "16KB"; exit}'); \
		NAME=$$(basename "$$so"); \
		ABI=$$(basename $$(dirname "$$so")); \
		if [ "$$ALIGN" = "4KB" ]; then \
			echo "  [FAIL] $$ABI/$$NAME — 4KB aligned (needs 16KB)"; \
			FAIL=1; \
		else \
			echo "  [OK]   $$ABI/$$NAME — 16KB aligned"; \
		fi; \
	done; \
	rm -rf "$$TMPDIR"; \
	if [ $$FAIL -eq 1 ]; then \
		echo ""; \
		echo "Some .so files are not 16KB aligned."; \
		exit 1; \
	else \
		echo ""; \
		echo "All .so files are 16KB aligned."; \
	fi
```

2. **Update `.PHONY`** declaration to include `check-so-alignment`.

**Acceptance Criteria:** `make check-so-alignment` runs and reports alignment status for all `.so` files.

### Task 3.6: Full verification

**Actions:**

1. Run `make build` to build debug APK.
2. Run `make check-so-alignment` to verify alignment.
3. Run all tests to verify nothing broke.

**Acceptance Criteria:** All `.so` files under our control are 16KB aligned. Any vendor `.so` files that are not aligned are documented with upstream issue references.

---

## Dependencies NOT Updated (Deferred)

| Library | Current | Latest | Reason |
|---------|---------|--------|--------|
| Hilt | 2.58 | 2.59.1 | 2.59 reportedly requires AGP 9 (needs verification during implementation); stay on 2.58 for AGP 8.x compatibility |
| Testcontainers | 1.20.4 | 2.0.3 | Major breaking changes (class relocations, module renames); not worth migration risk for E2E test module |
| JUnit | 5.x → 6.x | 6.0.2 | Major rewrite with breaking changes; stay on 5.13.4 within the 5.x line |
| SLF4J Android | 1.7.36 | — | Abandoned upstream, no clean replacement. Works fine for MCP SDK logging. |

---

## Execution Order

1. **US1** (Task 1.1) — Add version management plugins
2. **US2** (Tasks 2.1–2.6) — Update all safe dependencies
3. **US3** (Tasks 3.1–3.6) — Fix 16KB alignment
4. Final commit, push, PR

---

## Performance, Security, and QA Review

### Performance
- `useLegacyPackaging = false` slightly increases APK size but reduces install size and improves cold start time (OS loads `.so` via mmap instead of extracting).
- Updated kotlinx-coroutines and Lifecycle bring performance improvements.

### Security
- Bouncy Castle 1.83 is already the latest — no known CVEs.
- Updated dependencies include security patches from upstream (DataStore, Ktor, etc.).
- ngrok-java native library alignment is a supply chain concern — document and track.

### QA
- Compose BOM update is the riskiest change (pulls in many transitive version changes). Mitigated by running full test suite after the update.
- ktlint 14.0.1 may introduce new lint rules — run `ktlintFormat` before `ktlintCheck`.
- MockK 1.14.x may have minor API changes — watch for test compilation errors.
- All changes are verified with the full test suite (463+ tests) and lint tools.
