# MCP Tools Reference

This document provides a comprehensive reference for all MCP tools available in the Android Remote Control MCP application. Each tool includes its schema, usage examples, and error handling information.

**Transport**: Streamable HTTP at `/mcp` (JSON-only, no SSE)
**Protocol**: JSON-RPC 2.0
**Authentication**: Bearer token required for all requests (global Application-level plugin)
**Content-Type**: `application/json`

---

## Table of Contents

1. [Overview](#overview)
2. [Tool Naming Convention](#tool-naming-convention)
3. [Common Patterns](#common-patterns)
4. [Error Codes](#error-codes)
5. [Screen Introspection Tools](#1-screen-introspection-tools)
6. [System Action Tools](#2-system-action-tools)
7. [Touch Action Tools](#3-touch-action-tools)
8. [Gesture Tools](#4-gesture-tools)
9. [Element Action Tools](#5-element-action-tools)
10. [Text Input Tools](#6-text-input-tools)
11. [Utility Tools](#7-utility-tools)
12. [File Tools](#8-file-tools)
13. [App Management Tools](#9-app-management-tools)

---

## Overview

The MCP server exposes tools via the JSON-RPC 2.0 protocol. Tools are organized into 9 categories:

| Category | Tools | Plan |
|----------|-------|------|
| Screen Introspection | `android_get_screen_state` | 7, 15 |
| System Actions | `android_press_back`, `android_press_home`, `android_press_recents`, `android_open_notifications`, `android_open_quick_settings`, `android_get_device_logs` | 7 |
| Touch Actions | `android_tap`, `android_long_press`, `android_double_tap`, `android_swipe`, `android_scroll` | 8 |
| Gestures | `android_pinch`, `android_custom_gesture` | 8 |
| Element Actions | `android_find_elements`, `android_click_element`, `android_long_click_element`, `android_scroll_to_element` | 9 |
| Text Input | `android_type_append_text`, `android_type_insert_text`, `android_type_replace_text`, `android_type_clear_text`, `android_press_key` | 9, 22 |
| Utilities | `android_get_clipboard`, `android_set_clipboard`, `android_wait_for_element`, `android_wait_for_idle`, `android_get_element_details` | 9, 15 |
| File Operations | `android_list_storage_locations`, `android_list_files`, `android_read_file`, `android_write_file`, `android_append_file`, `android_file_replace`, `android_download_from_url`, `android_delete_file` | - |
| App Management | `android_open_app`, `android_list_apps`, `android_close_app` | - |

## Tool Naming Convention

All MCP tool names are prefixed with `android_` by default. When a **device slug** is configured in the app settings (e.g., `pixel7`), the prefix becomes `android_<slug>_`.

| Device Slug | Tool Name Example |
|-------------|-------------------|
| _(empty)_ | `android_tap`, `android_find_elements`, `android_get_screen_state` |
| `pixel7` | `android_pixel7_tap`, `android_pixel7_find_elements`, `android_pixel7_get_screen_state` |
| `work_phone` | `android_work_phone_tap`, `android_work_phone_find_elements`, `android_work_phone_get_screen_state` |

The device slug is configured in the app's **Configuration** section. Valid slugs contain only letters (a-z, A-Z), digits (0-9), and underscores. Maximum length is 20 characters. An empty slug is valid and results in the default `android_` prefix.

> **Note**: All tool names in this document use the default `android_` prefix (no device slug). When a slug is configured, replace `android_` with `android_<slug>_` in all tool names.

### Endpoint

All MCP communication goes through a single endpoint:
- **MCP endpoint**: `POST /mcp` — Streamable HTTP transport (JSON-only, no SSE). Handles all protocol messages: `initialize`, `tools/list`, `tools/call`, etc.

---

## Common Patterns

### Request Format

All tool calls use the same JSON-RPC 2.0 request format:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "<tool_name>",
    "arguments": { ... }
  }
}
```

### Response Format (Success)

Successful tool calls return a `content` array with typed entries:

**Text content**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "..."
      }
    ]
  }
}
```

**Image content**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "image",
        "data": "<base64-encoded data>",
        "mimeType": "image/jpeg"
      }
    ]
  }
}
```

### Response Format (Tool Error)

Tool errors are returned as `CallToolResult(isError = true)` with the error message in `TextContent`:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Accessibility service not enabled"
      }
    ],
    "isError": true
  }
}
```

---

## Error Handling

Tool errors are **not** returned as JSON-RPC error codes. Instead, the SDK catches `McpToolException` subtypes and wraps them as `CallToolResult(isError = true)` with a descriptive message in `TextContent`. This follows the standard MCP SDK pattern.

Protocol-level errors (parse errors, invalid requests) are handled automatically by the SDK and returned as standard JSON-RPC errors.

---

## 1. Screen Introspection Tools

### `android_get_screen_state`

Returns the consolidated current screen state: screen dimensions and a compact filtered flat TSV list of UI elements from **all on-screen windows** (including system dialogs, permission popups, and IME keyboards). Optionally includes an annotated low-resolution screenshot with bounding boxes and element ID labels.

Uses Android's multi-window accessibility API (`getWindows()`) to enumerate all interactive windows. Falls back to single-window mode via `rootInActiveWindow` when the multi-window API is unavailable (degraded mode).

Replaces the previous `get_accessibility_tree`, `capture_screenshot`, `get_current_app`, and `get_screen_info` tools.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "include_screenshot": {
      "type": "boolean",
      "description": "Include a low-resolution screenshot. Only request when the UI element list is not sufficient.",
      "default": false
    }
  },
  "required": []
}
```

**Request Example** (text only):
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_get_screen_state",
    "arguments": {}
  }
}
```

**Request Example** (with screenshot):
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_get_screen_state",
    "arguments": {
      "include_screenshot": true
    }
  }
}
```

**Response Example (text only)**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "note:structural-only nodes are omitted from the tree\nnote:certain elements are custom and will not be properly reported, if needed or if tools are not working as expected set include_screenshot=true to see the screen and take what you see into account\nnote:flags: on=onscreen off=offscreen clk=clickable lclk=longClickable foc=focusable scr=scrollable edt=editable ena=enabled\nnote:offscreen items require scroll_to_element before interaction\nscreen:1080x2400 density:420 orientation:portrait\n--- window:42 type:APPLICATION pkg:com.android.calculator2 title:Calculator activity:.Calculator layer:0 focused:true ---\nid\tclass\ttext\tdesc\tres_id\tbounds\tflags\nnode_1_w42\tTextView\tCalculator\t-\tcom.android.calculator2:id/title\t100,50,500,120\ton,ena\nnode_2_w42\tButton\t7\t-\tcom.android.calculator2:id/digit_7\t50,800,270,1000\ton,clk,ena"
      }
    ]
  }
}
```

**Response Example (with screenshot)**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "note:structural-only nodes are omitted from the tree\n...\nscreen:1080x2400 density:420 orientation:portrait\n--- window:42 type:APPLICATION pkg:com.android.calculator2 title:Calculator activity:.Calculator layer:0 focused:true ---\nid\tclass\ttext\tdesc\tres_id\tbounds\tflags\n..."
      },
      {
        "type": "image",
        "data": "/9j/4AAQSkZJRgABAQ...<base64 annotated JPEG data>",
        "mimeType": "image/jpeg"
      }
    ]
  }
}
```

#### Output Format

The text output is a multi-window compact flat TSV (tab-separated values) format designed for token-efficient LLM consumption. Each on-screen window gets its own section:

1. **Note lines** (global):
   - `note:structural-only nodes are omitted from the tree`
   - `note:certain elements are custom and will not be properly reported...`
   - `note:flags: on=onscreen off=offscreen clk=clickable lclk=longClickable foc=focusable scr=scrollable edt=editable ena=enabled`
   - `note:offscreen items require scroll_to_element before interaction`
2. **Degradation note** (only in fallback mode): `note:DEGRADED — multi-window API unavailable, showing rootInActiveWindow only`
3. **Screen line** (global): `screen:<width>x<height> density:<dpi> orientation:<orientation>`
4. **Per-window sections** (repeated for each window):
   - **Window header**: `--- window:<id> type:<TYPE> pkg:<package> title:<title> [activity:<activity>] layer:<N> focused:<bool> ---`
   - **TSV header**: `id\tclass\ttext\tdesc\tres_id\tbounds\tflags`
   - **Data rows**: One row per filtered node with tab-separated values

The `activity:` field in the window header is only present for the focused APPLICATION window whose package matches the tracked foreground package. Window types include: APPLICATION, INPUT_METHOD, SYSTEM, ACCESSIBILITY_OVERLAY, SPLIT_SCREEN_DIVIDER, MAGNIFICATION_OVERLAY.

Element IDs include the window ID suffix (e.g., `node_abc_w42`) to ensure uniqueness across windows.

#### Flags Reference

The `flags` column uses comma-separated abbreviated codes. A legend is included in the TSV notes.
Only flags that are `true` are included. The `on`/`off` flag is always first.

| Flag   | Meaning       |
|--------|---------------|
| `on`   | onscreen      |
| `off`  | offscreen     |
| `clk`  | clickable     |
| `lclk` | longClickable |
| `foc`  | focusable     |
| `scr`  | scrollable    |
| `edt`  | editable      |
| `ena`  | enabled       |

Example: `on,clk,ena` means onscreen + clickable + enabled.

#### Class Name Simplification

The `class` column shows the simple class name, stripped of the package prefix (e.g., `android.widget.Button` → `Button`). Returns `-` if null or empty.

#### Node Filtering

Nodes are **omitted** from the output when ALL of the following are true:
- No `text`
- No `contentDescription`
- No `resourceId`
- Not `clickable`, `longClickable`, `scrollable`, or `editable`

This filters out structural-only container nodes (e.g., bare `FrameLayout`, `LinearLayout`) that have no semantic value for LLM tool callers.

#### Text/Description Truncation

Both `text` and `desc` columns are truncated to **100 characters**. If truncated, the value ends with `...truncated`. Use the `android_get_element_details` tool to retrieve full untruncated values by element ID.

#### Screenshot

When `include_screenshot` is `true`, a low-resolution annotated JPEG screenshot (max 700px in either dimension, quality 80) is included as a second content item (`ImageContent`). The screenshot is annotated with:
- **Red dashed bounding boxes** (2px) around each on-screen element that appears in the TSV
- **Semi-transparent red pill labels** with white bold text showing the element ID hash (e.g., `a3f2` for `node_a3f2`) at the top-left of each bounding box

Off-screen elements (marked with `off` flag in the TSV) do not have bounding boxes on the screenshot. Use the `scroll_to_element` tool to bring them into view first.

Only request the screenshot when the element list alone is not sufficient to understand the screen layout.

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Permission denied**: Accessibility service not enabled or not ready
- **Action failed**: All windows returned null root nodes
- **Action failed**: No windows available and no active window root node
- **Permission denied**: Screen capture not available (when `include_screenshot` is true)
- **Action failed**: Screenshot capture failed

---

## 2. System Action Tools

### `android_press_back`

Presses the back button (global accessibility action).

**Input Schema**:
```json
{
  "type": "object",
  "properties": {},
  "required": []
}
```

**Request Example**:
```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "method": "tools/call",
  "params": {
    "name": "android_press_back",
    "arguments": {}
  }
}
```

**Response Example (Success)**:
```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Back button press executed successfully"
      }
    ]
  }
}
```

**Error Cases**:
- **Permission denied**: Accessibility service not enabled
- **Action failed**: Action execution failed

---

### `android_press_home`

Navigates to the home screen.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {},
  "required": []
}
```

