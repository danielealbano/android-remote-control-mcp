.PHONY: help check-deps check-deps-updates update-deps build build-release clean \
        test-unit test-integration test-e2e test coverage \
        lint lint-fix \
        install install-release uninstall grant-permissions start-server forward-port \
        setup-emulator start-emulator stop-emulator \
        logs logs-clear \
        version-bump-patch version-bump-minor version-bump-major \
        compile-cloudflared compile-ngrok-native check-so-alignment \
        all ci

# Variables
ANDROID_HOME ?= $(HOME)/Android/Sdk
GRADLE := ./gradlew
ADB := adb
APP_ID := com.danielealbano.androidremotecontrolmcp
APP_ID_DEBUG := $(APP_ID).debug
EMULATOR_NAME := mcp_test_emulator
EMULATOR_DEVICE := pixel_6
EMULATOR_API := 34
EMULATOR_IMAGE := system-images;android-$(EMULATOR_API);google_apis;x86_64
DEFAULT_PORT := 8080

# ─────────────────────────────────────────────────────────────────────────────
# Help
# ─────────────────────────────────────────────────────────────────────────────

help: ## Show this help message
	@echo "Android Remote Control MCP - Development Targets"
	@echo ""
	@echo "Usage: make <target>"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

# ─────────────────────────────────────────────────────────────────────────────
# Environment & Dependencies
# ─────────────────────────────────────────────────────────────────────────────

check-deps: ## Check for required development tools
	@echo "Checking required tools..."
	@echo ""
	@MISSING=0; \
	echo "  [OK] ANDROID_HOME = $(ANDROID_HOME)"; \
	if [ ! -d "$(ANDROID_HOME)" ]; then \
		echo "  [WARN] ANDROID_HOME directory does not exist: $(ANDROID_HOME)"; \
		echo "         Install Android SDK or set: export ANDROID_HOME=/path/to/sdk"; \
	fi; \
	if command -v java >/dev/null 2>&1; then \
		JAVA_VER=$$(java -version 2>&1 | head -1 | awk -F'"' '{print $$2}'); \
		echo "  [OK] Java $$JAVA_VER"; \
	else \
		echo "  [MISSING] Java (JDK 17 required)"; \
		echo "           Install: https://adoptium.net/"; \
		MISSING=1; \
	fi; \
	if [ -f "$(GRADLE)" ]; then \
		echo "  [OK] Gradle wrapper found"; \
	else \
		echo "  [MISSING] Gradle wrapper (gradlew)"; \
		echo "           Run: gradle wrapper --gradle-version 8.14.4"; \
		MISSING=1; \
	fi; \
	if command -v $(ADB) >/dev/null 2>&1; then \
		ADB_VER=$$($(ADB) version | head -1); \
		echo "  [OK] $$ADB_VER"; \
	else \
		echo "  [MISSING] adb (Android Debug Bridge)"; \
		echo "           Install Android SDK platform-tools"; \
		MISSING=1; \
	fi; \
	if command -v docker >/dev/null 2>&1; then \
		DOCKER_VER=$$(docker --version); \
		echo "  [OK] $$DOCKER_VER"; \
	else \
		echo "  [MISSING] Docker (required for E2E tests)"; \
		echo "           Install: https://docs.docker.com/get-docker/"; \
		MISSING=1; \
	fi; \
	if command -v go >/dev/null 2>&1; then \
		GO_VER=$$(go version); \
		echo "  [OK] $$GO_VER"; \
	else \
		echo "  [MISSING] Go (required for compiling cloudflared)"; \
		echo "           Install: https://go.dev/dl/"; \
		MISSING=1; \
	fi; \
	if command -v cargo >/dev/null 2>&1; then \
		CARGO_VER=$$(cargo --version); \
		echo "  [OK] $$CARGO_VER"; \
	else \
		echo "  [MISSING] Rust/cargo (required for compiling ngrok-java native)"; \
		echo "           Install: https://rustup.rs/"; \
		MISSING=1; \
	fi; \
	if command -v mvn >/dev/null 2>&1; then \
		MVN_VER=$$(mvn --version 2>&1 | head -1); \
		echo "  [OK] $$MVN_VER"; \
	else \
		echo "  [MISSING] Maven (required for compiling ngrok-java)"; \
		echo "           Install: brew install maven"; \
		MISSING=1; \
	fi; \
	echo ""; \
	if [ $$MISSING -eq 1 ]; then \
		echo "Some dependencies are missing. Please install them."; \
		exit 1; \
	else \
		echo "All dependencies are present."; \
	fi

