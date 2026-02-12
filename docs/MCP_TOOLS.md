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
6. [Touch Action Tools](#3-touch-action-tools) *(Plan 8)*
7. [Gesture Tools](#4-gesture-tools) *(Plan 8)*
8. [Element Action Tools](#5-element-action-tools) *(Plan 9)*
9. [Text Input Tools](#6-text-input-tools) *(Plan 9)*
10. [Utility Tools](#7-utility-tools) *(Plan 9)*

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

*To be documented in Plan 8.*

Tools: `tap`, `long_press`, `double_tap`, `swipe`, `scroll`

---

## 4. Gesture Tools

*To be documented in Plan 8.*

Tools: `pinch`, `custom_gesture`

---

## 5. Element Action Tools

*To be documented in Plan 9.*

Tools: `find_elements`, `click_element`, `long_click_element`, `set_text`, `scroll_to_element`

---

## 6. Text Input Tools

*To be documented in Plan 9.*

Tools: `input_text`, `clear_text`, `press_key`

---

## 7. Utility Tools

*To be documented in Plan 9.*

Tools: `get_clipboard`, `set_clipboard`, `wait_for_element`, `wait_for_idle`