**Request Example**:
```json
{
  "jsonrpc": "2.0",
  "id": 6,
  "method": "tools/call",
  "params": {
    "name": "android_press_home",
    "arguments": {}
  }
}
```

**Response Example (Success)**:
```json
{
  "jsonrpc": "2.0",
  "id": 6,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Home button press executed successfully"
      }
    ]
  }
}
```

**Error Cases**:
- **Permission denied**: Accessibility service not enabled
- **Action failed**: Action execution failed

---

### `android_press_recents`

Opens the recent apps screen.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {},
  "required": []
}
```

**Request Example**:
```json
{
  "jsonrpc": "2.0",
  "id": 7,
  "method": "tools/call",
  "params": {
    "name": "android_press_recents",
    "arguments": {}
  }
}
```

**Response Example (Success)**:
```json
{
  "jsonrpc": "2.0",
  "id": 7,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Recents button press executed successfully"
      }
    ]
  }
}
```

**Error Cases**:
- **Permission denied**: Accessibility service not enabled
- **Action failed**: Action execution failed

---

### `android_open_notifications`

Pulls down the notification shade.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {},
  "required": []
}
```

**Request Example**:
```json
{
  "jsonrpc": "2.0",
  "id": 8,
  "method": "tools/call",
  "params": {
    "name": "android_open_notifications",
    "arguments": {}
  }
}
```

