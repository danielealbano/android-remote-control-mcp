# MCP Tools Reference

This document provides a comprehensive reference for all MCP tools available in the Android Remote Control MCP application. Each tool includes its schema, usage examples, and error handling information.

**Protocol**: JSON-RPC 2.0 over HTTPS
**Authentication**: Bearer token required for all tool calls
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
| Screen Introspection | `get_accessibility_tree`, `capture_screenshot`, `get_current_app`, `get_screen_info` | 7 |
| System Actions | `press_back`, `press_home`, `press_recents`, `open_notifications`, `open_quick_settings`, `get_device_logs` | 7 |
| Touch Actions | `tap`, `long_press`, `double_tap`, `swipe`, `scroll` | 8 |
| Gestures | `pinch`, `custom_gesture` | 8 |
| Element Actions | `find_elements`, `click_element`, `long_click_element`, `set_text`, `scroll_to_element` | 9 |
| Text Input | `input_text`, `clear_text`, `press_key` | 9 |
| Utilities | `get_clipboard`, `set_clipboard`, `wait_for_element`, `wait_for_idle` | 9 |

### Endpoints

- **List tools**: `GET /mcp/v1/tools/list` (returns all registered tools)
- **Call tool**: `POST /mcp/v1/tools/call` (executes a tool)

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

### Response Format (Error)

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32001,
    "message": "Accessibility service not enabled"
  }
}
```

---

## Error Codes

### Standard JSON-RPC Error Codes

| Code | Name | Description |
|------|------|-------------|
| -32700 | Parse Error | Invalid JSON received |
| -32600 | Invalid Request | Malformed JSON-RPC request |
| -32601 | Method Not Found | Unknown tool name |
| -32602 | Invalid Params | Missing or invalid tool arguments |
| -32603 | Internal Error | Server-side error during tool execution |

### Custom MCP Error Codes

| Code | Name | Description |
|------|------|-------------|
| -32001 | Permission Denied | Accessibility service or MediaProjection not enabled |
| -32002 | Element Not Found | UI element not found by ID or criteria |
| -32003 | Action Failed | Accessibility action execution failed |
| -32004 | Timeout | Operation timed out |

---

## 1. Screen Introspection Tools

### `get_accessibility_tree`

Returns the full UI hierarchy of the current screen using accessibility services.

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
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "get_accessibility_tree",
    "arguments": {}
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
        "text": "{\"nodes\":[{\"id\":\"root_0\",\"className\":\"android.widget.FrameLayout\",\"text\":null,\"contentDescription\":null,\"resourceId\":null,\"bounds\":{\"left\":0,\"top\":0,\"right\":1080,\"bottom\":2400},\"clickable\":false,\"longClickable\":false,\"focusable\":false,\"scrollable\":false,\"editable\":false,\"enabled\":true,\"visible\":true,\"children\":[{\"id\":\"node_1\",\"className\":\"android.widget.TextView\",\"text\":\"Calculator\",\"bounds\":{\"left\":100,\"top\":50,\"right\":500,\"bottom\":120},\"visible\":true,\"enabled\":true,\"children\":[]}]}]}"
      }
    ]
  }
}
```

**Error Cases**:
- `-32001`: Accessibility service not enabled
- `-32003`: Failed to obtain root node

---

### `capture_screenshot`

Captures a screenshot of the current screen and returns it as base64-encoded JPEG.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "quality": {
      "type": "integer",
      "description": "JPEG quality (1-100)",
      "default": 80
    }
  },
  "required": []
}
```

**Request Example** (default quality):
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "capture_screenshot",
    "arguments": {}
  }
}
```

**Request Example** (custom quality):
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "capture_screenshot",
    "arguments": {
      "quality": 50
    }
  }
}
```

**Response Example (Success)**:
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      {
        "type": "image",
        "data": "/9j/4AAQSkZJRgABAQ...<base64 JPEG data>",
        "mimeType": "image/jpeg",
        "width": 1080,
        "height": 2400
      }
    ]
  }
}
```

