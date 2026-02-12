#!/usr/bin/env bash
#
# Runs Android integration tests (connectedAndroidTest) using a Docker
# Android emulator container. Same image as E2E tests for consistency.
#
# Requirements: Docker, adb (Android SDK platform-tools), Java 17
# KVM required for emulator hardware acceleration.
#
# In CI (detected via CI=true env var) the container runs in headless
# mode without VNC/display stack to minimise resource usage.
# Locally, VNC is enabled on port 6080 for visual debugging.
#
# Usage:
#   bash scripts/run-integration-tests.sh
#   make test-integration
#
set -euo pipefail

DOCKER_IMAGE="budtmo/docker-android:emulator_14.0"
CONTAINER_NAME="mcp-integration-test"
ADB_HOST_PORT=5555
NOVNC_HOST_PORT=6080
BOOT_TIMEOUT=300

cleanup() {
    echo "[integration] Cleaning up Docker container..."
    docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
    adb disconnect "localhost:$ADB_HOST_PORT" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Remove any leftover container from a previous run
docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true

# Build docker run arguments depending on environment
DOCKER_ARGS=(
    -d --name "$CONTAINER_NAME"
    --privileged
    -p "$ADB_HOST_PORT":5555
    -e EMULATOR_DEVICE="Nexus 5"
    -e DATAPARTITION_SIZE=4096
    -e USER_BEHAVIOR_ANALYTICS=false
)

# Emulator performance flags (always applied)
EMULATOR_EXTRA_ARGS="-no-boot-anim -no-audio -no-snapshot"

if [ "${CI:-false}" = "true" ]; then
    echo "[integration] CI detected — headless mode, no VNC"
    DOCKER_ARGS+=(
        -e EMULATOR_HEADLESS=true
        -e WEB_VNC=false
        -e "EMULATOR_ADDITIONAL_ARGS=${EMULATOR_EXTRA_ARGS} -no-window"
        --memory=4g
    )
else
    echo "[integration] Local mode — VNC enabled on http://localhost:$NOVNC_HOST_PORT"
    DOCKER_ARGS+=(
        -p "$NOVNC_HOST_PORT":6080
        -e WEB_VNC=true
        -e "EMULATOR_ADDITIONAL_ARGS=${EMULATOR_EXTRA_ARGS}"
        --memory=6g
    )
fi

echo "[integration] Starting Docker Android container ($DOCKER_IMAGE)..."
echo "[integration]   adb port: localhost:$ADB_HOST_PORT"
docker run "${DOCKER_ARGS[@]}" "$DOCKER_IMAGE"

echo "[integration] Waiting for emulator boot (timeout ${BOOT_TIMEOUT}s)..."
SECONDS=0
timeout "$BOOT_TIMEOUT" bash -c "
    while true; do
        adb connect localhost:$ADB_HOST_PORT >/dev/null 2>&1 || true
        BOOT=\$(adb -s localhost:$ADB_HOST_PORT shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
        if [ \"\$BOOT\" = \"1\" ]; then
            break
        fi
        sleep 2
    done
"
echo "[integration] Emulator booted in ${SECONDS}s."

echo "[integration] Disabling animations..."
adb -s "localhost:$ADB_HOST_PORT" shell settings put global window_animation_scale 0
adb -s "localhost:$ADB_HOST_PORT" shell settings put global transition_animation_scale 0
adb -s "localhost:$ADB_HOST_PORT" shell settings put global animator_duration_scale 0

echo "[integration] Running connectedAndroidTest..."
./gradlew :app:connectedAndroidTest

echo "[integration] Integration tests passed."