**Response Example (Success)**:
```json
{
  "jsonrpc": "2.0",
  "id": 8,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Open notifications executed successfully"
      }
    ]
  }
}
```

**Error Cases**:
- **Permission denied**: Accessibility service not enabled
- **Action failed**: Action execution failed

---

### `android_open_quick_settings`

Opens the quick settings panel.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {},
  "required": []
}
```

**Request Example**:
```json
{
  "jsonrpc": "2.0",
  "id": 9,
  "method": "tools/call",
  "params": {
    "name": "android_open_quick_settings",
    "arguments": {}
  }
}
```

**Response Example (Success)**:
```json
{
  "jsonrpc": "2.0",
  "id": 9,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Open quick settings executed successfully"
      }
    ]
  }
}
```

**Error Cases**:
- **Permission denied**: Accessibility service not enabled
- **Action failed**: Action execution failed

---

### `android_get_device_logs`

Retrieves device logcat logs filtered by time range, tag, level, or package name.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "last_lines": {
      "type": "integer",
      "description": "Number of most recent log lines to return (1-1000)",
      "default": 100
    },
    "since": {
      "type": "string",
      "description": "ISO 8601 timestamp to filter logs from (e.g., 2024-01-15T10:30:00)"
    },
    "until": {
      "type": "string",
      "description": "ISO 8601 timestamp to filter logs until (used with since)"
    },
    "tag": {
      "type": "string",
      "description": "Filter by log tag (exact match, e.g., MCP:ServerService)"
    },
    "level": {
      "type": "string",
      "enum": ["V", "D", "I", "W", "E", "F"],
      "description": "Minimum log level to include",
      "default": "D"
    },
    "package_name": {
      "type": "string",
      "description": "Filter logs by package name"
    }
  },
  "required": []
}
```

**Request Example** (default, last 100 lines):
```json
{
  "jsonrpc": "2.0",
  "id": 10,
  "method": "tools/call",
  "params": {
    "name": "android_get_device_logs",
    "arguments": {}
  }
}
```

**Request Example** (filtered by tag and level):
```json
{
  "jsonrpc": "2.0",
  "id": 10,
  "method": "tools/call",
  "params": {
    "name": "android_get_device_logs",
    "arguments": {
      "last_lines": 50,
      "tag": "MCP:ServerService",
      "level": "W"
    }
  }
}
```

**Response Example (Success)**:
```json
{
  "jsonrpc": "2.0",
  "id": 10,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{\"logs\":\"02-11 16:30:00.123 D/MCP:ServerService: Server started on port 8080\\n02-11 16:30:01.456 I/MCP:ServerService: Client connected\",\"line_count\":2,\"truncated\":false}"
      }
    ]
  }
}
```

**Error Cases**:
- **Invalid params**: Invalid parameter (e.g., `last_lines` out of range 1-1000, invalid `level`)
- **Action failed**: Logcat command execution failed

---

## 3. Touch Action Tools

Coordinate-based touch interactions. All coordinates are in screen pixels (absolute).
Coordinate values must be >= 0. Duration values must be between 1 and 60000 milliseconds.

### `android_tap`

Performs a single tap at the specified coordinates.

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `x` | number | Yes | - | X coordinate (>= 0) |
| `y` | number | Yes | - | Y coordinate (>= 0) |

**Example Request**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_tap",
    "arguments": {
      "x": 500,
      "y": 1000
    }
  }
}
```

**Example Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Tap executed at (500, 1000)"
      }
    ]
  }
}
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing or invalid parameters (x, y not numbers or negative)
- **Permission denied**: Accessibility service not enabled
- **Action failed**: Tap gesture execution failed

---

### `android_long_press`

Performs a long press at the specified coordinates.

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `x` | number | Yes | - | X coordinate (>= 0) |
| `y` | number | Yes | - | Y coordinate (>= 0) |
| `duration` | number | No | 1000 | Press duration in ms (1-60000) |

**Example Request**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_long_press",
    "arguments": {
      "x": 500,
      "y": 1000,
      "duration": 2000
    }
  }
}
```

**Example Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Long press executed at (500, 1000) for 2000ms"
      }
    ]
  }
}
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing or invalid parameters (x, y not numbers or negative; duration <= 0 or > 60000)
- **Permission denied**: Accessibility service not enabled
- **Action failed**: Long press gesture execution failed

---

### `android_double_tap`

Performs a double tap at the specified coordinates.

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `x` | number | Yes | - | X coordinate (>= 0) |
| `y` | number | Yes | - | Y coordinate (>= 0) |

**Example Request**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_double_tap",
    "arguments": {
      "x": 500,
      "y": 1000
    }
  }
}
```

**Example Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Double tap executed at (500, 1000)"
      }
    ]
  }
}
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing or invalid parameters
- **Permission denied**: Accessibility service not enabled
- **Action failed**: Double tap gesture execution failed

---

### `android_swipe`

Performs a swipe gesture from one point to another.

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `x1` | number | Yes | - | Start X coordinate (>= 0) |
| `y1` | number | Yes | - | Start Y coordinate (>= 0) |
| `x2` | number | Yes | - | End X coordinate (>= 0) |
| `y2` | number | Yes | - | End Y coordinate (>= 0) |
| `duration` | number | No | 300 | Swipe duration in ms (1-60000) |

**Example Request**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_swipe",
    "arguments": {
      "x1": 500,
      "y1": 1500,
      "x2": 500,
      "y2": 500,
      "duration": 300
    }
  }
}
```

