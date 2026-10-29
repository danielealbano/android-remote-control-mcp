# Android Remote Control MCP

[![CI](https://github.com/danielealbano/android-remote-control-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/danielealbano/android-remote-control-mcp/actions/workflows/ci.yml)
[![License: GPL v3 or later](https://img.shields.io/badge/License-GPLv3%2B-blue.svg)](LICENSE.md)

An Android application that runs as an **MCP (Model Context Protocol) server**, enabling AI models to **fully control an Android device** remotely using accessibility services and screenshot capture.

The app runs directly on your Android device (or emulator) and exposes an HTTP server (with optional HTTPS) implementing the MCP protocol. AI models like Claude can connect to it and interact with any app on the device — reading UI elements, tapping buttons, typing text, swiping, capturing screenshots, managing files, launching apps, and more.

> **Warning:** This software is provided "as-is" without warranty of any kind, for **research and educational purposes only**. The authors do not condone the use of this tool for any illegal, unauthorized, or unethical activities. Users are solely responsible for ensuring their use complies with all applicable laws and regulations. By using this software, you agree to use it responsibly and at your own risk.

---

## Contents

- [Demo](#demo)
- [Features](#features)
- [Install](#install)
- [Setup](#setup)
- [Privacy Mode](#privacy-mode)
- [Integrations](#integrations)
- [Configuration](#configuration)
- [Permissions Reference](#permissions-reference)
- [Security](#security)
- [Contributing](#contributing)
- [License](#license)

---

## Demo

<table>
  <tr>
    <td align="center" width="33%" valign="top">
      <a href="docs/assets/demo-reddit-post.gif" title="Click for full resolution"><img src="docs/assets/demo-reddit-post-small.gif" alt="Posting on Reddit through the MCP server" width="240"></a>
      <br><em>Posting on Reddit (sped up, ~6 min original)</em>
    </td>
    <td align="center" width="33%" valign="top">
      <a href="docs/assets/demo-install-booking.gif" title="Click for full resolution"><img src="docs/assets/demo-install-booking-small.gif" alt="Installing the booking.com app through the MCP server" width="240"></a>
      <br><em>Installing the booking.com app</em>
    </td>
    <td align="center" width="33%" valign="top">
      <a href="docs/assets/demo-flight-search.gif" title="Click for full resolution"><img src="docs/assets/demo-flight-search-small.gif" alt="Skyscanner flight search from Zurich to Istanbul through the MCP server" width="240"></a>
      <br><em>Flight search on Skyscanner, Zurich &rarr; Istanbul (sped up, ~4 min original)</em>
    </td>
  </tr>
</table>

<p align="center"><em>An AI model controlling an Android device through the MCP server &mdash; click any clip for full resolution.</em></p>

## Features

### MCP Server
- HTTP server running directly on Android (Ktor + Netty), with optional HTTPS
- Streamable HTTP transport at `/mcp` (MCP specification compliant, JSON-only, no SSE)
- Combined authentication: static bearer token and/or a self-contained OAuth 2.1 server (Claude.ai / Claude Desktop custom connectors, with on-device approval)
- Auto-generated self-signed TLS certificates (or custom certificate upload)
- Configurable binding: localhost (127.0.0.1), network (0.0.0.0) or a specific IP address of the device
- Auto-start on boot
- Remote access tunnels via the free end-to-end encrypted tunnel (no account), Cloudflare Quick Tunnels or ngrok (public HTTPS URL)

### 58 MCP Tools across 14 Categories

Screen introspection, system actions, touch actions, gestures, node actions, text input, utilities, file operations, app management, camera, intents, notifications, location, and sharing.

All tool names use the `android_` prefix by default (e.g., `android_tap`). When a device slug is configured (e.g., `pixel7`), the prefix becomes `android_pixel7_` (e.g., `android_pixel7_tap`).

See [docs/MCP_TOOLS.md](docs/MCP_TOOLS.md) for the full tool reference with input/output schemas and examples.

### Android App
- Material Design 3 UI with tabbed layout (Server / Settings / About) and dark mode
- Server status monitoring (running/stopped) with permission warning banner
- Connection info display (IP, port, token, tunnel URL)
- Per-tool and per-parameter permissions (enable/disable individual MCP tools)
- Permission management (Accessibility, Notifications, Camera, Microphone)
- Remote access tunnel configuration (free E2E tunnel / Cloudflare / ngrok)
- Storage location management (automatic locations + SAF authorization for file tools)
- Server log viewer (MCP tool calls, tunnel events)
- Headless setup via ADB (configure, grant permissions, start/stop server without UI)

### Comparison with Alternatives

| Feature | This project | [mobile-mcp] | [Android-MCP] | [android-mcp-server] | [adb-mcp] | [droidrun-mcp] |
|---------|:-:|:-:|:-:|:-:|:-:|:-:|
| MCP tools | 58 | 21 | 11 | 5 | 10 | 11 |
| Runs on the phone (no ADB) | :white_check_mark: | :x: | :x: | :x: | :x: | :x: |
| Action latency | 10-100 ms | 1-4 s | 1-4 s | 1-4 s | 1-4 s | 1-4 s |
| Works over the internet | :white_check_mark: | :x: | :x: | :x: | :x: | :x: |
| Token-efficient screen state | :white_check_mark: | :x: | :white_check_mark: | :x: | :x: | :white_check_mark: |
| Annotated screenshots | :white_check_mark: | :x: | :white_check_mark: | :x: | :x: | :x: |
| Configurable screenshot resolution/quality | :white_check_mark: | :x: | :x: | :x: | :x: | :x: |
| Per-tool enable/disable | :white_check_mark: | :x: | :x: | :x: | :x: | :x: |
| Multi-device support | :white_check_mark: | :white_check_mark: | :x: | :x: | :x: | :x: |
| Camera, clipboard, files, downloads | :white_check_mark: | :x: | :x: | :x: | :x: | :x: |
| iOS support | :x: | :white_check_mark: | :x: | :x: | :x: | :x: |

[mobile-mcp]: https://github.com/mobile-next/mobile-mcp
[Android-MCP]: https://github.com/CursorTouch/Android-MCP
[android-mcp-server]: https://github.com/minhalvp/android-mcp-server
[adb-mcp]: https://github.com/srmorete/adb-mcp
[droidrun-mcp]: https://github.com/chukfinley/droidrun-mcp-server

Most alternatives rely on ADB running on a host machine, which means a USB cable or local network connection and a computer sitting next to the phone. This project runs entirely on the device itself, so you can expose the MCP endpoint through a tunnel and control your phone from anywhere.

On the token efficiency side, ADB-based tools typically return raw `uiautomator` XML dumps which can easily be 10-50x more verbose than the compact representation used here. Combined with numbered screenshot annotations, configurable image quality, and the ability to disable tools you don't need (every tool definition costs tokens on every turn), this significantly reduces the per-interaction cost in agentic loops.

---

## Install

### Which build to download: GMS or FOSS

Each release provides two flavors of the APK — pick the one that matches your device:

- **GMS** (`…-gms-release.apk`) — the full build. **Requires Google Mobile Services (Google Play Services)** installed on the phone. Choose this on standard devices/emulators that ship with Google services.
- **FOSS** (`…-foss-release.apk`) — a Google-free build with **no dependency on Google Mobile Services**. Works on **any** Android phone, including de-Googled ROMs and devices where Google services are unavailable.

If unsure, the GMS build is the right choice for a typical phone with the Play Store.

### Option A: Download on your phone (easiest)

1. Open the [Releases](https://github.com/danielealbano/android-remote-control-mcp/releases) page on your phone's browser
2. Download the APK from the latest release
3. Open the downloaded APK and follow the prompts to install it (you may need to allow installation from unknown sources)

### Option B: Download on your PC and install via ADB

1. Download the APK from the [Releases](https://github.com/danielealbano/android-remote-control-mcp/releases) page
2. Connect your phone via USB (with USB Debugging enabled)
3. Install the APK (use the GMS or FOSS file you downloaded):
```bash
adb install android-remote-control-mcp-<version>-gms-release.apk
```

### Option C: Build from sources

```bash
git clone https://github.com/danielealbano/android-remote-control-mcp.git
cd android-remote-control-mcp
make build
make install  # installs on connected device/emulator
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for full build requirements and instructions.

---

## Setup

1. **Open the app.** The Server tab shows a permission warning banner; grant permissions from there or via Settings > Permissions.

2. **Grant permissions:**

   - **Accessibility Service** — **required.** Powers UI introspection, action execution, and screenshots; nothing works without it.
     - **If the toggle is greyed out** (Android 13+, common on Samsung/One UI for apps installed from an APK): this is Android's "Restricted settings" protection. A dialog reads *"Restricted setting — For your security, this setting is currently unavailable."* To unblock it, go to **Settings → Apps → Android Remote Control MCP → App info**, tap the **⋮ menu** (top-right), choose **Allow restricted settings**, confirm with your PIN/pattern/biometric, then enable the Accessibility Service. (Play Store / F-Droid installs are not affected.)
   - **Notifications** — **optional** (recommended for OAuth). OAuth sign-in approvals (Claude.ai / Claude Desktop, ChatGPT, or any third-party OAuth client) arrive as heads-up notifications you tap to confirm the 2-digit code and approve. It is **no longer required** for OAuth login, though: if you don't grant this permission, pending requests still appear as a **card on the app's Server screen** that you tap to review and approve/deny. The **Event Channel** push feature — e.g. pushing device events to Claude Code — runs as a foreground service that posts its status notification through this permission on Android 13+.
   - **Other permissions** (optional) — Camera, Microphone, and Location enable their corresponding MCP tools. The app works without them; only the dependent features are disabled until granted. See the [Permissions Reference](#permissions-reference) below for the complete list.
   - **Storage locations** — configure in Settings > Storage if you plan to use the file tools (automatic locations like Downloads, plus custom locations via SAF).

3. **Start the server.** Go back to the **Server tab** and tap **Start**.

The server starts on `http://127.0.0.1:8080` by default. The connection info (IP, port, token, URL) is displayed on the Server tab.

> **Note**: `127.0.0.1` refers to the phone's localhost, not your computer. To connect from your computer, use [adb port forwarding](#using-with-adb-port-forwarding), bind to `0.0.0.0` (network mode), or enable a [remote access tunnel](#using-remote-access-tunnels).

---

## Privacy Mode

Privacy Mode detects and hides personal data — emails, credit cards and IBANs, credentials, phone
numbers, national IDs, addresses, and names — in everything the MCP tools return (screen content,
notifications, clipboard, files, …) **before it leaves the device** for the AI provider. Detection
runs fully on-device: fast rule-based checks (checksums, formats, field context) combined with a
local NER model — no cloud calls. Detected values are either pseudonymized with consistent
placeholders (`EMAIL#a1b2c`, so AI tools keep working across screens) or fully redacted. Detection
is best-effort mitigation, not a guarantee.

The model is the [Ai4Privacy multilingual anonymiser](https://huggingface.co/ai4privacy/llama-ai4privacy-multilingual-categorical-anonymiser-openpii)
(MIT license, Built with Llama), downloaded once on first enable (~150 MB) directly from the pinned
Hugging Face revision and checksum-verified on device.

### Measured detection rates

Share of personal data items detected per category by the full pipeline, measured with the in-repo
effectiveness benchmark (`make privacy-benchmark`) on realistic UI-style content across 8 languages:

| Category | Detected |
|---|---|
| Addresses | 77.9% |
| Cards & IBANs | 75.3% |
| Credentials | 90.9% |
| Emails | 100.0% |
| Names | 0.8% |
| National IDs | 93.0% |
| Phone numbers | 84.4% |

Names are the on-device model's known weak spot (it recognizes common Western names far better than
the globally diverse names in the benchmark); improving name detection with a fine-tuned model is on
the roadmap. Actual results vary with content and language.

### Enabling Privacy Mode

- **From the main Server screen**: tap **Set up Privacy Mode** on the Privacy Mode card (shown while
  the mode is off).
- **Or via Settings → Privacy**: turn on **Enable Privacy Mode** and confirm the one-time model
  download. After the download and warm-up, a short on-device benchmark measures the expected
  per-screen overhead and shows it on the same screen.
- Protected categories, pseudonymize-vs-redact, and the placeholder format are configurable in
  **Settings → Privacy**.

---

## Integrations

### Claude Desktop / Claude Code

Add the server to your `.mcp.json` configuration file:

```json
{
  "mcpServers": {
    "android-phone": {
      "type": "http",
      "url": "http://DEVICE_IP:PORT/mcp",
      "headers": {
        "Authorization": "Bearer YOUR_TOKEN"
      }
    }
  }
}
```

Replace `DEVICE_IP`, `PORT`, and `YOUR_TOKEN` with the values shown in the app's Server tab. If the server is bound to localhost (default), you'll need [adb port forwarding](#using-with-adb-port-forwarding) or a [remote access tunnel](#using-remote-access-tunnels) to connect.

> **Note for clients without custom-header support:** if your MCP client cannot send an `Authorization` header and does not support OAuth, you can turn off both auth methods in the app (**Settings → Access** — disable Bearer token and OAuth). The app warns and asks you to confirm, because the server is then **open**: anyone who can reach it has full control. Only do this on a network you trust.

### Connect from Claude.ai & Claude Desktop (Custom Connector, OAuth)

Claude.ai (web) and Claude Desktop connect as a **custom connector** (remote MCP) using OAuth 2.1 — the app is its own OAuth Authorization Server, so no external account or pre-registration is needed. This requires the server to be reachable over a **public HTTPS URL**, so you must first enable a [remote access tunnel](#using-remote-access-tunnels) — a `localhost`/LAN address or `adb` port-forward will **not** work.

1. **OAuth is enabled by default** — no action needed (if you previously disabled it, re-enable it under **Settings → Access**). The bearer token stays enabled too; both are accepted.
2. Open **Settings → Tunnel**, enable **Remote Access** (the default free E2E tunnel needs no account; the first setup takes about 10–30 seconds), and start the server. Copy the public `https://…` URL from the Server tab and append `/mcp` (e.g. `https://<name>.d.arc-tunnel.io/mcp`).
3. In Claude, open **[Customize → Connectors → Add custom connector](https://claude.ai/customize/connectors?modal=add-custom-connector)**, paste the `https://…/mcp` URL, and **leave the OAuth Client ID and Client Secret blank** (the app uses Dynamic Client Registration). Click **Add**.
4. Claude opens a browser approval page showing a **2-digit code**. On the device, tap the heads-up notification — or, if the Notifications permission is off, open the app's **Server** screen and tap the **pending approvals** card — to open the approval screen, confirm the code matches, and **Approve**.
5. Manage or revoke connected clients any time under **Settings → Access → Connected clients**. Revoking immediately invalidates that client's tokens.

> **Public URL override (optional):** if your tunnel/host topology needs a fixed public host (or you bind to `0.0.0.0` without a trusted proxy), set a **Public URL override** in Settings → Access so OAuth metadata and links use a stable host.

Custom connectors are available on the Free (1 connector), Pro, Max, Team, and Enterprise plans (currently in beta).

> ⚠️ **Security:** treat the public tunnel URL as sensitive, approve only connections you initiated (verify the 2-digit code), revoke clients you no longer use, and stop the tunnel and server when you are done. Never point it at a device holding sensitive data.

### Connect from ChatGPT (Custom Connector, OAuth)

ChatGPT connects to the server as a **custom MCP connector** using the same self-contained OAuth 2.1 flow. As with Claude, the server must be reachable over a **public HTTPS URL**, so enable a [remote access tunnel](#using-remote-access-tunnels) first — a `localhost`/LAN address or `adb` port-forward will **not** work. Custom connectors require a paid plan (Plus, Pro, Business, Enterprise, or Edu — not Free) and are set up from the **ChatGPT web app**.

1. **Enable Developer mode** — in ChatGPT on the web, open **Settings → Connectors → Advanced settings** (labelled **Apps & Connectors** on some builds) and turn on **Developer mode**.
2. **Start the tunnel** — same as the Claude flow above: OAuth stays enabled by default, turn on **Remote Access** under Settings → Tunnel, start the server, and copy the public `https://…/mcp` URL from the Server tab.
3. **Add the connector** — open **Settings → Connectors → Create**, give it a name and description, paste the `https://…/mcp` URL, select **OAuth** as the authentication method, and leave any Client ID / Client Secret blank (the app self-registers clients via Dynamic Client Registration).
4. **Approve on the device** — ChatGPT starts the OAuth flow. Tap the heads-up notification — or, if the Notifications permission is off, open the app's **Server** screen and tap the **pending approvals** card — confirm the 2-digit code matches, and **Approve** (identical to the Claude approval step).
5. Manage or revoke the connected client any time under **Settings → Access → Connected clients** in the app.

> ⚠️ **Security:** the same cautions apply — treat the tunnel URL as sensitive, approve only connections you initiated (verify the code), revoke unused clients, and stop the tunnel and server when you are done.

### Other MCP Clients

The MCP server exposes a standard Streamable HTTP endpoint at `/mcp` with combined authentication (static bearer token and/or OAuth 2.1). Any MCP-compatible client can connect to it — refer to your client's documentation for the specific configuration format.

### Testing with MCP Inspector

[MCP Inspector](https://github.com/modelcontextprotocol/inspector) is the official visual testing tool for MCP servers. It provides a browser-based UI to connect, list tools, fill parameters, and see responses:

```bash
npx @modelcontextprotocol/inspector
```

This opens a UI at `http://localhost:6274` where you can connect to your server and test tools interactively.

### Testing with curl

All requests are sent as JSON-RPC 2.0 via `POST /mcp` (Streamable HTTP transport). The server is stateless: every request stands alone, so there is no session to open and no `mcp-session-id` to send, and tools can be called directly:

```bash
# List available tools
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# Get the current screen state (UI nodes + optional screenshot)
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"android_get_screen_state","arguments":{}}}'

# Tap at coordinates
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"android_tap","arguments":{"x":540,"y":1200}}}'
```

The bearer token is shown in the app's connection info and can be copied directly. Bearer authentication is controlled by the **Bearer token** toggle in **Settings → Access** (not by clearing the value): with it enabled, every `/mcp` request must present the token (or a valid OAuth access token). The server accepts unauthenticated requests only when **both** the Bearer token and OAuth are disabled.

---

## Configuration

### Server Settings (via App UI)

| Setting | Default | Description |
|---------|---------|-------------|
| Port | `8080` | HTTP/HTTPS server port |
| Binding Address | `127.0.0.1` | `127.0.0.1` (localhost, use with adb port forwarding), `0.0.0.0` (network, all interfaces) or a specific IP address of the device (IPv4 or IPv6, not IPv6 link-local, without a zone index such as `%3`; the server does not start while the device does not have it) |
| Bearer Token | Enabled, auto-generated UUID | Static token for MCP requests (Settings → Access). Enforcement is set by the Bearer toggle, not by clearing the value. |
| OAuth | Enabled | Self-contained OAuth 2.1 server for Claude.ai / Claude Desktop custom connectors (Settings → Access). |
| Public URL override | Empty (auto-detect) | Pin the public host used for OAuth metadata and share links. |
| HTTPS | Disabled | Enable HTTPS with auto-generated self-signed certificate (configurable hostname) or upload custom .p12/.pfx |
| Auto-start on Boot | Disabled | Start MCP server automatically when device boots |
| Device Slug | Empty | Optional device identifier for tool name prefix (e.g., `pixel7` makes tools `android_pixel7_tap`) |
| Remote Access Tunnel | Disabled | Expose server via public HTTPS URL (free E2E tunnel by default; Cloudflare Quick Tunnels or ngrok) |
| Tunnel Local Port | The server port on installs from before this setting (unless their HTTPS kept their Cloudflare/ngrok tunnel off), a random port of 61000-65535 otherwise | The port Cloudflare and ngrok forward to (`http://localhost:<port>`). When the server serves plain HTTP on `127.0.0.1` on that same port (Localhost, Network, or the Specific IP 127.0.0.1), the tunnel uses it; otherwise the server opens a plain HTTP listener of its own on `127.0.0.1:<port>`, with the same authentication. With HTTPS on and such a binding, choose a port other than the server port |
| Tool Permissions | All enabled | Per-tool and per-parameter enable/disable (Settings > MCP Tools) |
| File Size Limit | 50 MB | Maximum file size for file operations (range 1-500 MB) |
| Allow HTTP Downloads | Disabled | Allow non-HTTPS downloads via `android_download_from_url` |
| Allow Unverified HTTPS | Disabled | Accept unverified (self-signed or invalid) HTTPS certificates for `android_download_from_url` (less secure) |
| Block Local Network Downloads | Disabled | `android_download_from_url` refuses to connect to this phone, the local network or VPN addresses (loopback, unspecified, private LAN, link-local, CGNAT/Tailscale and IPv6 unique-local addresses, including their IPv4-mapped/compatible IPv6 forms) |
| Download Timeout | 60 seconds | Timeout for file downloads (range 10-300 seconds) |
| Screen-awake timeout | 0 (disabled) | Keep an already unlocked screen awake during MCP calls and for 60-172800 seconds after the last call. Read at server start; editing is disabled while running. |

### Using with adb Port Forwarding

When the server is bound to `127.0.0.1` (default, most secure):

```bash
# Forward device port to host
adb forward tcp:8080 tcp:8080

# Test connection from host
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"ping"}'
```

### Using over Network

When the server is bound to `0.0.0.0`:

1. Find the device's IP address (shown in the app's connection info)
2. Connect directly via `POST http://DEVICE_IP:8080/mcp` with bearer token

**Warning**: Binding to `0.0.0.0` exposes the server to all devices on the same network. Only use on trusted private networks.

To listen on one address only (the device's address on a VPN such as Tailscale, for example), choose **Specific IP** and enter an IP address of the device: the server listens on that address alone (plus `127.0.0.1` when a tunnel is enabled, for the tunnel), so normally only the network of that address reaches it, and does not start while the device does not have it.

### Using Remote Access Tunnels

For connecting from outside the local network without port forwarding:

1. **Free E2E tunnel** (default, no account required): end-to-end encrypted. The phone enrolls with hardware key attestation, gets a name under `d.arc-tunnel.io` and a public certificate (Let's Encrypt, Google Trust Services or ZeroSSL) issued to its own key, and terminates TLS itself — the tunnel service only relays ciphertext (unlike Cloudflare or ngrok, which read the traffic). The refresh icon next to the URL re-enrolls under a new name. A Certificate Transparency check (on by default) warns if a certificate for your name is ever issued to another key. Self-hosters change the base address.
2. **Cloudflare Quick Tunnels** (no account required): Creates a temporary tunnel with a random `*.trycloudflare.com` HTTPS URL.
3. **ngrok** (account required): Supports optional custom domains. Requires an ngrok authtoken (free tier available). Available on arm64-v8a and x86_64 devices.

Cloudflare and ngrok forward to `http://localhost:<tunnel local port>` (Settings → Tunnel), so they also work while the server serves HTTPS: the server then gives them a plain HTTP listener of its own on `127.0.0.1`.

Enable the tunnel in the app's "Remote Access" section. The public URL is displayed in the connection info and server logs.

### Headless Setup via ADB

The app can be fully configured and controlled from the command line without opening the UI. This is useful for automated setups, CI pipelines, or headless devices.

Replace `<app-id>` with the application ID for your build:
- **Debug (gms flavor)**: `com.danielealbano.androidremotecontrolmcp.gms.debug`
- **Debug (foss flavor)**: `com.danielealbano.androidremotecontrolmcp.foss.debug`
- **Release (both flavors)**: `com.danielealbano.androidremotecontrolmcp`

#### Grant Permissions

```bash
# Enable Accessibility Service (required for UI introspection, actions, and screenshots)
adb shell settings put secure enabled_accessibility_services \
  <app-id>/com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService

# Enable Notification Listener Service (required for notification tools)
adb shell cmd notification allow_listener \
  <app-id>/com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService

# Grant notification permission (Android 13+)
adb shell pm grant <app-id> android.permission.POST_NOTIFICATIONS

# Grant camera permission
adb shell pm grant <app-id> android.permission.CAMERA

# Grant microphone permission
adb shell pm grant <app-id> android.permission.RECORD_AUDIO

# Grant location permissions
adb shell pm grant <app-id> android.permission.ACCESS_FINE_LOCATION
adb shell pm grant <app-id> android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant <app-id> android.permission.ACCESS_BACKGROUND_LOCATION

# Grant nearby WiFi devices permission (Android 13+)
adb shell pm grant <app-id> android.permission.NEARBY_WIFI_DEVICES

# Grant media read permissions (Android 13+)
adb shell pm grant <app-id> android.permission.READ_MEDIA_IMAGES
adb shell pm grant <app-id> android.permission.READ_MEDIA_VIDEO
adb shell pm grant <app-id> android.permission.READ_MEDIA_AUDIO
```

See [docs/PERMISSIONS.md](docs/PERMISSIONS.md) for the full reference and a copy-paste script that grants everything in one go.

#### Configure the App

All extras are optional — only the ones provided are updated. The app does **not** need to be open.

> **Requires adb.** The configuration receiver and the service trampoline are gated on
> `android.permission.DUMP`, which the adb shell UID holds but ordinary apps cannot obtain. The
> commands below work unchanged from `adb shell`; an app on the device cannot use them to
> reconfigure the server. See the [Security](#security) section.

```bash
adb shell am broadcast \
  -a com.danielealbano.androidremotecontrolmcp.ADB_CONFIGURE \
  -n <app-id>/com.danielealbano.androidremotecontrolmcp.services.mcp.AdbConfigReceiver \
  --es bearer_token "my-secret-token" \
  --es binding_address "0.0.0.0" \
  --ei port 8080 \
  --ez auto_start_on_boot true \
  --ez hide_from_recents false \
  --ez https_enabled false \
  --es certificate_source "AUTO_GENERATED" \
  --es certificate_hostname "mcp.local" \
  --ez tunnel_enabled true \
  --es tunnel_provider "android-remote-control-mcp-tunneld" \
  --es arc_tunnel_base_address "arc-tunnel.io" \
  --ez arc_tunnel_ct_check_enabled true \
  --es cloudflare_tunnel_mode "TOKEN" \
  --es cloudflare_tunnel_token "your-cloudflare-tunnel-token" \
  --es cloudflare_tunnel_extra_args "--edge region1.v2.argotunnel.com:7844" \
  --es ngrok_authtoken "your-ngrok-token" \
  --es ngrok_domain "your-domain.ngrok-free.app" \
  --ei tunnel_local_port 61200 \
  --ei file_size_limit_mb 50 \
  --ez allow_http_downloads false \
  --ez allow_unverified_https_certs false \
  --ez block_local_network_downloads false \
  --ei download_timeout_seconds 60 \
  --es device_slug "pixel8" \
  --es tool_permissions '{"disabled_tools":["tap"],"disabled_params":{"swipe":["duration_ms"]}}'
```

```bash
# Disable bearer authentication (use only on trusted networks; the server is open
# only if OAuth is also disabled — see the Security section)
adb shell am broadcast \
  -a com.danielealbano.androidremotecontrolmcp.ADB_CONFIGURE \
  -n <app-id>/com.danielealbano.androidremotecontrolmcp.services.mcp.AdbConfigReceiver \
  --ez bearer_token_enabled false
```

Bearer enforcement is controlled by `--ez bearer_token_enabled <bool>`, NOT by clearing the value. Clearing the value (`--es bearer_token ""`) while `bearer_token_enabled=true` makes `/mcp` fail CLOSED (401) — it does NOT open the server. The server accepts unauthenticated requests only when BOTH `bearer_token_enabled` and `oauth_enabled` are false.

| Extra | Type | Description |
|-------|------|-------------|
| `bearer_token` | string | Static bearer-token value (clearing it while `bearer_token_enabled=true` fails closed, it does NOT disable auth) |
| `bearer_token_enabled` | boolean | Enable/disable bearer-token authentication (controls enforcement, independent of the value) |
| `oauth_enabled` | boolean | Enable/disable the self-contained OAuth 2.1 server (Claude.ai / Claude Desktop connectors) |
| `public_url_override` | string | Pin the public host used for OAuth metadata and share links (empty = auto-detect from the request) |
| `binding_address` | string | `127.0.0.1` (localhost), `0.0.0.0` (network) or another IP address literal of the device (specific IP) |
| `port` | int | HTTP/HTTPS server port (1-65535) |
| `auto_start_on_boot` | boolean | Start MCP server when device boots |
| `hide_from_recents` | boolean | Exclude the app from the Android Recent Tasks list |
| `https_enabled` | boolean | Enable HTTPS with TLS |
| `certificate_source` | string | `AUTO_GENERATED` or `CUSTOM` |
| `certificate_hostname` | string | Hostname for auto-generated certificate |
| `tunnel_enabled` | boolean | Enable remote access tunnel |
| `tunnel_provider` | string | `android-remote-control-mcp-tunneld` (free E2E tunnel), `CLOUDFLARE` or `NGROK` |
| `arc_tunnel_base_address` | string | Base address of the free E2E tunnel service (default `arc-tunnel.io`; hosts `enroll.`, `connect.` and `d.` are derived) |
| `arc_tunnel_ct_check_enabled` | boolean | Certificate Transparency check for the free E2E tunnel (default `true`) |
| `arc_tunnel_enroll` | boolean | `true` enrolls the free E2E tunnel (or resumes a failed certificate issuance) with the settings in the same broadcast, while the app is visible and the server is stopped; headless, use the trampoline action `enroll-tunnel` below. The public URL appears in the Server tab |
| `cloudflare_tunnel_mode` | string | `FREE` (Quick Tunnel, random `*.trycloudflare.com` URL) or `TOKEN` (named tunnel with static hostname) |
| `cloudflare_tunnel_token` | string | Cloudflare named-tunnel token (used when `cloudflare_tunnel_mode` is `TOKEN`) |
| `cloudflare_tunnel_extra_args` | string | Optional extra arguments passed to cloudflared (e.g. `--edge region1.v2.argotunnel.com:7844`) |
| `ngrok_authtoken` | string | ngrok authentication token |
| `ngrok_domain` | string | ngrok custom domain (optional) |
| `tunnel_local_port` | int | Port Cloudflare and ngrok forward to on `localhost` (1-65535) |
| `file_size_limit_mb` | int | Max file size for file operations (1-500) |
| `allow_http_downloads` | boolean | Allow non-HTTPS downloads |
| `allow_unverified_https_certs` | boolean | Allow unverified HTTPS certificates for downloads |
| `block_local_network_downloads` | boolean | Refuse downloads from this phone, the local network and VPN addresses |
| `download_timeout_seconds` | int | Download timeout (10-300) |
| `screen_awake_timeout_seconds` | int | Keep an unlocked screen awake during MCP calls and for this many seconds afterward. Default 0 (disabled); enabled range 60-172800. Requires server restart. |
| `device_slug` | string | Device identifier for tool name prefix |
| `tool_permissions` | string (JSON) | Per-tool and per-parameter permissions: `{"disabled_tools":["tool_name"],"disabled_params":{"tool_name":["param"]}}` |
| `storage_location_id` | string | ID of an existing storage location (from `android_list_storage_locations` or Settings > Storage); required by the two extras below, unknown IDs are ignored |
| `storage_allow_write` | boolean | Allow write operations on the location identified by `storage_location_id` |
| `storage_allow_delete` | boolean | Allow delete operations on the location identified by `storage_location_id` |

#### Start the MCP Server

The server must be started via a trampoline Activity (required on Android 12+ to gain foreground service exemption). This works even when the app is force-stopped.

```bash
adb shell am start \
  -n <app-id>/com.danielealbano.androidremotecontrolmcp.services.mcp.AdbServiceTrampolineActivity \
  --es action start
```

#### Stop the MCP Server

```bash
adb shell am start \
  -n <app-id>/com.danielealbano.androidremotecontrolmcp.services.mcp.AdbServiceTrampolineActivity \
  --es action stop
```

#### Enroll the Free E2E Tunnel

Enrollment (getting the device's name and certificate) runs in a short foreground service. Headless, use the same trampoline Activity as for starting the server; it also resumes a failed certificate issuance. Run it while the server is stopped.

```bash
adb shell am start \
  -n <app-id>/com.danielealbano.androidremotecontrolmcp.services.mcp.AdbServiceTrampolineActivity \
  --es action enroll-tunnel
```

`--ez arc_tunnel_enroll true` on the configuration broadcast does the same when the app is visible; from the background Android 12+ refuses the foreground service start and the app reports "background start refused".

---

## Permissions Reference

The app declares the permissions below. **Normal** permissions are granted automatically at install. **Runtime** permissions and **Special access** require explicit user action (via the app's Settings > Permissions tab, or programmatically — see [Granting Permissions Programmatically](docs/PERMISSIONS.md)). All runtime permissions are optional: the app works without them, but the features that depend on them are disabled until granted.

| Permission | Type | Used for |
|------------|------|----------|
| `INTERNET` | Normal | HTTP/HTTPS server and remote access tunnels |
| `ACCESS_NETWORK_STATE` | Normal | Detect network connectivity |
| `FOREGROUND_SERVICE` | Normal | Run the MCP server as a foreground service |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Normal | Foreground service type for the MCP server |
| `FOREGROUND_SERVICE_LOCATION` | Normal | Foreground service type for the Event Channel (geofence/WiFi) |
| `FOREGROUND_SERVICE_DATA_SYNC` | Normal | Foreground service type for the free E2E tunnel enrollment (name + certificate) |
| `RECEIVE_BOOT_COMPLETED` | Normal | Auto-start the server on device boot |
| `QUERY_ALL_PACKAGES` | Normal | Enumerate installed apps for app-management tools |
| `KILL_BACKGROUND_PROCESSES` | Normal | Stop background apps via app-management tools |
| `ACCESS_WIFI_STATE` | Normal | Read WiFi state for the Event Channel |
| `CHANGE_WIFI_STATE` | Normal | Manage WiFi for the Event Channel |
| `POST_NOTIFICATIONS` | Runtime | Show the foreground service notification (Android 13+) |
| `CAMERA` | Runtime | Camera photo/video MCP tools |
| `RECORD_AUDIO` | Runtime | Audio capture for camera video tools |
| `ACCESS_FINE_LOCATION` | Runtime | Location tools and geofence events |
| `ACCESS_COARSE_LOCATION` | Runtime | Approximate location |
| `ACCESS_BACKGROUND_LOCATION` | Runtime | Location/geofence events while the app is in the background |
| `NEARBY_WIFI_DEVICES` | Runtime | WiFi scanning for the Event Channel (Android 13+) |
| `READ_MEDIA_IMAGES` | Runtime | "All files" mode for built-in image storage locations (Android 13+) |
| `READ_MEDIA_VIDEO` | Runtime | "All files" mode for built-in video storage locations (Android 13+) |
| `READ_MEDIA_AUDIO` | Runtime | "All files" mode for built-in audio storage locations (Android 13+) |
| Accessibility Service | Special access | UI introspection, action execution, and screenshots — **required** for core functionality |
| Notification Listener | Special access | Reading and managing notifications via notification tools |

---

## Security

### No Root Required

The application runs entirely within Android's standard permission model. No root access, no unlocked bootloader, no custom ROM required.

### Authentication

Authentication is combined (dual-accept): a `/mcp` request is authorized by a valid static bearer token OR a valid issued OAuth access token. Enforcement is controlled by two independent toggles in **Settings → Access** — `bearer_token_enabled` (default on) and `oauth_enabled` (default on) — not by the token value. The bearer token is auto-generated once on first launch (UUID, preserved across app upgrades) and can be viewed, copied, or regenerated in the app. Clearing the value while bearer is enabled makes the server fail CLOSED (401). The server accepts unauthenticated requests only when BOTH toggles are off (the app shows a warning and a confirmation before that happens) — see the security note in the Connect section.

### Network Security

- **Default binding `127.0.0.1`**: Only accessible via adb port forwarding or tunnels (most secure)
- **Optional binding `0.0.0.0`**: Accessible over network (use only on trusted networks; security warning displayed when enabling)
- **Optional specific IP**: Listens on that address only (plus `127.0.0.1` for an enabled tunnel), so normally only the network of that address reaches it (same security warning)
- **HTTPS**: Optional, disabled by default. When enabled, uses auto-generated self-signed certificates or upload your own CA-signed certificate. Certificate is stored in app-private storage.

### Permissions

All permissions are managed from the app's Settings > Permissions tab. Every sensitive permission requires explicit user action — nothing is granted silently.

Storage locations are configured in Settings > Storage, which includes automatic locations (e.g., Downloads) and custom locations authorized via the system file picker.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build requirements, testing, architecture, and development conventions.

---

## License

This project is licensed under the GNU General Public License, version 3 or (at your option) any later version,
with an additional permission under GPLv3 section 7 for combining it with the Google Play services client libraries.
See [LICENSE.md](LICENSE.md) for the full text.

Third-party components bundled with the app and their licenses are listed in
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