**Error Cases**:
- `-32001`: MediaProjection permission not granted
- `-32602`: Quality parameter out of range (must be 1-100)
- `-32003`: Screenshot capture failed

---

### `get_current_app`

Returns the package name and activity name of the currently focused app.

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
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "get_current_app",
    "arguments": {}
  }
}
```

**Response Example (Success)**:
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{\"packageName\":\"com.android.calculator2\",\"activityName\":\".Calculator\"}"
      }
    ]
  }
}
```

**Response Example (No app focused)**:
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{\"packageName\":\"unknown\",\"activityName\":\"unknown\"}"
      }
    ]
  }
}
```

**Error Cases**:
- `-32001`: Accessibility service not enabled

---

### `get_screen_info`

Returns screen dimensions, orientation, and DPI.

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
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "get_screen_info",
    "arguments": {}
  }
}
```

**Response Example (Success)**:
```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{\"width\":1080,\"height\":2400,\"densityDpi\":420,\"orientation\":\"portrait\"}"
      }
    ]
  }
}
```

**Error Cases**:
- `-32001`: Accessibility service not enabled

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
- `-32001`: Accessibility service not enabled
- `-32003`: Action execution failed

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
- `-32001`: Accessibility service not enabled
- `-32003`: Action execution failed

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
- `-32001`: Accessibility service not enabled
- `-32003`: Action execution failed

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
- `-32001`: Accessibility service not enabled
- `-32003`: Action execution failed

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
- `-32001`: Accessibility service not enabled
- `-32003`: Action execution failed

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
- `-32602`: Invalid parameter (e.g., `last_lines` out of range 1-1000, invalid `level`)
- `-32003`: Logcat command execution failed

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

**Error Codes**:
- `-32602`: Missing or invalid parameters (x, y not numbers or negative)
- `-32001`: Accessibility service not enabled
- `-32003`: Tap gesture execution failed

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

**Error Codes**:
- `-32602`: Missing or invalid parameters (x, y not numbers or negative; duration <= 0 or > 60000)
- `-32001`: Accessibility service not enabled
- `-32003`: Long press gesture execution failed

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

**Error Codes**:
- `-32602`: Missing or invalid parameters
- `-32001`: Accessibility service not enabled
- `-32003`: Double tap gesture execution failed

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

**Error Codes**:
- `-32602`: Missing or invalid parameters (coords not numbers or negative; duration <= 0 or > 60000)
- `-32001`: Accessibility service not enabled
- `-32003`: Swipe gesture execution failed

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

**Error Codes**:
- `-32602`: Invalid direction (not one of up/down/left/right) or invalid amount (not one of small/medium/large)
- `-32001`: Accessibility service not enabled
- `-32003`: Scroll gesture execution failed (e.g., no root node available for screen dimensions)

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

**Error Codes**:
- `-32602`: Missing or invalid parameters (coords negative; scale <= 0; duration <= 0 or > 60000)
- `-32001`: Accessibility service not enabled
- `-32003`: Pinch gesture execution failed

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

**Error Codes**:
- `-32602`: Invalid parameters (empty paths, path with < 2 points, negative coords/times, non-monotonic times, missing fields)
- `-32001`: Accessibility service not enabled
- `-32003`: Custom gesture execution failed

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
curl -X POST https://localhost:8080/mcp/v1/tools/call \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "find_elements", "arguments": { "by": "text", "value": "Submit" } }
  }'
```

**Error Codes**:
- `-32602`: Invalid `by` value, empty `value`, or missing required parameters
- `-32001`: Accessibility service not enabled

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
curl -X POST https://localhost:8080/mcp/v1/tools/call \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "click_element", "arguments": { "element_id": "node_abc123" } }
  }'
```