**Example Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Swipe executed from (500, 1500) to (500, 500) over 300ms"
      }
    ]
  }
}
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing or invalid parameters (coords not numbers or negative; duration <= 0 or > 60000)
- **Permission denied**: Accessibility service not enabled
- **Action failed**: Swipe gesture execution failed

---

### `android_scroll`

Scrolls the screen in the specified direction. Calculates scroll distance as a percentage of screen dimension based on the amount parameter.

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `direction` | string | Yes | - | Direction: "up", "down", "left", "right" |
| `amount` | string | No | "medium" | Amount: "small" (25%), "medium" (50%), "large" (75%) |

**Example Request**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_scroll",
    "arguments": {
      "direction": "down",
      "amount": "large"
    }
  }
}
```

**Example Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Scroll down (large) executed"
      }
    ]
  }
}
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Invalid direction (not one of up/down/left/right) or invalid amount (not one of small/medium/large)
- **Permission denied**: Accessibility service not enabled
- **Action failed**: Scroll gesture execution failed (e.g., no root node available for screen dimensions)

---

## 4. Gesture Tools

Advanced multi-touch gesture tools for zoom and custom gesture sequences.

### `android_pinch`

Performs a pinch-to-zoom gesture centered at the specified coordinates.

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `center_x` | number | Yes | - | Center X coordinate (>= 0) |
| `center_y` | number | Yes | - | Center Y coordinate (>= 0) |
| `scale` | number | Yes | - | Scale factor (> 0; > 1 = zoom in, < 1 = zoom out) |
| `duration` | number | No | 300 | Gesture duration in ms (1-60000) |

**Example Request** (zoom in):
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_pinch",
    "arguments": {
      "center_x": 540,
      "center_y": 1200,
      "scale": 2.0
    }
  }
}
```

**Example Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Pinch (zoom in) executed at (540, 1200) with scale 2.0 over 300ms"
      }
    ]
  }
}
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing or invalid parameters (coords negative; scale <= 0; duration <= 0 or > 60000)
- **Permission denied**: Accessibility service not enabled
- **Action failed**: Pinch gesture execution failed

---

### `android_custom_gesture`

Executes a custom multi-touch gesture defined by path points. Each path represents one finger's movement. Multiple paths enable multi-finger gestures.

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `paths` | array | Yes | - | Array of paths (see below) |

Each path is an array of point objects:
| Field | Type | Description |
|-------|------|-------------|
| `x` | number | X coordinate (>= 0) |
| `y` | number | Y coordinate (>= 0) |
| `time` | number | Time offset in ms from gesture start (>= 0, monotonically increasing) |

**Validation Rules**:
- `paths` must be a non-empty array
- Each path must contain at least 2 points
- All coordinates must be >= 0
- All time values must be >= 0
- Time values must be strictly monotonically increasing within each path

**Example Request** (single-finger drag):
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_custom_gesture",
    "arguments": {
      "paths": [
        [
          {"x": 100, "y": 100, "time": 0},
          {"x": 200, "y": 200, "time": 150},
          {"x": 300, "y": 300, "time": 300}
        ]
      ]
    }
  }
}
```

**Example Request** (two-finger pinch):
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_custom_gesture",
    "arguments": {
      "paths": [
        [
          {"x": 400, "y": 600, "time": 0},
          {"x": 300, "y": 600, "time": 300}
        ],
        [
          {"x": 600, "y": 600, "time": 0},
          {"x": 700, "y": 600, "time": 300}
        ]
      ]
    }
  }
}
```

**Example Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Custom gesture executed with 2 path(s), total 4 point(s)"
      }
    ]
  }
}
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Invalid parameters (empty paths, path with < 2 points, negative coords/times, non-monotonic times, missing fields)
- **Permission denied**: Accessibility service not enabled
- **Action failed**: Custom gesture execution failed

---

## 5. Element Action Tools

### `android_find_elements`

Find UI elements matching the specified criteria in the accessibility tree.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "by": {
      "type": "string",
      "enum": ["text", "content_desc", "resource_id", "class_name"],
      "description": "Search criteria type"
    },
    "value": {
      "type": "string",
      "description": "Search value"
    },
    "exact_match": {
      "type": "boolean",
      "default": false,
      "description": "If true, match exactly. If false, match contains (case-insensitive)"
    }
  },
  "required": ["by", "value"]
}
```

**Output**: JSON string containing an `elements` array (may be empty):
```json
{
  "elements": [
    {
      "id": "node_abc123",
      "text": "Submit",
      "contentDescription": null,
      "resourceId": "com.example:id/submit_btn",
      "className": "android.widget.Button",
      "bounds": { "left": 50, "top": 800, "right": 250, "bottom": 1000 },
      "clickable": true,
      "longClickable": false,
      "scrollable": false,
      "editable": false,
      "enabled": true
    }
  ]
}
```

**Example**:
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "android_find_elements", "arguments": { "by": "text", "value": "Submit" } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Invalid `by` value, empty `value`, or missing required parameters
- **Permission denied**: Accessibility service not enabled

---

### `android_click_element`

Click the specified accessibility node by element ID.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "element_id": { "type": "string", "description": "Node ID from find_elements" }
  },
  "required": ["element_id"]
}
```

**Output**: `"Click performed on element '<element_id>'"`

**Example**:
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "android_click_element", "arguments": { "element_id": "node_abc123" } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing or empty `element_id`
- **Permission denied**: Accessibility service not enabled
- **Element not found**: Element not found in accessibility tree
- **Action failed**: Element is not clickable or click action failed

---

### `android_long_click_element`