check-deps-updates: ## Check for outdated dependencies
	$(GRADLE) dependencyUpdates --no-parallel

update-deps: ## Update version catalog with latest stable versions (interactive)
	$(GRADLE) versionCatalogUpdate --interactive

# ─────────────────────────────────────────────────────────────────────────────
# Build
# ─────────────────────────────────────────────────────────────────────────────

build: compile-cloudflared compile-ngrok-native ## Build debug APK
	$(GRADLE) assembleDebug

build-release: compile-cloudflared compile-ngrok-native ## Build release APK
	$(GRADLE) assembleRelease

clean: ## Clean build artifacts
	$(GRADLE) clean

# ─────────────────────────────────────────────────────────────────────────────
# Testing
# ─────────────────────────────────────────────────────────────────────────────

test-unit: ## Run unit tests (includes integration tests since both are JVM-based)
	$(if $(wildcard .env),set -a && . ./.env && set +a &&,) $(GRADLE) :app:test

test-integration: ## Run integration tests (JVM-based, no emulator required)
	$(if $(wildcard .env),set -a && . ./.env && set +a &&,) $(GRADLE) :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.integration.*"

test-e2e: ## Run E2E tests (requires Docker)
	$(if $(wildcard .env),set -a && . ./.env && set +a &&,) $(GRADLE) :e2e-tests:cleanTest :e2e-tests:test

test: test-unit test-e2e ## Run all tests

coverage: ## Generate code coverage report (Jacoco)
	$(GRADLE) jacocoTestReport
	@echo "Coverage report: app/build/reports/jacoco/jacocoTestReport/html/index.html"

# ─────────────────────────────────────────────────────────────────────────────
# Linting
# ─────────────────────────────────────────────────────────────────────────────

lint: ## Run all linters (ktlint + detekt)
	$(GRADLE) ktlintCheck detekt

lint-fix: ## Auto-fix linting issues
	$(GRADLE) ktlintFormat

# ─────────────────────────────────────────────────────────────────────────────
# Device Management
# ─────────────────────────────────────────────────────────────────────────────

install: ## Install debug APK on connected device/emulator
	$(GRADLE) installDebug

install-release: ## Install release APK on connected device/emulator
	$(GRADLE) installRelease

uninstall: ## Uninstall app from connected device/emulator
	$(ADB) uninstall $(APP_ID) 2>/dev/null || true
	$(ADB) uninstall $(APP_ID_DEBUG) 2>/dev/null || true

grant-permissions: ## Grant permissions via adb (accessibility + notifications)
	@echo "=== Granting permissions via adb ==="
	@echo ""
	@echo "1. Enabling Accessibility Service..."
	$(ADB) shell settings put secure enabled_accessibility_services \
		$(APP_ID_DEBUG)/com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
	@echo "   Done."
	@echo ""
	@echo "2. Granting POST_NOTIFICATIONS permission..."
	$(ADB) shell pm grant $(APP_ID_DEBUG) android.permission.POST_NOTIFICATIONS
	@echo "   Done."
	@echo ""

# Note: start-server defaults to the debug application ID (APP_ID_DEBUG).
# To launch the release build, use: make start-server APP_ID_TARGET=$(APP_ID)
APP_ID_TARGET ?= $(APP_ID_DEBUG)

