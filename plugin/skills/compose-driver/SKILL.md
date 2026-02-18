---
name: compose-driver
description: Drive any Jetpack Compose UI to verify UI logic, capture screenshots, and iterate on composables. Use whenever you need to see or interact with a running Compose UI — Android or Desktop.
---

# Compose Driver Skill

## Description

Compose Driver wraps a target Composable in a `ComposeUiTest` harness and exposes all UI actions
as MCP tools (screenshots, clicks, text input, scrolling, gestures, etc.). It runs fully headless
— no emulator or device required.

## Setup

### 1. Verify the project has Compose Driver configured

The project's `settings.gradle.kts` must apply the Compose Driver plugin and the repo must have a
`:driver-mcp` module (or the sample's `:mcp` module). Look for:

```kotlin
// settings.gradle.kts
plugins {
    id("io.github.jdemeulenaere.compose.driver")
}
composeDriver {
    desktop() // and/or android()
}
```

If the plugin is missing, instruct the user to follow the setup instructions at
https://github.com/jdemeulenaere/compose-driver.

### 2. Find the target Composable's fully-qualified name

The `compose.driver.composable` property must point to a `@Composable` function. Search the
codebase for the entry-point composable, then construct its FQN:

```
<package>.<FileName>Kt.<FunctionName>
```

Example: `fun MyScreen()` in `com/example/ui/Screen.kt` with package `com.example.ui` →
`com.example.ui.ScreenKt.MyScreen`

### 3. Start the MCP server

The MCP server starts the Compose UI in a test harness and exposes all actions as tools.
It is already wired to Claude Code via the project's `.mcp.json` or the user's MCP config.
If it is not yet configured, add it:

```bash
claude mcp add --scope project --transport stdio compose-driver \
  -- ./gradlew :driver-mcp:run \
  -Dcompose.driver.composable=<fully.qualified.ComposableName>
```

For the sample project use `:mcp:run` instead of `:driver-mcp:run`.

After adding the server, restart Claude Code and verify with `/mcp` that `compose-driver` appears.

### 4. Alternatively — Raw HTTP server (no MCP)

If MCP is not available, start the HTTP server directly and interact via `curl`:

```bash
# Desktop (fast, recommended for JVM/multiplatform)
pkill -f compose-driver-desktop || true
./gradlew :compose-driver-desktop:run \
  -Dcompose.driver.composable=<fully.qualified.ComposableName> &

# Poll until ready
until curl -s http://localhost:8080/status | grep -q ok; do sleep 1; done
```

```bash
# Android (via Robolectric — slower first run)
pkill -f compose-driver-android || true
./gradlew :compose-driver-android:run \
  -Dcompose.driver.composable=<fully.qualified.ComposableName> &

until curl -s http://localhost:8080/status | grep -q ok; do sleep 1; done
```

---

## MCP Tools Reference

When using the MCP server, the following tools are available directly. All tools that target a
specific node accept these optional parameters:

| Parameter | Description |
|---|---|
| `nodeTag` | Match by `Modifier.testTag()` |
| `nodeText` | Match by text content |
| `nodeTextSubstring` | Treat `nodeText` as substring (default: false) |
| `nodeTextIgnoreCase` | Case-insensitive text match (default: false) |

If neither `nodeTag` nor `nodeText` is provided, the action applies to the root node.

### Observation

| Tool | Description |
|---|---|
| `status` | Check if the server is ready (returns "ok") |
| `print_tree` | Return the semantic tree — use this first to discover node tags |
| `screenshot` | Capture a PNG of the matched node or full screen |

### Synchronization

| Tool | Extra inputs | Description |
|---|---|---|
| `wait_for_idle` | — | Wait for all recompositions to settle |
| `wait_for_node` | `timeout` (ms, default 5000) | Wait until a matching node appears |

### Interaction

| Tool | Extra inputs | Description |
|---|---|---|
| `click` | `gifDurationMs` (0–5000) | Click; optionally returns GIF of the animation |
| `long_click` | — | Long press |
| `double_click` | — | Double tap |
| `text_input` | `text` (required) | Append text to a text field |
| `text_replacement` | `text` (required) | Replace all text in a text field |
| `text_clearance` | — | Clear a text field |
| `scroll_to` | — | Scroll the node into view |
| `swipe` | `direction` (UP/DOWN/LEFT/RIGHT, required) | Swipe gesture |
| `navigate_back` | — | Trigger back navigation |

### Low-level pointer input

| Tool | Extra inputs | Description |
|---|---|---|
| `pointer_down` | `pointerId` | Press at node center |
| `pointer_move_by` | `x`, `y`, `pointerId` | Move pointer by offset |
| `pointer_move_to` | `x`, `y`, `pointerId` | Move pointer to absolute position |
| `pointer_up` | `pointerId` | Release pointer |

### Lifecycle

| Tool | Extra inputs | Description |
|---|---|---|
| `reset` | `composable` (optional FQN) | Reset UI state; optionally switch composable |

---

## HTTP API Reference (raw server mode)

All endpoints are `GET` requests to `http://localhost:8080`. Node selection params (`nodeTag`,
`nodeText`, `nodeTextSubstring`, `nodeTextIgnoreCase`) apply to all endpoints below.

| Endpoint | Extra params | Description |
|---|---|---|
| `/status` | — | Health check |
| `/printTree` | — | Semantic tree as text |
| `/screenshot` | — | PNG image |
| `/waitForIdle` | — | Wait for idle |
| `/waitForNode` | `timeout` | Wait for node |
| `/click` | `gifDurationMs` | Click (+ optional GIF) |
| `/longClick` | — | Long press |
| `/doubleClick` | — | Double tap |
| `/textInput` | `text` (req) | Append text |
| `/textReplacement` | `text` (req) | Replace text |
| `/textClearance` | — | Clear text |
| `/scrollTo` | — | Scroll to node |
| `/swipe` | `direction` (req) | Swipe |
| `/navigateBack` | — | Back navigation |
| `/pointerInput/down` | `pointerId` | Pointer down |
| `/pointerInput/moveBy` | `x`, `y`, `pointerId` | Move by |
| `/pointerInput/moveTo` | `x`, `y`, `pointerId` | Move to |
| `/pointerInput/up` | `pointerId` | Pointer up |
| `/reset` | `composable` | Reset UI |

---

## Workflow Examples

### Inspect and interact

1. Call `print_tree` to see the semantic tree and find `testTag` values
2. Call `screenshot` to see the current UI state
3. Call `click` with `nodeTag=<tag>` to interact
4. Call `screenshot` again to verify the result

### Test a login flow

1. `print_tree` → find tags for username, password, login button
2. `wait_for_node` with `nodeTag=username_field`
3. `text_input` with `nodeTag=username_field`, `text=myuser`
4. `text_input` with `nodeTag=password_field`, `text=mypass`
5. `click` with `nodeTag=login_button`
6. `wait_for_node` with `nodeTag=home_screen`

### Capture an animation

```
click(nodeTag="animate_btn", gifDurationMs=1000)
```

Returns a 1-second GIF of the click and resulting animation. Requires `ffmpeg` on PATH.

### Interact with off-screen items

If `print_tree` shows a node but it is outside the viewport:

1. `scroll_to` with `nodeTag=target_item` — brings it into view
2. `click` with `nodeTag=target_item`

### Reset between test scenarios

Call `reset` to restore the initial UI state between scenarios without restarting the server.
Pass `composable` to switch to a different screen entirely.
