# Granting Permissions Programmatically

This document describes how to grant every permission the app needs from the command line via `adb`, without opening the UI. This is useful for automated setups, CI pipelines, and headless devices.

The commands below require a device or emulator reachable over `adb` where the app is already installed. Granting **special access** (Accessibility, Notification Listener) and some runtime permissions via the command line requires a privileged shell (e.g. an emulator, or a device that allows `pm grant` / `settings put secure` from the adb shell). On a standard production device, grant these through the app's **Settings > Permissions** tab instead.

## Application ID

Replace `<app-id>` with the application ID for your build:

- **Debug**: `com.danielealbano.androidremotecontrolmcp.debug`
- **Release**: `com.danielealbano.androidremotecontrolmcp`

> **Note**: the debug build adds the `.debug` suffix to the **application ID**, but the **class names do not change**. The Accessibility and Notification Listener component names below always use the unsuffixed class package (`com.danielealbano.androidremotecontrolmcp.services.*`), regardless of build type.

## Permission categories

- **Normal** — granted automatically at install. No command needed: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_DATA_SYNC`, `RECEIVE_BOOT_COMPLETED`, `QUERY_ALL_PACKAGES`, `KILL_BACKGROUND_PROCESSES`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`.
- **Runtime** — granted with `pm grant`.
- **Special access** — granted with `settings put secure` / `cmd notification`, **not** `pm grant`.

See the [Permissions Reference](../README.md#permissions-reference) in the README for what each permission is used for.

## Runtime permissions

```bash
# Notifications (Android 13+)
adb shell pm grant <app-id> android.permission.POST_NOTIFICATIONS

# Camera and microphone
adb shell pm grant <app-id> android.permission.CAMERA
adb shell pm grant <app-id> android.permission.RECORD_AUDIO

# Location
adb shell pm grant <app-id> android.permission.ACCESS_FINE_LOCATION
adb shell pm grant <app-id> android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant <app-id> android.permission.ACCESS_BACKGROUND_LOCATION

# Nearby WiFi devices (Android 13+)
adb shell pm grant <app-id> android.permission.NEARBY_WIFI_DEVICES

# Media read access (Android 13+) — enables "all files" mode for built-in storage locations
adb shell pm grant <app-id> android.permission.READ_MEDIA_IMAGES
adb shell pm grant <app-id> android.permission.READ_MEDIA_VIDEO
adb shell pm grant <app-id> android.permission.READ_MEDIA_AUDIO
```

## Special access

### Accessibility Service

Required for UI introspection, action execution, and screenshots (core functionality).

```bash
adb shell settings put secure enabled_accessibility_services \
  <app-id>/com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

### Notification Listener

Required for the notification tools (reading and managing notifications).

```bash
adb shell cmd notification allow_listener \
  <app-id>/com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService
```

If `cmd notification allow_listener` is unavailable on your platform image, use the secure setting instead:

```bash
adb shell settings put secure enabled_notification_listeners \
  <app-id>/com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService
```

## One-shot script

The following script grants every runtime permission and enables both special-access services. Set `APP_ID` to match your installed build.

```bash
#!/usr/bin/env bash
set -euo pipefail

# Set to com.danielealbano.androidremotecontrolmcp for a release build
APP_ID="com.danielealbano.androidremotecontrolmcp.debug"

ACCESSIBILITY_SERVICE="$APP_ID/com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService"
NOTIFICATION_LISTENER="$APP_ID/com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService"

# Runtime permissions
for perm in \
  android.permission.POST_NOTIFICATIONS \
  android.permission.CAMERA \
  android.permission.RECORD_AUDIO \
  android.permission.ACCESS_FINE_LOCATION \
  android.permission.ACCESS_COARSE_LOCATION \
  android.permission.ACCESS_BACKGROUND_LOCATION \
  android.permission.NEARBY_WIFI_DEVICES \
  android.permission.READ_MEDIA_IMAGES \
  android.permission.READ_MEDIA_VIDEO \
  android.permission.READ_MEDIA_AUDIO; do
  adb shell pm grant "$APP_ID" "$perm"
done

# Accessibility service
adb shell settings put secure enabled_accessibility_services "$ACCESSIBILITY_SERVICE"
adb shell settings put secure accessibility_enabled 1

# Notification listener
adb shell cmd notification allow_listener "$NOTIFICATION_LISTENER"

echo "Permissions granted for $APP_ID"
```

## Verifying

```bash
# Confirm which build is installed
adb shell pm list packages | grep androidremotecontrolmcp

# Confirm runtime permission grants
adb shell dumpsys package <app-id> | grep -A40 "runtime permissions"

# Confirm the accessibility service is connected
adb shell dumpsys accessibility | grep McpAccessibilityService
```