start-server: ## Launch MainActivity on device (debug build by default)
	$(ADB) shell am start -n $(APP_ID_TARGET)/.ui.MainActivity

forward-port: ## Set up adb port forwarding (device -> host)
	$(ADB) forward tcp:$(DEFAULT_PORT) tcp:$(DEFAULT_PORT)
	@echo "Port forwarding: localhost:$(DEFAULT_PORT) -> device:$(DEFAULT_PORT)"

# ─────────────────────────────────────────────────────────────────────────────
# Emulator Management
# ─────────────────────────────────────────────────────────────────────────────

setup-emulator: ## Create AVD for testing
	@echo "Creating AVD '$(EMULATOR_NAME)'..."
	@echo "Ensure system image is installed: sdkmanager '$(EMULATOR_IMAGE)'"
	avdmanager create avd \
		-n $(EMULATOR_NAME) \
		-k "$(EMULATOR_IMAGE)" \
		--device "$(EMULATOR_DEVICE)" \
		--force
	@echo "AVD '$(EMULATOR_NAME)' created."

start-emulator: ## Start emulator in background (headless)
	@echo "Starting emulator '$(EMULATOR_NAME)'..."
	emulator -avd $(EMULATOR_NAME) -no-snapshot -no-window -no-audio -no-metrics &
	@echo "Waiting for emulator to boot..."
	$(ADB) wait-for-device
	$(ADB) shell getprop sys.boot_completed | grep -q 1 || \
		(echo "Waiting for boot..."; while [ "$$($(ADB) shell getprop sys.boot_completed 2>/dev/null)" != "1" ]; do sleep 2; done)
	@echo "Emulator is ready."

stop-emulator: ## Stop running emulator
	$(ADB) -s emulator-5554 emu kill 2>/dev/null || true
	@echo "Emulator stopped."

# ─────────────────────────────────────────────────────────────────────────────
# Logging & Debugging
# ─────────────────────────────────────────────────────────────────────────────

logs: ## Show app logs (filtered by MCP tags)
	$(ADB) logcat -s "MCP:*" "AndroidRemoteControl:*"

logs-clear: ## Clear logcat buffer
	$(ADB) logcat -c
	@echo "Logcat buffer cleared."

# ─────────────────────────────────────────────────────────────────────────────
# Versioning
# ─────────────────────────────────────────────────────────────────────────────

version-bump-patch: ## Bump patch version (1.0.0 -> 1.0.1)
	@CURRENT=$$(grep '^VERSION_NAME=' gradle.properties | cut -d= -f2); \
	MAJOR=$$(echo $$CURRENT | cut -d. -f1); \
	MINOR=$$(echo $$CURRENT | cut -d. -f2); \
	PATCH=$$(echo $$CURRENT | cut -d. -f3); \
	NEW_PATCH=$$((PATCH + 1)); \
	NEW_VERSION="$$MAJOR.$$MINOR.$$NEW_PATCH"; \
	sed -i.bak "s/^VERSION_NAME=.*/VERSION_NAME=$$NEW_VERSION/" gradle.properties; \
	CODE=$$(grep '^VERSION_CODE=' gradle.properties | cut -d= -f2); \
	NEW_CODE=$$((CODE + 1)); \
	sed -i.bak "s/^VERSION_CODE=.*/VERSION_CODE=$$NEW_CODE/" gradle.properties; \
	rm -f gradle.properties.bak; \
	echo "Version bumped: $$CURRENT -> $$NEW_VERSION (code: $$CODE -> $$NEW_CODE)"