Long-click the specified accessibility node by element ID.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "element_id": { "type": "string", "description": "Node ID from find_elements" }
  },
  "required": ["element_id"]
}
```

**Output**: `"Long-click performed on element '<element_id>'"`

**Example**:
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "android_long_click_element", "arguments": { "element_id": "node_abc123" } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing or empty `element_id`
- **Permission denied**: Accessibility service not enabled
- **Element not found**: Element not found
- **Action failed**: Element is not long-clickable or action failed

---

### `android_scroll_to_element`

Scroll to make the specified element visible by scrolling its nearest scrollable ancestor.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "element_id": { "type": "string", "description": "Node ID from find_elements" }
  },
  "required": ["element_id"]
}
```

**Output**: `"Scrolled to element '<element_id>' (N scroll(s))"` or `"Element '<element_id>' is already visible"`

**Example**:
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "android_scroll_to_element", "arguments": { "element_id": "node_abc123" } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing or empty `element_id`
- **Permission denied**: Accessibility service not enabled
- **Element not found**: Element not found
- **Action failed**: No scrollable container found, scroll failed, or element not visible after max attempts (5)

---

## 6. Text Input Tools

Natural text input tools that use the Android AccessibilityService's `FLAG_INPUT_METHOD_EDITOR` + `AccessibilityInputConnection.commitText()` API (API 33+) for character-by-character typing that is indistinguishable from real IME input. All type tools require `element_id` (mandatory), click the element to focus it, and return the field content after the operation for verification.

All typing operations are serialized via a Mutex — concurrent MCP requests are queued, not interleaved.

### `android_type_append_text`

Type text character by character at the end of a text field. Uses natural InputConnection typing (indistinguishable from keyboard input). For text longer than 2000 characters, call this tool multiple times — subsequent calls continue typing at the current cursor position.

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `element_id` | string | Yes | - | Target element ID to type into |
| `text` | string | Yes | - | Text to type (must be non-empty, max 2000 characters) |
| `typing_speed` | integer | No | 70 | Base delay between characters in ms (min: 10, max: 5000) |
| `typing_speed_variance` | integer | No | 15 | Random variance in ms, clamped to [0, typing_speed] |

**Output**: `"Typed N characters at end of element '<element_id>'.\nField content: <content>"`

**Request Example**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_type_append_text",
    "arguments": {
      "element_id": "node_abc123",
      "text": "Hello World"
    }
  }
}
```

**Response Example**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Typed 11 characters at end of element 'node_abc123'.\nField content: Hello World"
      }
    ]
  }
}
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing or empty `element_id` or `text`, text exceeds 2000 characters, `typing_speed` out of range (10-5000), `typing_speed_variance` negative
- **Permission denied**: Accessibility service not enabled
- **Element not found**: Element not found in accessibility tree
- **Action failed**: Click failed, input connection not ready (element may not be editable), cursor positioning failed, typing failed (input connection lost)

---

### `android_type_insert_text`

Type text character by character at a specific position in a text field. Uses natural InputConnection typing (indistinguishable from keyboard input).

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `element_id` | string | Yes | - | Target element ID to type into |
| `text` | string | Yes | - | Text to type (must be non-empty, max 2000 characters) |
| `offset` | integer | Yes | - | 0-based character offset for cursor position. Must be within [0, current text length] |
| `typing_speed` | integer | No | 70 | Base delay between characters in ms (min: 10, max: 5000) |
| `typing_speed_variance` | integer | No | 15 | Random variance in ms, clamped to [0, typing_speed] |

**Output**: `"Typed N characters at offset M in element '<element_id>'.\nField content: <content>"`

**Request Example**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_type_insert_text",
    "arguments": {
      "element_id": "node_abc123",
      "text": "inserted ",
      "offset": 5
    }
  }
}
```

**Response Example**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Typed 9 characters at offset 5 in element 'node_abc123'.\nField content: Helloinserted  World"
      }
    ]
  }
}
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing or empty `element_id` or `text`, text exceeds 2000 characters, missing `offset`, `offset` negative, `offset` exceeds current text length, `typing_speed` out of range (10-5000), `typing_speed_variance` negative
- **Permission denied**: Accessibility service not enabled
- **Element not found**: Element not found in accessibility tree
- **Action failed**: Click failed, input connection not ready, cursor positioning failed, typing failed (input connection lost)

---

### `android_type_replace_text`

Find and replace text in a field by typing the replacement naturally. Finds the first occurrence of search text, selects and deletes it, then types new_text character by character via InputConnection. If `new_text` is empty, only deletes the found text (delete-only mode).

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `element_id` | string | Yes | - | Target element ID |
| `search` | string | Yes | - | Text to find in the field (first occurrence, max 10000 characters) |
| `new_text` | string | Yes | - | Replacement text to type (max 2000 characters). Can be empty to just delete the found text |
| `typing_speed` | integer | No | 70 | Base delay between characters in ms (min: 10, max: 5000) |
| `typing_speed_variance` | integer | No | 15 | Random variance in ms, clamped to [0, typing_speed] |

**Output**: `"Replaced N characters with M characters in element '<element_id>'.\nField content: <content>"`

**Request Example**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_type_replace_text",
    "arguments": {
      "element_id": "node_abc123",
      "search": "World",
      "new_text": "Android"
    }
  }
}
```

**Response Example**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Replaced 5 characters with 7 characters in element 'node_abc123'.\nField content: Hello Android"
      }
    ]
  }
}
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing or empty `element_id` or `search`, `search` exceeds 10000 characters, `new_text` exceeds 2000 characters, `typing_speed` out of range (10-5000), `typing_speed_variance` negative
- **Permission denied**: Accessibility service not enabled
- **Element not found**: Element not found in accessibility tree, or search text not found in the field
- **Action failed**: Click failed, input connection not ready, text selection failed, deletion failed, typing failed (input connection lost)

---

### `android_type_clear_text`

Clear all text from a field naturally using select-all + delete. Uses InputConnection operations (indistinguishable from user action). If the field is already empty, returns success without performing any action.

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `element_id` | string | Yes | - | Target element ID to clear |

**Output**: `"Text cleared from element '<element_id>'.\nField content: <content>"`

**Request Example**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_type_clear_text",
    "arguments": {
      "element_id": "node_abc123"
    }
  }
}
```

