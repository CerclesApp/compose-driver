# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Project Is

Compose Driver enables AI agents to interact with Jetpack Compose UIs (Android and Desktop) via HTTP. It wraps a Composable in a `ComposeUiTest` harness and exposes a Ktor server on port 8080 that translates HTTP requests into UI test actions.

## Build Commands

```bash
# Build the library and plugin
./gradlew :driver-core:build
./gradlew :driver-plugin:build

# Build everything
./gradlew build

# Publish to local Maven (needed when testing sample with SNAPSHOT version)
./gradlew publishToMavenLocal

# Run sample on Desktop (from repo root or sample/)
cd sample && ./gradlew :compose-driver-desktop:run -Dcompose.driver.composable=io.github.jdemeulenaere.compose.driver.sample.desktop.DesktopApplicationKt.DesktopApplication

# Run sample on Android via Robolectric
cd sample && ./gradlew :compose-driver-android:run -Dcompose.driver.composable=io.github.jdemeulenaere.compose.driver.sample.android.AndroidApplicationKt.AndroidApplication
```

Gradle configuration cache (`org.gradle.configuration-cache=true`) and build caching are enabled — keep Gradle task code compatible with configuration cache.

## Architecture

The project has two publishable modules and a sample:

### `:driver-core` (KMP Library)
Targets JVM and Android. Published to Maven Central as `io.github.jdemeulenaere:compose-driver`.

Key files:
- `ComposeDriver.kt` — `startComposeDriverServer()` entry point. Sets up the `ComposeUiTest` harness, starts the Ktor/Netty HTTP server, and wires all routes to `ComposeUiTest` actions.
- `RunUiTest.kt` — `expect fun runUiTest(...)`. JVM actual uses `runSkikoComposeUiTest`; Android actual uses the Android Compose test runner.
- `ComposeReflection.kt` — Resolves a `@Composable` by fully qualified name via reflection, handling the Compose compiler's transformed signature (`$composer`, `$changed`, `$default` parameters).
- `GifEncoder.kt` / `GifEncoder.*.kt` — GIF recording by capturing frames during clock-advanced test time, then delegating to `ffmpeg`.
- `HttpServer.kt` — `expect` declarations for `ApplicationEngineFactory` and `respondStream`.
- `CaptureToImage.kt` — `expect` for capturing a `SemanticsNodeInteraction` to `ImageBitmap`.

Node selection in HTTP requests works via `nodeTag` (matches `Modifier.testTag()`), `nodeText`, or both combined. If neither is provided, the root node is used.

### `:driver-plugin` (Gradle Settings Plugin)
A Gradle Settings plugin (`io.github.jdemeulenaere.compose.driver`).

Key files:
- `DriverSettingsPlugin.kt` — Evaluates the `composeDriver { }` extension after settings evaluation. Generates `build.gradle.kts` files for each platform under `build/compose-driver/<name>/` and registers them as included subprojects. Scans all other projects for Compose plugins (`org.jetbrains.kotlin.plugin.compose`) to auto-wire dependencies.
- `DriverSettingsPluginExtension.kt` — DSL classes: `AndroidDriverConfiguration`, `DesktopDriverConfiguration`, `RobolectricConfiguration`.
- `GenerateAndroidTestClassTask.kt` — Generates a Robolectric test class (`ComposeDriverTest`) that calls `startComposeDriverServer()`. The test is a no-op unless `compose.driver.enabled=true` is passed as a system property (only set by the `:run` task).

#### How the plugin generates subprojects
- For **desktop**: generates a `plugins { application }` build file with `mainClass = "io.github.jdemeulenaere.compose.driver.MainKt"`.
- For **android**: generates an `com.android.library` build file with Robolectric configuration and the `GenerateAndroidTestClassTask`.
- The `:run` task on Android sets `compose.driver.enabled=true` and delegates to `testDebugUnitTest`.

#### SNAPSHOT vs release dependency
In `driverDependency()`: SNAPSHOT versions use `project(":compose-driver:driver-core")` (composite build); release versions use the Maven coordinate string.

## Key System Properties

| Property | Used by | Description |
|---|---|---|
| `compose.driver.composable` | both platforms | Fully qualified composable name, e.g. `com.example.FooKt.FooScreen` |
| `compose.driver.enabled` | Android only | Must be `true` for the Robolectric test to actually start the server |
| `compose.driver.desktop.window.width/height` | Desktop | Optional window size (default 1024×768) |
| `compose.driver.desktop.window.density` | Desktop | Optional display density (default 1.0) |

## Version Management

The current version is in `gradle.properties` as `compose.driver.version`. The plugin reads this at build time via a generated `compose-driver.properties` resource file (see `generateVersionFile` task in `driver-plugin/build.gradle.kts`).

## Sample Project

`sample/` is a standalone Gradle project that uses the plugin. Its `settings.gradle.kts` does `includeBuild("../")` when the version is a SNAPSHOT, so local changes to the plugin are picked up automatically. Set `compose.driver.local=true` in `sample/gradle.properties` to pull `:driver-core` from `mavenLocal()` instead.