version-bump-minor: ## Bump minor version (1.0.0 -> 1.1.0)
	@CURRENT=$$(grep '^VERSION_NAME=' gradle.properties | cut -d= -f2); \
	MAJOR=$$(echo $$CURRENT | cut -d. -f1); \
	MINOR=$$(echo $$CURRENT | cut -d. -f2); \
	NEW_MINOR=$$((MINOR + 1)); \
	NEW_VERSION="$$MAJOR.$$NEW_MINOR.0"; \
	sed -i.bak "s/^VERSION_NAME=.*/VERSION_NAME=$$NEW_VERSION/" gradle.properties; \
	CODE=$$(grep '^VERSION_CODE=' gradle.properties | cut -d= -f2); \
	NEW_CODE=$$((CODE + 1)); \
	sed -i.bak "s/^VERSION_CODE=.*/VERSION_CODE=$$NEW_CODE/" gradle.properties; \
	rm -f gradle.properties.bak; \
	echo "Version bumped: $$CURRENT -> $$NEW_VERSION (code: $$CODE -> $$NEW_CODE)"

version-bump-major: ## Bump major version (1.0.0 -> 2.0.0)
	@CURRENT=$$(grep '^VERSION_NAME=' gradle.properties | cut -d= -f2); \
	MAJOR=$$(echo $$CURRENT | cut -d. -f1); \
	NEW_MAJOR=$$((MAJOR + 1)); \
	NEW_VERSION="$$NEW_MAJOR.0.0"; \
	sed -i.bak "s/^VERSION_NAME=.*/VERSION_NAME=$$NEW_VERSION/" gradle.properties; \
	CODE=$$(grep '^VERSION_CODE=' gradle.properties | cut -d= -f2); \
	NEW_CODE=$$((CODE + 1)); \
	sed -i.bak "s/^VERSION_CODE=.*/VERSION_CODE=$$NEW_CODE/" gradle.properties; \
	rm -f gradle.properties.bak; \
	echo "Version bumped: $$CURRENT -> $$NEW_VERSION (code: $$CODE -> $$NEW_CODE)"

# ─────────────────────────────────────────────────────────────────────────────
# Native Binary Compilation (cloudflared + ngrok)
# ─────────────────────────────────────────────────────────────────────────────

CLOUDFLARED_SRC_DIR := vendor/cloudflared
CLOUDFLARED_JNILIBS_DIR := app/src/main/jniLibs

compile-cloudflared: ## Cross-compile cloudflared for Android (requires Go + Android NDK)
	@if [ ! -f "$(CLOUDFLARED_SRC_DIR)/cmd/cloudflared/main.go" ]; then \
		echo "ERROR: cloudflared submodule not initialized."; \
		echo "Run: git submodule update --init vendor/cloudflared"; \
		exit 1; \
	fi
	@echo "Compiling cloudflared from submodule ($(CLOUDFLARED_SRC_DIR))..."
	@echo ""
	@echo "Compiling cloudflared for arm64-v8a..."
	mkdir -p $(CLOUDFLARED_JNILIBS_DIR)/arm64-v8a
	cd $(CLOUDFLARED_SRC_DIR) && \
		CGO_ENABLED=1 GOOS=android GOARCH=arm64 \
		CC=$$(find $(ANDROID_HOME)/ndk -name "aarch64-linux-android*-clang" | sort -V | tail -1) \
		go build -a -installsuffix cgo -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" \
		-o $(CURDIR)/$(CLOUDFLARED_JNILIBS_DIR)/arm64-v8a/libcloudflared.so \
		./cmd/cloudflared
	@echo ""
	@echo "Compiling cloudflared for x86_64..."
	mkdir -p $(CLOUDFLARED_JNILIBS_DIR)/x86_64
	cd $(CLOUDFLARED_SRC_DIR) && \
		CGO_ENABLED=1 GOOS=android GOARCH=amd64 \
		CC=$$(find $(ANDROID_HOME)/ndk -name "x86_64-linux-android*-clang" | sort -V | tail -1) \
		go build -a -installsuffix cgo -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" \
		-o $(CURDIR)/$(CLOUDFLARED_JNILIBS_DIR)/x86_64/libcloudflared.so \
		./cmd/cloudflared
	@echo ""
	@echo "cloudflared compiled successfully for arm64-v8a and x86_64"