**Response Example**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Text cleared from element 'node_abc123'.\nField content: "
      }
    ]
  }
}
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing or empty `element_id`
- **Permission denied**: Accessibility service not enabled
- **Element not found**: Element not found in accessibility tree
- **Action failed**: Click failed, input connection not ready, select-all failed, deletion failed (input connection lost)

---

### `android_press_key`

Press a specific key. Supported keys: ENTER, BACK, DEL, HOME, TAB, SPACE.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "key": {
      "type": "string",
      "enum": ["ENTER", "BACK", "DEL", "HOME", "TAB", "SPACE"],
      "description": "Key to press"
    }
  },
  "required": ["key"]
}
```

**Output**: `"Key '<KEY>' pressed successfully"`

**Key Behavior**:
- **BACK**, **HOME**: Delegate to global accessibility actions
- **ENTER**: Uses `ACTION_IME_ENTER`
- **DEL**: Removes last character from focused field (no-op if empty)
- **TAB**, **SPACE**: Appends the character to focused field text

**Example**:
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "android_press_key", "arguments": { "key": "ENTER" } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing `key` parameter or invalid key name
- **Permission denied**: Accessibility service not enabled
- **Element not found**: No focused element found (for ENTER, DEL, TAB, SPACE)
- **Action failed**: Key action failed

---

## 7. Utility Tools

### `android_get_clipboard`

Get the current clipboard text content. Accessibility services are exempt from Android 10+ background clipboard restrictions.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {},
  "required": []
}
```

**Output**: JSON string:
```json
{ "text": "clipboard content" }
```
Returns `{ "text": null }` when clipboard is empty.

**Example**:
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "android_get_clipboard", "arguments": {} }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Permission denied**: Accessibility service not enabled
- **Action failed**: Clipboard access failed

---

### `android_set_clipboard`

Set the clipboard content to the specified text.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "text": { "type": "string", "description": "Text to set in clipboard" }
  },
  "required": ["text"]
}
```

**Output**: `"Clipboard set successfully (N characters)"`

**Example**:
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "android_set_clipboard", "arguments": { "text": "Hello from MCP" } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing `text` parameter
- **Permission denied**: Accessibility service not enabled
- **Action failed**: Clipboard set failed

---

### `android_wait_for_element`

Wait until an element matching the specified criteria appears in the accessibility tree, polling every 500ms.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "by": {
      "type": "string",
      "enum": ["text", "content_desc", "resource_id", "class_name"],
      "description": "Search criteria type"
    },
    "value": { "type": "string", "description": "Search value" },
    "timeout": {
      "type": "integer",
      "description": "Timeout in milliseconds (1-30000). Required."
    }
  },
  "required": ["by", "value", "timeout"]
}
```

**Output** (on success): JSON string:
```json
{
  "found": true,
  "elapsedMs": 1200,
  "attempts": 3,
  "element": {
    "id": "node_abc123",
    "text": "Result",
    "contentDescription": null,
    "resourceId": null,
    "className": "android.widget.TextView",
    "bounds": { "left": 50, "top": 800, "right": 250, "bottom": 1000 },
    "clickable": false,
    "enabled": true
  }
}
```

**Timeout behavior**: When the timeout expires without finding the element, a **non-error** `CallToolResult` is returned with an informational message (e.g., `{"found": false, "elapsedMs": 5000, "attempts": 10}`). This is not a tool error — the caller should check the `found` field.

**Example**:
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "android_wait_for_element", "arguments": { "by": "text", "value": "10", "timeout": 5000 } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- Invalid `by` value, empty `value`, missing required parameters, or timeout out of range (1-30000)
- Accessibility service not enabled

---

### `android_wait_for_idle`

Wait for the UI to become idle by comparing accessibility tree fingerprints using similarity-based change detection. Generates a 256-slot histogram fingerprint of the tree structure and uses normalized difference to compute a similarity percentage. Considers UI idle when two consecutive snapshots (500ms apart) meet the `match_percentage` threshold.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "timeout": {
      "type": "integer",
      "description": "Timeout in milliseconds (1-30000). Required."
    },
    "match_percentage": {
      "type": "integer",
      "description": "Similarity threshold percentage (0-100, default 100). 100 = exact match, lower values tolerate minor UI changes",
      "default": 100
    }
  },
  "required": ["timeout"]
}
```

**Output** (on success): JSON string:
```json
{
  "message": "UI is idle",
  "elapsedMs": 1500,
  "similarity": 100
}
```

**Timeout behavior**: When the timeout expires without the UI becoming idle, a **non-error** `CallToolResult` is returned with a JSON object containing the last computed similarity:
```json
{
  "message": "Operation timed out after 3000ms waiting for UI idle. Retry if the operation is long-running.",
  "elapsedMs": 3000,
  "similarity": 85
}
```
This is not a tool error — the caller should check the `message` field.

**Example** (exact match, default behavior):
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "android_wait_for_idle", "arguments": { "timeout": 3000 } }
  }'
```

**Example** (tolerate minor UI changes):
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "android_wait_for_idle", "arguments": { "timeout": 5000, "match_percentage": 95 } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- Timeout out of range (1-30000) or missing
- `match_percentage` out of range (0-100)
- Accessibility service not enabled

---

### `android_get_element_details`

Retrieves full untruncated text and contentDescription for one or more elements by their IDs. Use this tool when `android_get_screen_state` shows truncated values (ending with `...truncated`) and you need the full content.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "ids": {
      "type": "array",
      "items": { "type": "string" },
      "description": "Array of element IDs to look up (from get_screen_state output)"
    }
  },
  "required": ["ids"]
}
```

**Request Example**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_get_element_details",
    "arguments": {
      "ids": ["node_1", "node_2"]
    }
  }
}
```

