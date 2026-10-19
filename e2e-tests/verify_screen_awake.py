"""Standalone Android emulator regression for screen-awake lifecycle.

Run after assembling the GMS debug APK. Requires an already booted disposable emulator:
python e2e-tests/verify_screen_awake.py --adb /path/to/adb --serial emulator-5554 \
    --apk app/build/outputs/apk/gms/debug/app-gms-debug.apk --output /tmp/awake-proof
Uses only the explicitly selected emulator and debug package. Leaves its server stopped,
timeout disabled and visual indicator enabled, and restores accessibility registrations.
"""

import argparse
import concurrent.futures
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.request

BASE = "com.danielealbano.androidremotecontrolmcp"
PACKAGE = BASE + ".gms.debug"
PORT = 18087
TOKEN = "screen-awake-emulator-test"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise ValueError("This test requires an explicit emulator serial; physical devices are unsupported")
    args.output.mkdir(parents=True, exist_ok=True)

    def adb(*command):
        return subprocess.check_output(
            [args.adb, "-s", args.serial, *command], timeout=40,
            text=True, encoding="utf-8", errors="replace",
        ).strip()

    if adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise ValueError("Selected target is not an Android emulator")
    power = adb("shell", "dumpsys", "power")
    policy = adb("shell", "dumpsys", "window", "policy")
    if "mWakefulness=Awake" not in power or not re.search(r"^\s+showing=false\s*$", policy, re.MULTILINE):
        raise ValueError("The disposable emulator must be awake and unlocked before this test")
    old_services = adb("shell", "settings", "get", "secure", "enabled_accessibility_services")
    old_enabled = adb("shell", "settings", "get", "secure", "accessibility_enabled")
    checks = {}
    forwarded = False

    def broadcast(action, *extras, debug=False):
        receiver = ".debug.E2EConfigReceiver" if debug else ".services.mcp.AdbConfigReceiver"
        return adb("shell", "am", "broadcast", "--include-stopped-packages", "-a", BASE + action,
                   "-n", PACKAGE + "/" + BASE + receiver, *extras)

    def configure(seconds):
        broadcast(".ADB_CONFIGURE", "--ei", "screen_awake_timeout_seconds", str(seconds),
                  "--ei", "port", str(PORT), "--es", "bearer_token", TOKEN,
                  "--ez", "bearer_token_enabled", "true", "--ez", "oauth_enabled", "false",
                  "--ez", "https_enabled", "false", "--ez", "tunnel_enabled", "false",
                  "--es", "binding_address", "127.0.0.1")

    def window_present(label):
        windows = adb("shell", "dumpsys", "window", "windows")
        (args.output / (label + "-windows.txt")).write_text(windows, encoding="utf-8")
        return "MCP screen awake" in windows

    def expect_window(present, label, timeout=8):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if window_present(label) == present:
                checks[label] = True
                return
            time.sleep(0.2)
        raise AssertionError(label + ": unexpected keep-awake window state")

    def rpc(method, params):
        request = urllib.request.Request(
            f"http://127.0.0.1:{PORT}/mcp",
            data=json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode(),
            headers={"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                     "Authorization": "Bearer " + TOKEN, "MCP-Protocol-Version": "2025-06-18"},
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            body = response.read().decode()
        if body.startswith("event:") or body.startswith("data:"):
            body = next(line[5:].strip() for line in body.splitlines() if line.startswith("data:"))
        result = json.loads(body)
        if "error" in result:
            raise AssertionError(result["error"])
        return result["result"]

    def call(name, arguments=None):
        result = rpc("tools/call", {"name": "android_" + name, "arguments": arguments or {}})
        if result.get("isError"):
            raise AssertionError(result)
        return result

    def start():
        adb("shell", "am", "start", "-n", PACKAGE + "/" + BASE + ".ui.MainActivity")
        broadcast(".ADB_START_SERVER")
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            try:
                rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                   "clientInfo": {"name": "screen-awake-test", "version": "1"}})
                return
            except (OSError, urllib.error.URLError):
                time.sleep(0.3)
        raise AssertionError("Server failed to start")

    def stop():
        broadcast(".ADB_STOP_SERVER")
        expect_window(False, "server-stop-releases-window")
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=0.5):
                    pass
            except OSError:
                return
            time.sleep(0.2)
        raise AssertionError("Previous server is still listening after stop")

    try:
        installed = adb("install", "-r", str(Path(args.apk).resolve()))
        if "Success" not in installed:
            raise AssertionError(installed)
        adb("shell", "am", "start", "-n", PACKAGE + "/" + BASE + ".ui.MainActivity")
        services = [] if old_services in ("null", "") else old_services.split(":")
        component = PACKAGE + "/" + BASE + ".services.accessibility.McpAccessibilityService"
        if component not in services:
            services.append(component)
        adb("shell", "settings", "put", "secure", "enabled_accessibility_services", ":".join(services))
        adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")
        adb("forward", "--no-rebind", f"tcp:{PORT}", f"tcp:{PORT}")
        forwarded = True
        stop()
        broadcast(".debug.E2E_CONFIGURE", "--ez", "tool_call_indicator_enabled", "false", debug=True)
        time.sleep(0.5)
        configure(0)
        start()
        call("get_screen_state")
        expect_window(False, "disabled-has-no-window")
        configure(60)
        call("get_screen_state")
        expect_window(False, "settings-require-restart")
        stop()
        start()
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
            active = executor.submit(call, "wait_for_node", {"by": "text", "value": "awake-test-absent-node", "timeout": 10000})
            expect_window(True, "window-during-call")
            assert not active.done(), "Window must be observed while the tool is still running"
            active.result(timeout=25)
        expect_window(True, "window-during-grace")
        screen = call("get_screen_state")
        (args.output / "grace-screen-state.json").write_text(json.dumps(screen, indent=2), encoding="utf-8")
        text = "\n".join(item.get("text", "") for item in screen.get("content", []))
        assert text.strip(), "Screen introspection must return real content"
        assert not any("ACCESSIBILITY_OVERLAY" in line and PACKAGE in line for line in text.splitlines())
        checks["no-extra-accessibility-window"] = True
        stop()
        start()
        call("get_screen_state")
        expect_window(True, "expiry-hold-start")
        time.sleep(59)
        expect_window(True, "hold-before-expiry", timeout=1)
        time.sleep(2)
        expect_window(False, "normal-expiry-releases-window")
        stop()
        print(json.dumps({"passed": True, "checks": checks}))
    finally:
        try:
            stop()
            configure(0)
            broadcast(".debug.E2E_CONFIGURE", "--ez", "tool_call_indicator_enabled", "true", debug=True)
        finally:
            for name, value in [("enabled_accessibility_services", old_services), ("accessibility_enabled", old_enabled)]:
                if value == "null":
                    adb("shell", "settings", "delete", "secure", name)
                else:
                    adb("shell", "settings", "put", "secure", name, value)
            if forwarded:
                adb("forward", "--remove", f"tcp:{PORT}")


if __name__ == "__main__":
    main()
