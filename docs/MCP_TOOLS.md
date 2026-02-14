# MCP Tools Reference

This document provides a comprehensive reference for all MCP tools available in the Android Remote Control MCP application. Each tool includes its schema, usage examples, and error handling information.

**Transport**: Streamable HTTP at `/mcp` (JSON-only, no SSE)
**Protocol**: JSON-RPC 2.0
**Authentication**: Bearer token required for all requests (global Application-level plugin)
**Content-Type**: `application/json`

---

## Table of Contents

1. [Overview](#overview)
2. [Common Patterns](#common-patterns)
3. [Error Codes](#error-codes)
4. [Screen Introspection Tools](#1-screen-introspection-tools)
5. [System Action Tools](#2-system-action-tools)
6. [Touch Action Tools](#3-touch-action-tools)
7. [Gesture Tools](#4-gesture-tools)
8. [Element Action Tools](#5-element-action-tools)
9. [Text Input Tools](#6-text-input-tools)
10. [Utility Tools](#7-utility-tools)

---

## Overview

The MCP server exposes tools via the JSON-RPC 2.0 protocol. Tools are organized into 7 categories:

| Category | Tools | Plan |
|----------|-------|------|
| Screen Introspection | `get_screen_state` | 7, 15 |
| System Actions | `press_back`, `press_home`, `press_recents`, `open_notifications`, `open_quick_settings`, `get_device_logs` | 7 |
| Touch Actions | `tap`, `long_press`, `double_tap`, `swipe`, `scroll` | 8 |
| Gestures | `pinch`, `custom_gesture` | 8 |
| Element Actions | `find_elements`, `click_element`, `long_click_element`, `set_text`, `scroll_to_element` | 9 |
| Text Input | `input_text`, `clear_text`, `press_key` | 9 |
| Utilities | `get_clipboard`, `set_clipboard`, `wait_for_element`, `wait_for_idle`, `get_element_details` | 9, 15 |

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

### `get_screen_state`

Returns the consolidated current screen state: app metadata, screen dimensions, and a compact filtered flat TSV list of UI elements. Optionally includes a low-resolution screenshot.

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
    "name": "get_screen_state",
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
    "name": "get_screen_state",
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
        "text": "note:structural-only nodes are omitted from the tree\napp:com.android.calculator2 activity:.Calculator\nscreen:1080x2400 density:420 orientation:portrait\nid\tclass\ttext\tdesc\tres_id\tbounds\tflags\nnode_1\tandroid.widget.TextView\tCalculator\t-\tcom.android.calculator2:id/title\t100,50,500,120\te\nnode_2\tandroid.widget.Button\t7\t-\tcom.android.calculator2:id/digit_7\t50,800,270,1000\tce"
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
        "text": "note:structural-only nodes are omitted from the tree\napp:com.android.calculator2 activity:.Calculator\nscreen:1080x2400 density:420 orientation:portrait\nid\tclass\ttext\tdesc\tres_id\tbounds\tflags\n..."
      },
      {
        "type": "image",
        "data": "/9j/4AAQSkZJRgABAQ...<base64 JPEG data>",
        "mimeType": "image/jpeg"
      }
    ]
  }
}
```

#### Output Format

The text output is a compact flat TSV (tab-separated values) format designed for token-efficient LLM consumption:

1. **Note line**: `note:structural-only nodes are omitted from the tree`
2. **App line**: `app:<package> activity:<activity>`
3. **Screen line**: `screen:<width>x<height> density:<dpi> orientation:<orientation>`
4. **Header**: `id\tclass\ttext\tdesc\tres_id\tbounds\tflags`
5. **Data rows**: One row per filtered node with tab-separated values

#### Flags Reference

The `flags` column uses single-character codes for interactive properties:

| Flag | Meaning |
|------|---------|
| `c` | clickable |
| `l` | longClickable |
| `f` | focusable |
| `s` | scrollable |
| `d` | editable |
| `e` | enabled |

Only flags that are `true` are included. Example: `ce` means clickable + enabled.

#### Node Filtering

Nodes are **omitted** from the output when ALL of the following are true:
- No `text`
- No `contentDescription`
- No `resourceId`
- Not `clickable`, `longClickable`, `scrollable`, or `editable`

This filters out structural-only container nodes (e.g., bare `FrameLayout`, `LinearLayout`) that have no semantic value for LLM tool callers.

#### Text/Description Truncation

Both `text` and `desc` columns are truncated to **100 characters**. If truncated, the value ends with `...truncated`. Use the `get_element_details` tool to retrieve full untruncated values by element ID.

#### Screenshot

When `include_screenshot` is `true`, a low-resolution JPEG screenshot (max 700px in either dimension, quality 80) is included as a second content item (`ImageContent`). Only request the screenshot when the element list alone is not sufficient to understand the screen layout.

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Permission denied**: Accessibility service not enabled
- **Action failed**: Failed to obtain root accessibility node
- **Permission denied**: Screen capture not available (when `include_screenshot` is true)
- **Action failed**: Screenshot capture failed

---

## 2. System Action Tools

### `press_back`

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
    "name": "press_back",
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

### `press_home`

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
    "name": "press_home",
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

### `press_recents`

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
    "name": "press_recents",
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

### `open_notifications`

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
    "name": "open_notifications",
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

### `open_quick_settings`

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
    "name": "open_quick_settings",
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

### `get_device_logs`

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
    "name": "get_device_logs",
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
    "name": "get_device_logs",
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

### `tap`

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
    "name": "tap",
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

### `long_press`

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
    "name": "long_press",
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

### `double_tap`

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
    "name": "double_tap",
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

### `swipe`

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
    "name": "swipe",
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

### `scroll`

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
    "name": "scroll",
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

### `pinch`

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
    "name": "pinch",
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

### `custom_gesture`

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
    "name": "custom_gesture",
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
    "name": "custom_gesture",
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

### `find_elements`

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
    "params": { "name": "find_elements", "arguments": { "by": "text", "value": "Submit" } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Invalid `by` value, empty `value`, or missing required parameters
- **Permission denied**: Accessibility service not enabled

---

### `click_element`

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
    "params": { "name": "click_element", "arguments": { "element_id": "node_abc123" } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing or empty `element_id`
- **Permission denied**: Accessibility service not enabled
- **Element not found**: Element not found in accessibility tree
- **Action failed**: Element is not clickable or click action failed

---

### `long_click_element`

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
    "params": { "name": "long_click_element", "arguments": { "element_id": "node_abc123" } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing or empty `element_id`
- **Permission denied**: Accessibility service not enabled
- **Element not found**: Element not found
- **Action failed**: Element is not long-clickable or action failed

---

### `set_text`

Set text on an editable accessibility node. Empty string clears the field.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "element_id": { "type": "string", "description": "Node ID from find_elements" },
    "text": { "type": "string", "description": "Text to set (empty string to clear)" }
  },
  "required": ["element_id", "text"]
}
```

**Output**: `"Text set on element '<element_id>'"`

**Example**:
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "set_text", "arguments": { "element_id": "node_abc123", "text": "Hello World" } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing `element_id` or `text` parameter
- **Permission denied**: Accessibility service not enabled
- **Element not found**: Element not found
- **Action failed**: Element is not editable or set text action failed

---

### `scroll_to_element`

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
    "params": { "name": "scroll_to_element", "arguments": { "element_id": "node_abc123" } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing or empty `element_id`
- **Permission denied**: Accessibility service not enabled
- **Element not found**: Element not found
- **Action failed**: No scrollable container found, scroll failed, or element not visible after max attempts (5)

---

## 6. Text Input Tools

### `input_text`

Type text into the focused input field or a specified element. When `element_id` is provided, the tool clicks the element to focus it before setting text.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "text": { "type": "string", "description": "Text to type" },
    "element_id": { "type": "string", "description": "Optional: target element ID to focus and type into" }
  },
  "required": ["text"]
}
```

**Output**: `"Text input completed (N characters)"`

**Example**:
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "input_text", "arguments": { "text": "Hello World" } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing `text` parameter
- **Permission denied**: Accessibility service not enabled
- **Element not found**: No focused editable element found (when `element_id` not provided) or element not found
- **Action failed**: Text input action failed

---

### `clear_text`

Clear text from the focused input field or a specified element.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "element_id": { "type": "string", "description": "Optional: target element ID to clear" }
  },
  "required": []
}
```

**Output**: `"Text cleared successfully"`

**Example**:
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "clear_text", "arguments": {} }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Permission denied**: Accessibility service not enabled
- **Element not found**: No focused editable element found (when `element_id` not provided)
- **Action failed**: Clear text action failed

---

### `press_key`

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
- **ENTER**: Uses `ACTION_IME_ENTER` on API 30+, fallback to newline append
- **DEL**: Removes last character from focused field (no-op if empty)
- **TAB**, **SPACE**: Appends the character to focused field text

**Example**:
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "press_key", "arguments": { "key": "ENTER" } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing `key` parameter or invalid key name
- **Permission denied**: Accessibility service not enabled
- **Element not found**: No focused element found (for ENTER, DEL, TAB, SPACE)
- **Action failed**: Key action failed

---

## 7. Utility Tools

### `get_clipboard`

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
    "params": { "name": "get_clipboard", "arguments": {} }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Permission denied**: Accessibility service not enabled
- **Action failed**: Clipboard access failed

---

### `set_clipboard`

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
    "params": { "name": "set_clipboard", "arguments": { "text": "Hello from MCP" } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: Missing `text` parameter
- **Permission denied**: Accessibility service not enabled
- **Action failed**: Clipboard set failed

---

### `wait_for_element`

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
    "params": { "name": "wait_for_element", "arguments": { "by": "text", "value": "10", "timeout": 5000 } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- Invalid `by` value, empty `value`, missing required parameters, or timeout out of range (1-30000)
- Accessibility service not enabled

---

### `wait_for_idle`

Wait for the UI to become idle by detecting when the accessibility tree structure stops changing. Considers UI idle when two consecutive snapshots (500ms apart) produce the same structural hash.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "timeout": {
      "type": "integer",
      "description": "Timeout in milliseconds (1-30000). Required."
    }
  },
  "required": ["timeout"]
}
```

**Output** (on success): JSON string:
```json
{
  "message": "UI is idle",
  "elapsedMs": 1500
}
```

**Timeout behavior**: When the timeout expires without the UI becoming idle, a **non-error** `CallToolResult` is returned with an informational message. This is not a tool error — the caller should check the message content.

**Example**:
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "wait_for_idle", "arguments": { "timeout": 3000 } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- Timeout out of range (1-30000) or missing
- Accessibility service not enabled

---

### `get_element_details`

Retrieves full untruncated text and contentDescription for one or more elements by their IDs. Use this tool when `get_screen_state` shows truncated values (ending with `...truncated`) and you need the full content.

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
    "name": "get_element_details",
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