NGROK_SRC_DIR := vendor/ngrok-java
NGROK_NATIVE_DIR := $(NGROK_SRC_DIR)/ngrok-java-native
NGROK_JNILIBS_DIR := app/src/main/jniLibs
NGROK_JAVA_JAR := $(NGROK_SRC_DIR)/ngrok-java/target/ngrok-java-1.1.1.jar
NGROK_HOST_NATIVE_DIR := $(NGROK_NATIVE_DIR)/target/aarch64-apple-darwin/release
JAVA_HOME_17 ?= /opt/homebrew/opt/openjdk@17

# NDK root auto-detection: check ANDROID_HOME/ndk first, then brew cask location
NDK_ROOT := $(shell \
	if [ -d "$(ANDROID_HOME)/ndk" ] && ls "$(ANDROID_HOME)/ndk" 2>/dev/null | grep -q .; then \
		ls -d "$(ANDROID_HOME)/ndk"/*/ 2>/dev/null | sort -V | tail -1; \
	elif [ -d "/opt/homebrew/Caskroom/android-ndk" ]; then \
		NDK_VER=$$(ls /opt/homebrew/Caskroom/android-ndk/ | sort -V | tail -1); \
		APP_DIR=$$(ls -d "/opt/homebrew/Caskroom/android-ndk/$$NDK_VER/"*.app 2>/dev/null | head -1); \
		echo "$$APP_DIR/Contents/NDK"; \
	fi)
NDK_BIN := $(NDK_ROOT)/toolchains/llvm/prebuilt/$(shell uname -s | tr A-Z a-z)-$(shell uname -m | sed 's/arm64/x86_64/')/bin

compile-ngrok-native: ## Build ngrok-java native library from source (requires Rust + Android NDK + Maven)
	@if [ ! -f "$(NGROK_NATIVE_DIR)/Cargo.toml" ]; then \
		echo "ERROR: ngrok-java submodule not initialized."; \
		echo "Run: git submodule update --init vendor/ngrok-java"; \
		exit 1; \
	fi
	@if [ ! -d "$(NDK_ROOT)" ]; then \
		echo "ERROR: Android NDK not found."; \
		echo "Install via: brew install --cask android-ndk"; \
		exit 1; \
	fi
	@if ! command -v cargo >/dev/null 2>&1; then \
		echo "ERROR: Rust/cargo not found. Install: https://rustup.rs/"; \
		exit 1; \
	fi
	@if ! command -v mvn >/dev/null 2>&1; then \
		echo "ERROR: Maven not found. Install: brew install maven"; \
		exit 1; \
	fi
	@echo "=== Compiling ngrok-java from source ==="
	@echo ""
	@echo "Step 1: Compiling Java classes (needed for JNI code generation)..."
	cd $(NGROK_SRC_DIR) && \
		JAVA_HOME=$(JAVA_HOME_17) \
		JAVA_11_HOME=$(JAVA_HOME_17) \
		JAVA_17_HOME=$(JAVA_HOME_17) \
		mvn compile -pl ngrok-java-native --also-make --global-toolchains toolchains.xml -q
	@echo ""
	@echo "Step 2: Packaging Java JAR..."
	cd $(NGROK_SRC_DIR) && \
		JAVA_HOME=$(JAVA_HOME_17) \
		JAVA_11_HOME=$(JAVA_HOME_17) \
		JAVA_17_HOME=$(JAVA_HOME_17) \
		mvn package -pl ngrok-java -DskipTests --global-toolchains toolchains.xml -q
	@echo ""
	@echo "Step 3: Building native library for arm64-v8a (aarch64-linux-android)..."
	cd $(NGROK_NATIVE_DIR) && \
		CC_aarch64_linux_android="$(NDK_BIN)/aarch64-linux-android21-clang" \
		AR_aarch64_linux_android="$(NDK_BIN)/llvm-ar" \
		CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$(NDK_BIN)/aarch64-linux-android21-clang" \
		cargo build --release --target aarch64-linux-android
	@echo ""
	@echo "Step 4: Building native library for x86_64 (x86_64-linux-android)..."
	cd $(NGROK_NATIVE_DIR) && \
		CC_x86_64_linux_android="$(NDK_BIN)/x86_64-linux-android21-clang" \
		AR_x86_64_linux_android="$(NDK_BIN)/llvm-ar" \
		CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$(NDK_BIN)/x86_64-linux-android21-clang" \
		cargo build --release --target x86_64-linux-android
	@echo ""
	@echo "Step 5: Building native library for host (JVM tests)..."
	cd $(NGROK_NATIVE_DIR) && cargo build --release
	@echo ""
	@echo "Step 6: Copying .so files to jniLibs..."
	mkdir -p $(NGROK_JNILIBS_DIR)/arm64-v8a $(NGROK_JNILIBS_DIR)/x86_64
	cp $(NGROK_NATIVE_DIR)/target/aarch64-linux-android/release/libngrok_java.so $(NGROK_JNILIBS_DIR)/arm64-v8a/
	cp $(NGROK_NATIVE_DIR)/target/x86_64-linux-android/release/libngrok_java.so $(NGROK_JNILIBS_DIR)/x86_64/
	@echo ""
	@echo "ngrok-java compiled successfully:"
	@echo "  JAR:     $(NGROK_JAVA_JAR)"
	@echo "  arm64:   $(NGROK_JNILIBS_DIR)/arm64-v8a/libngrok_java.so"
	@echo "  x86_64:  $(NGROK_JNILIBS_DIR)/x86_64/libngrok_java.so"
	@echo "  host:    $(NGROK_NATIVE_DIR)/target/release/"

check-so-alignment: ## Check 16KB page alignment of native .so libraries in debug APK
	@if ! command -v llvm-objdump >/dev/null 2>&1; then \
		echo "ERROR: llvm-objdump not found. Install LLVM toolchain."; \
		exit 1; \
	fi; \
	APK="app/build/outputs/apk/debug/app-debug.apk"; \
	if [ ! -f "$$APK" ]; then \
		echo "Debug APK not found. Run 'make build' first."; \
		exit 1; \
	fi; \
	TMPDIR=$$(mktemp -d); \
	unzip -q -o "$$APK" "lib/*" -d "$$TMPDIR" 2>/dev/null; \
	FAIL=0; \
	for so in $$(find "$$TMPDIR/lib" -name "*.so" 2>/dev/null); do \
		MIN_EXP=$$(llvm-objdump -p "$$so" 2>/dev/null | grep 'LOAD.*align' | sed 's/.*align 2\*\*//' | sort -n | head -1); \
		NAME=$$(basename "$$so"); \
		ABI=$$(basename $$(dirname "$$so")); \
		if [ -z "$$MIN_EXP" ]; then \
			echo "  [WARN] $$ABI/$$NAME — no LOAD segments found, skipping"; \
			continue; \
		fi; \
		if [ "$$MIN_EXP" -ge 14 ] 2>/dev/null; then \
			echo "  [OK]   $$ABI/$$NAME — 16KB aligned (2**$$MIN_EXP)"; \
		else \
			echo "  [FAIL] $$ABI/$$NAME — not 16KB aligned (2**$$MIN_EXP)"; \
			FAIL=1; \
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

# ─────────────────────────────────────────────────────────────────────────────
# All-in-One
# ─────────────────────────────────────────────────────────────────────────────

all: clean build lint test-unit ## Run full workflow (clean, build, lint, test-unit)

ci: check-deps lint test-unit coverage build-release ## Run CI workflow
