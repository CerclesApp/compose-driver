# Compose Driver Sample

This project demonstrates how to use the **Compose Driver** to instrument and drive Jetpack Compose
UI on Android and Desktop (JVM).

**This sample is very barebone and contains barely any code.** You can use it to quickly get started
with any project. Just open this folder in your favorite AI coding tool and prompt it: *"Create a
multiplatform clone of [Instagram] in the `multiplatform/` directory. Use your `compose-driver`
skill to iterate on the app and validate that it works as expected, and to share some screenshots of
the different screens as you progress."*

## Project Structure

- **`:android:lib`**: Android library module.
- **`:android:app`**: Android application.
- **`:desktop`**: Desktop (JVM) application module.
- **`:multiplatform`**: Shared UI code used by both platforms.
- (iOS module coming soon)

## Running the apps

### Desktop

```bash
./gradlew :desktop:run
```

### Android

```bash
./gradlew :android:app:installDebug
adb shell am start -n io.github.jdemeulenaere.compose.driver.sample.android.app/.MainActivity
```

## Running the Compose Driver

The Compose Driver plugin automatically generates subprojects that depend on `:android:lib`,
`:desktop` and `:multiplatform`.

> [!NOTE]
> The Compose Driver is mostly meant to be run by your AI coding tools, but running it yourself and
> pinging the endpoints in the browser is a fun way to see how (quickly) it works. **Make sure to
have an [agent skill](.agent/skills/compose-driver/SKILL.md) that describes how to use it!**

### MCP Server (Recommended)

Pass `-Dcompose.driver.mcp=true` to expose all compose-driver actions as MCP tools over HTTP (SSE)
at `/mcp`, so any MCP-compatible AI agent can discover and use them without knowing the raw HTTP API.

**Desktop**

```bash
./gradlew :compose-driver-desktop:run \
  -Dcompose.driver.composable=io.github.jdemeulenaere.compose.driver.sample.desktop.DesktopApplicationKt.DesktopApplication \
  -Dcompose.driver.mcp=true
```

**Android**

```bash
./gradlew :compose-driver-android:run \
  -Dcompose.driver.composable=io.github.jdemeulenaere.compose.driver.sample.android.AndroidApplicationKt.AndroidApplication \
  -Dcompose.driver.mcp=true
```

Point your MCP client (Claude Desktop, Cursor, etc.) at this URL:

```json
{
  "mcpServers": {
    "compose-driver": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

### HTTP Server Only (Desktop)

Run the raw HTTP server on Desktop without MCP:

```bash
./gradlew :compose-driver-desktop:run -Dcompose.driver.composable=io.github.jdemeulenaere.compose.driver.sample.desktop.DesktopApplicationKt.DesktopApplication
```

### HTTP Server Only (Android via Robolectric)

Run the raw HTTP server on Android without MCP:

```bash
./gradlew :compose-driver-android:run -Dcompose.driver.composable=io.github.jdemeulenaere.compose.driver.sample.android.AndroidApplicationKt.AndroidApplication
```

## Interacting with the Driver

Once the HTTP server is running (default port `8080`), you can interact with it using HTTP requests
in your browser or using `curl`.

See the full API in [../README.md](../README.md).

**Check Status:**

```bash
curl http://localhost:8080/status
```

**Print UI Tree:**

```bash
curl http://localhost:8080/printTree
```

**Click Example:**

```bash
# Click the button with testTag "button"
curl "http://localhost:8080/click?nodeTag=button"
```