**Response Example (Success)**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "id\ttext\tdesc\nnode_1\tThis is a very long text value that was truncated in get_screen_state but is returned in full here\t-\nnode_2\t-\tFull content description for this element"
      }
    ]
  }
}
```

**Output Format**: TSV with three columns:
- `id`: The element ID
- `text`: Full untruncated text (or `-` if null/empty)
- `desc`: Full untruncated contentDescription (or `-` if null/empty)

If an element ID is not found in the current accessibility tree, the row shows `not_found` for both text and desc columns.

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing `ids` parameter, `ids` is not an array, array is empty, or contains non-string values
- **Permission denied**: Accessibility service not enabled

---

## 8. File Tools

File operations on user-added storage locations. Storage locations must be added by the user in the app settings before file operations can be performed. Text file operations are subject to a configurable file size limit. All file paths are relative to the storage location root.

### `android_list_storage_locations`

Lists user-added storage locations with their metadata. Each location represents a directory the user granted access to via the app settings. Use the location ID from this list for all file operations.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {},
  "required": []
}
```

**Example Request**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_list_storage_locations",
    "arguments": {}
  }
}
```

**Example Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "[{\"id\":\"com.android.externalstorage.documents/primary:\",\"name\":\"Internal Storage\",\"path\":\"/\",\"description\":\"Main device storage\",\"available_bytes\":52428800000,\"allow_read\":true,\"allow_write\":false,\"allow_delete\":false}]"
      }
    ]
  }
}
```

**Response Fields** (per location):
| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Unique location identifier |
| `name` | string | Display name of the directory |
| `path` | string | Human-readable path |
| `description` | string | User-provided description |
| `available_bytes` | number \| null | Available space in bytes, or null if unknown |
| `allow_read` | boolean | Always `true` — read access is always permitted |
| `allow_write` | boolean | Whether write operations are permitted (write_file, append_file, file_replace, download_from_url) |
| `allow_delete` | boolean | Whether delete operations are permitted (delete_file) |

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Action failed**: Failed to query storage locations

---

### `android_list_files`

Lists files and directories in a storage location. Results are sorted directories-first, then alphabetically. Supports pagination via `offset` and `limit` parameters.

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `location_id` | string | Yes | - | Storage location ID from `android_list_storage_locations` |
| `path` | string | No | `""` | Relative path within the storage location |
| `offset` | integer | No | 0 | Pagination offset (0-based) |
| `limit` | integer | No | 200 | Maximum number of entries to return (max 200) |

**Example Request**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_list_files",
    "arguments": {
      "location_id": "com.android.providers.downloads.documents/downloads",
      "path": "",
      "offset": 0,
      "limit": 200
    }
  }
}
```

**Example Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{\"files\":[{\"name\":\"Documents\",\"path\":\"loc/Documents\",\"is_directory\":true,\"size\":0,\"last_modified\":null,\"mime_type\":null},{\"name\":\"readme.txt\",\"path\":\"loc/readme.txt\",\"is_directory\":false,\"size\":1024,\"last_modified\":1700000000000,\"mime_type\":\"text/plain\"}],\"total_count\":2,\"has_more\":false}"
      }
    ]
  }
}
```

**Notes**:
- Returns `total_count` (total number of entries in the directory) and `has_more` (whether more entries exist beyond the current page) for pagination.
- Directories are listed before files, both sorted alphabetically.

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing `location_id`, invalid `offset` or `limit`
- **Action failed**: Storage location not found, path not found

---

### `android_read_file`

Reads a text file with line-based pagination. Returns content with line numbers.

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `location_id` | string | Yes | - | Storage location ID from `android_list_storage_locations` |
| `path` | string | Yes | - | Relative path to the file within the storage location |
| `offset` | integer | No | 1 | Starting line number (1-based) |
| `limit` | integer | No | 200 | Maximum number of lines to return (max 200) |

**Example Request**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_read_file",
    "arguments": {
      "location_id": "com.android.providers.downloads.documents/downloads",
      "path": "readme.txt",
      "offset": 1,
      "limit": 200
    }
  }
}
```

**Example Response** (partial file):
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "1| First line of the file\n2| Second line of the file\n--- More lines available. Use offset=3 to continue reading. Total lines: 100 ---"
      }
    ]
  }
}
```

**Notes**:
- Subject to configured file size limit.
- When more lines exist beyond the returned range, a hint is appended: `"--- More lines available. Use offset=N to continue reading. Total lines: T ---"`
- Line numbers are 1-based and formatted as `N| line content`.

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing `location_id` or `path`, invalid `offset` or `limit`
- **Action failed**: Storage location not found, file not found, file exceeds size limit, file is not a text file

---

### `android_write_file`

Writes text content to a file. Creates the file if it doesn't exist, creates parent directories automatically, overwrites existing content. Empty content creates an empty file.

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `location_id` | string | Yes | - | Storage location ID from `android_list_storage_locations` |
| `path` | string | Yes | - | Relative path to the file within the storage location |
| `content` | string | Yes | - | Text content to write (empty string creates an empty file) |

**Example Request**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_write_file",
    "arguments": {
      "location_id": "com.android.providers.downloads.documents/downloads",
      "path": "notes/todo.txt",
      "content": "Buy groceries\nWalk the dog"
    }
  }
}
```

**Example Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "File written successfully: notes/todo.txt"
      }
    ]
  }
}
```

**Notes**:
- Subject to configured file size limit.
- Parent directories are created automatically if they don't exist.

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing `location_id`, `path`, or `content`
- **Permission denied**: Write not allowed
- **Action failed**: Storage location not found, content exceeds file size limit, write operation failed

---

### `android_append_file`

Appends text content to an existing file. If the storage provider does not support append mode, an error with a hint to use `android_write_file` is returned.

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `location_id` | string | Yes | - | Storage location ID from `android_list_storage_locations` |
| `path` | string | Yes | - | Relative path to the file within the storage location |
| `content` | string | Yes | - | Text content to append |

**Example Request**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_append_file",
    "arguments": {
      "location_id": "com.android.providers.downloads.documents/downloads",
      "path": "notes/todo.txt",
      "content": "\nFeed the cat"
    }
  }
}
```

**Example Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Content appended successfully to: notes/todo.txt"
      }
    ]
  }
}
```