**Error Codes**:
- `-32602`: Missing or empty `element_id`
- `-32001`: Accessibility service not enabled
- `-32002`: Element not found in accessibility tree
- `-32003`: Element is not clickable or click action failed

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
curl -X POST https://localhost:8080/mcp/v1/tools/call \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "long_click_element", "arguments": { "element_id": "node_abc123" } }
  }'
```

**Error Codes**:
- `-32602`: Missing or empty `element_id`
- `-32001`: Accessibility service not enabled
- `-32002`: Element not found
- `-32003`: Element is not long-clickable or action failed

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
curl -X POST https://localhost:8080/mcp/v1/tools/call \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "set_text", "arguments": { "element_id": "node_abc123", "text": "Hello World" } }
  }'
```

**Error Codes**:
- `-32602`: Missing `element_id` or `text` parameter
- `-32001`: Accessibility service not enabled
- `-32002`: Element not found
- `-32003`: Element is not editable or set text action failed

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
curl -X POST https://localhost:8080/mcp/v1/tools/call \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "scroll_to_element", "arguments": { "element_id": "node_abc123" } }
  }'
```

**Error Codes**:
- `-32602`: Missing or empty `element_id`
- `-32001`: Accessibility service not enabled
- `-32002`: Element not found
- `-32003`: No scrollable container found, scroll failed, or element not visible after max attempts (5)

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
curl -X POST https://localhost:8080/mcp/v1/tools/call \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "input_text", "arguments": { "text": "Hello World" } }
  }'
```

**Error Codes**:
- `-32602`: Missing `text` parameter
- `-32001`: Accessibility service not enabled
- `-32002`: No focused editable element found (when `element_id` not provided) or element not found
- `-32003`: Text input action failed

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
curl -X POST https://localhost:8080/mcp/v1/tools/call \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "clear_text", "arguments": {} }
  }'
```

**Error Codes**:
- `-32001`: Accessibility service not enabled
- `-32002`: No focused editable element found (when `element_id` not provided)
- `-32003`: Clear text action failed

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
curl -X POST https://localhost:8080/mcp/v1/tools/call \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "press_key", "arguments": { "key": "ENTER" } }
  }'
```

**Error Codes**:
- `-32602`: Missing `key` parameter or invalid key name
- `-32001`: Accessibility service not enabled
- `-32002`: No focused element found (for ENTER, DEL, TAB, SPACE)
- `-32003`: Key action failed

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
curl -X POST https://localhost:8080/mcp/v1/tools/call \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "get_clipboard", "arguments": {} }
  }'
```

**Error Codes**:
- `-32001`: Accessibility service not enabled
- `-32003`: Clipboard access failed

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
curl -X POST https://localhost:8080/mcp/v1/tools/call \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "set_clipboard", "arguments": { "text": "Hello from MCP" } }
  }'
```

**Error Codes**:
- `-32602`: Missing `text` parameter
- `-32001`: Accessibility service not enabled
- `-32003`: Clipboard set failed

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
      "description": "Timeout in milliseconds (1-30000)",
      "default": 5000
    }
  },
  "required": ["by", "value"]
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

**Example**:
```bash
curl -X POST https://localhost:8080/mcp/v1/tools/call \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "wait_for_element", "arguments": { "by": "text", "value": "10", "timeout": 5000 } }
  }'
```

**Error Codes**:
- `-32602`: Invalid `by` value, empty `value`, missing required parameters, or timeout out of range (1-30000)
- `-32001`: Accessibility service not enabled
- `-32004`: Element not found within timeout

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
      "description": "Timeout in milliseconds (1-30000)",
      "default": 3000
    }
  },
  "required": []
}
```

**Output** (on success): JSON string:
```json
{
  "message": "UI is idle",
  "elapsedMs": 1500
}
```

**Example**:
```bash
curl -X POST https://localhost:8080/mcp/v1/tools/call \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "wait_for_idle", "arguments": { "timeout": 3000 } }
  }'
```

**Error Codes**:
- `-32602`: Timeout out of range (1-30000)
- `-32001`: Accessibility service not enabled
- `-32004`: UI did not become idle within timeout