**Notes**:
- Not all storage providers (e.g., virtual providers like Google Drive) support append mode. When unsupported, the error message includes a hint to use `android_read_file` + `android_write_file` as a workaround.
- Subject to configured file size limit (combined existing + appended content).

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing `location_id`, `path`, or `content`
- **Permission denied**: Write not allowed
- **Action failed**: Storage location not found, file not found, append mode not supported by provider, content exceeds file size limit

---

### `android_file_replace`

Performs literal string replacement in a text file. Uses advisory file locking when supported by the storage provider.

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `location_id` | string | Yes | - | Storage location ID from `android_list_storage_locations` |
| `path` | string | Yes | - | Relative path to the file within the storage location |
| `old_string` | string | Yes | - | The literal string to search for |
| `new_string` | string | Yes | - | The replacement string |
| `replace_all` | boolean | No | `false` | If `true`, replaces all occurrences; if `false`, replaces only the first |

**Example Request**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_file_replace",
    "arguments": {
      "location_id": "com.android.providers.downloads.documents/downloads",
      "path": "notes/todo.txt",
      "old_string": "Buy groceries",
      "new_string": "Buy organic groceries",
      "replace_all": false
    }
  }
}
```

**Example Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Replaced 1 occurrence(s) in: notes/todo.txt"
      }
    ]
  }
}
```

**Notes**:
- Literal matching only (no regex support).
- Subject to configured file size limit.
- Uses advisory file locking when supported by the underlying storage provider to avoid concurrent modification issues.

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing `location_id`, `path`, `old_string`, or `new_string`; `old_string` is empty
- **Permission denied**: Write not allowed
- **Action failed**: Storage location not found, file not found, `old_string` not found in file, file exceeds size limit

---

### `android_download_from_url`

Downloads a file from a URL and saves it to a storage location.

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `location_id` | string | Yes | - | Storage location ID from `android_list_storage_locations` |
| `path` | string | Yes | - | Destination path within the storage location |
| `url` | string | Yes | - | URL to download from (HTTP or HTTPS) |

**Example Request**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_download_from_url",
    "arguments": {
      "location_id": "com.android.providers.downloads.documents/downloads",
      "path": "images/photo.jpg",
      "url": "https://example.com/photo.jpg"
    }
  }
}
```

**Example Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "File downloaded successfully: images/photo.jpg"
      }
    ]
  }
}
```

**Notes**:
- Subject to file size limit, download timeout, and HTTP/HTTPS security settings configured in the app.
- HTTP downloads (non-HTTPS) must be explicitly allowed in app settings.
- Unverified HTTPS certificates must be explicitly allowed in app settings.
- Parent directories are created automatically if they don't exist.

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing `location_id`, `path`, or `url`; invalid URL format
- **Permission denied**: Write not allowed
- **Action failed**: Storage location not found, download failed (network error, timeout, HTTP error status), file exceeds size limit, HTTP not allowed, unverified HTTPS not allowed

---

### `android_delete_file`

Deletes a single file from a storage location. Cannot delete directories.

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `location_id` | string | Yes | - | Storage location ID from `android_list_storage_locations` |
| `path` | string | Yes | - | Relative path to the file within the storage location |

**Example Request**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_delete_file",
    "arguments": {
      "location_id": "com.android.providers.downloads.documents/downloads",
      "path": "notes/old-todo.txt"
    }
  }
}
```

**Example Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "File deleted successfully: notes/old-todo.txt"
      }
    ]
  }
}
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing `location_id` or `path`
- **Permission denied**: Delete not allowed
- **Action failed**: Storage location not found, file not found, target is a directory (not a file), delete operation failed

---

## 9. App Management Tools

Tools for managing installed applications: launching, listing, and closing apps.

### `android_open_app`

Opens (launches) an application by its package ID. The app must be installed and have a launchable activity.

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `package_id` | string | Yes | - | The package ID of the app to launch (e.g., `com.example.app`) |

**Example Request**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_open_app",
    "arguments": {
      "package_id": "com.android.calculator2"
    }
  }
}
```

**Example Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "App opened successfully: com.android.calculator2"
      }
    ]
  }
}
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing or empty `package_id`
- **Action failed**: App not installed, app has no launchable activity, launch failed

---

### `android_list_apps`

Lists installed applications. Can filter by type (all, user-installed, system) and by name substring.

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `filter` | string | No | `"all"` | Filter by app type: `"all"`, `"user"`, or `"system"` |
| `name_query` | string | No | - | Filter by app name substring (case-insensitive) |

**Example Request** (list user apps matching "calc"):
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_list_apps",
    "arguments": {
      "filter": "user",
      "name_query": "calc"
    }
  }
}
```

**Example Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "[{\"package_id\":\"com.example.app\",\"name\":\"Example App\",\"version_name\":\"1.2.3\",\"version_code\":42,\"is_system\":false}]"
      }
    ]
  }
}
```

**Notes**:
- The `filter` parameter accepts `"all"` (default, all installed apps), `"user"` (user-installed apps only), or `"system"` (system apps only).
- The `name_query` parameter performs a case-insensitive substring match on the app's display name.
- Both filters can be combined.

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Invalid `filter` value (not one of `all`, `user`, `system`)
- **Action failed**: Failed to query installed applications

---

### `android_close_app`

Kills a background application process.

**Input Schema**:
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `package_id` | string | Yes | - | The package ID of the app to close (e.g., `com.example.app`) |

**Example Request**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_close_app",
    "arguments": {
      "package_id": "com.example.app"
    }
  }
}
```

**Example Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "App closed successfully: com.example.app"
      }
    ]
  }
}
```

**Important Limitations**:
- This only works for apps that are currently in the **background**. For foreground apps, use `android_press_home` first to send the app to the background, wait briefly (e.g., using `android_wait_for_idle`), then call `android_close_app`.
- System processes may restart automatically after being killed.

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing or empty `package_id`
- **Action failed**: App not installed, failed to kill process
