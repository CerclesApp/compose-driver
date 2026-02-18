@file:OptIn(ExperimentalTestApi::class)

package io.github.jdemeulenaere.compose.driver

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.io.ByteArrayOutputStream
import java.util.Properties
import kotlin.coroutines.CoroutineContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val emptyJsonObject = JsonObject(emptyMap())

// arguments is nullable in the Kotlin SDK despite the MCP spec requiring it
private val CallToolRequest.args: JsonObject get() = arguments ?: emptyJsonObject

internal suspend fun runMcpServer(
    test: ComposeUiTest,
    runTestContext: CoroutineContext,
    navigationEventDispatcher: NavigationEventDispatcher,
    onReset: (composableName: String?) -> Unit,
) {
    val server = Server(
        serverInfo = Implementation(name = "compose-driver", version = composeDriverVersion()),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools()),
        ),
    )

    registerMcpTools(server, test, runTestContext, navigationEventDispatcher, onReset)

    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered(),
    )

    server.createSession(transport)
    val done = Job()
    server.onClose { done.complete() }
    done.join()
}

private fun composeDriverVersion(): String {
    val props = Properties()
    val stream = object {}::class.java.getResourceAsStream("/compose-driver.properties")
    if (stream != null) {
        stream.use { props.load(it) }
        return props.getProperty("version", "unknown")
    }
    return "unknown"
}

private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.content

private fun nodeMatcherFromArgs(args: JsonObject): SemanticsMatcher {
    val nodeTag = args.str("nodeTag")
    val nodeText = args.str("nodeText")
    val nodeTextSubstring = args.str("nodeTextSubstring")?.toBoolean() ?: false
    val nodeTextIgnoreCase = args.str("nodeTextIgnoreCase")?.toBoolean() ?: false

    val tagMatcher = nodeTag?.let { hasTestTag(it) }
    val textMatcher = nodeText?.let { hasText(it, substring = nodeTextSubstring, ignoreCase = nodeTextIgnoreCase) }

    return when {
        tagMatcher != null && textMatcher != null -> tagMatcher and textMatcher
        tagMatcher != null -> tagMatcher
        textMatcher != null -> textMatcher
        else -> isRoot()
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun ImageBitmap.toPngBase64(): String {
    val out = ByteArrayOutputStream()
    writePng(this, out)
    return Base64.encode(out.toByteArray())
}

@OptIn(ExperimentalEncodingApi::class)
private fun gifBase64(frames: List<ImageBitmap>, timeBetweenFramesMs: Long): String {
    return Base64.encode(gifBytes(frames, timeBetweenFramesMs))
}

private val nodeSelectionProperties = buildJsonObject {
    putJsonObject("nodeTag") {
        put("type", "string")
        put("description", "Match node by Modifier.testTag(). Falls back to nodeText or root if omitted.")
    }
    putJsonObject("nodeText") {
        put("type", "string")
        put("description", "Match node by text content.")
    }
    putJsonObject("nodeTextSubstring") {
        put("type", "boolean")
        put("description", "Treat nodeText as a substring match (default false).")
    }
    putJsonObject("nodeTextIgnoreCase") {
        put("type", "boolean")
        put("description", "Match nodeText case-insensitively (default false).")
    }
}

private fun nodeSchema(extra: (JsonObjectBuilder.() -> Unit)? = null, required: List<String> = emptyList()): ToolSchema =
    ToolSchema(
        properties = buildJsonObject {
            nodeSelectionProperties.forEach { key, value -> put(key, value) }
            extra?.invoke(this)
        },
        required = required,
    )

private fun emptySchema(): ToolSchema = ToolSchema()

private fun registerMcpTools(
    server: Server,
    test: ComposeUiTest,
    runTestContext: CoroutineContext,
    navigationEventDispatcher: NavigationEventDispatcher,
    onReset: (composableName: String?) -> Unit,
) {
    suspend fun onNode(
        matcher: SemanticsMatcher,
        f: suspend ComposeUiTest.(SemanticsNodeInteraction) -> Unit,
    ) = withContext(runTestContext) {
        test.waitForIdle()
        test.f(test.onNode(matcher))
        test.waitForIdle()
    }

    server.addTool(
        name = "status",
        description = "Health check — returns 'ok' when the compose-driver server is running.",
        inputSchema = emptySchema(),
    ) { _ ->
        CallToolResult(content = listOf(TextContent(text = "ok")))
    }

    server.addTool(
        name = "reset",
        description = "Reset the UI to its initial state, or load a different composable by fully-qualified name.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("composable") {
                    put("type", "string")
                    put(
                        "description",
                        "Fully-qualified composable name, e.g. com.example.FooKt.FooScreen. Omit to reset current composable.",
                    )
                }
            },
        ),
    ) { request ->
        val composableName = request.args.str("composable")
        withContext(runTestContext) { onReset(composableName) }
        CallToolResult(content = listOf(TextContent(text = "ok")))
    }

    server.addTool(
        name = "screenshot",
        description = "Capture a PNG screenshot of the matched node, or the whole UI if no node is specified.",
        inputSchema = nodeSchema(),
    ) { request ->
        val matcher = nodeMatcherFromArgs(request.args)
        val encoded = withContext(runTestContext) {
            test.waitForIdle()
            test.onNode(matcher).captureToImage().toPngBase64()
        }
        CallToolResult(content = listOf(ImageContent(data = encoded, mimeType = "image/png")))
    }

    server.addTool(
        name = "print_tree",
        description = "Print the semantic tree of the matched node (or root). Useful for discovering node tags and text.",
        inputSchema = nodeSchema(),
    ) { request ->
        val matcher = nodeMatcherFromArgs(request.args)
        val tree = withContext(runTestContext) {
            test.waitForIdle()
            test.onNode(matcher).printToString()
        }
        CallToolResult(content = listOf(TextContent(text = tree)))
    }

    server.addTool(
        name = "wait_for_idle",
        description = "Wait for the Compose UI to become idle (all recompositions and animations settled).",
        inputSchema = nodeSchema(),
    ) { request ->
        val matcher = nodeMatcherFromArgs(request.args)
        onNode(matcher) {}
        CallToolResult(content = listOf(TextContent(text = "ok")))
    }

    server.addTool(
        name = "wait_for_node",
        description = "Wait until a node matching the given criteria exists, up to the specified timeout.",
        inputSchema = nodeSchema(
            extra = {
                putJsonObject("timeout") {
                    put("type", "integer")
                    put("description", "Maximum wait time in milliseconds (default 5000).")
                }
            },
        ),
    ) { request ->
        val matcher = nodeMatcherFromArgs(request.args)
        val timeout = request.args.str("timeout")?.toLong() ?: 5_000L
        onNode(matcher) { waitUntilAtLeastOneExists(matcher, timeout) }
        CallToolResult(content = listOf(TextContent(text = "ok")))
    }

    server.addTool(
        name = "click",
        description = "Perform a single tap/click on the matched node. Optionally returns an animated GIF.",
        inputSchema = nodeSchema(
            extra = {
                putJsonObject("gifDurationMs") {
                    put("type", "integer")
                    put(
                        "description",
                        "If set (0–5000), returns a GIF recording the UI for this many milliseconds after the click.",
                    )
                }
            },
        ),
    ) { request ->
        val matcher = nodeMatcherFromArgs(request.args)
        val gifDurationMs = request.args.str("gifDurationMs")?.toInt()
        if (gifDurationMs != null) {
            require(gifDurationMs in 0..5_000) { "gifDurationMs should be <= 5_000 and >= 0" }
            val timeBetweenFramesMs = 16L
            val frames = withContext(runTestContext) {
                test.waitForIdle()
                generateFrames(test, matcher, gifDurationMs, timeBetweenFramesMs) { it.performClick() }
            }
            val encoded = gifBase64(frames, timeBetweenFramesMs)
            CallToolResult(content = listOf(ImageContent(data = encoded, mimeType = "image/gif")))
        } else {
            onNode(matcher) { it.performClick() }
            CallToolResult(content = listOf(TextContent(text = "ok")))
        }
    }

    server.addTool(
        name = "long_click",
        description = "Perform a long press on the matched node.",
        inputSchema = nodeSchema(),
    ) { request ->
        val matcher = nodeMatcherFromArgs(request.args)
        onNode(matcher) { it.performTouchInput { longClick() } }
        CallToolResult(content = listOf(TextContent(text = "ok")))
    }

    server.addTool(
        name = "double_click",
        description = "Perform a double tap on the matched node.",
        inputSchema = nodeSchema(),
    ) { request ->
        val matcher = nodeMatcherFromArgs(request.args)
        onNode(matcher) { it.performTouchInput { doubleClick() } }
        CallToolResult(content = listOf(TextContent(text = "ok")))
    }

    server.addTool(
        name = "text_input",
        description = "Append text to the focused text field matched by the node selector.",
        inputSchema = nodeSchema(
            extra = {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "Text to append.")
                }
            },
            required = listOf("text"),
        ),
    ) { request ->
        val matcher = nodeMatcherFromArgs(request.args)
        val text = requireNotNull(request.args.str("text")) { "Missing 'text' parameter" }
        onNode(matcher) { it.performTextInput(text) }
        CallToolResult(content = listOf(TextContent(text = "ok")))
    }

    server.addTool(
        name = "text_replacement",
        description = "Replace all text in the matched text field with new text.",
        inputSchema = nodeSchema(
            extra = {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "Replacement text.")
                }
            },
            required = listOf("text"),
        ),
    ) { request ->
        val matcher = nodeMatcherFromArgs(request.args)
        val text = requireNotNull(request.args.str("text")) { "Missing 'text' parameter" }
        onNode(matcher) { it.performTextReplacement(text) }
        CallToolResult(content = listOf(TextContent(text = "ok")))
    }

    server.addTool(
        name = "text_clearance",
        description = "Clear all text from the matched text field.",
        inputSchema = nodeSchema(),
    ) { request ->
        val matcher = nodeMatcherFromArgs(request.args)
        onNode(matcher) { it.performTextClearance() }
        CallToolResult(content = listOf(TextContent(text = "ok")))
    }

    server.addTool(
        name = "scroll_to",
        description = "Scroll the matched node into view.",
        inputSchema = nodeSchema(),
    ) { request ->
        val matcher = nodeMatcherFromArgs(request.args)
        onNode(matcher) { it.performScrollTo() }
        CallToolResult(content = listOf(TextContent(text = "ok")))
    }

    server.addTool(
        name = "swipe",
        description = "Perform a swipe gesture on the matched node in the given direction (UP, DOWN, LEFT, RIGHT).",
        inputSchema = nodeSchema(
            extra = {
                putJsonObject("direction") {
                    put("type", "string")
                    put("description", "Swipe direction: UP, DOWN, LEFT, or RIGHT.")
                }
            },
            required = listOf("direction"),
        ),
    ) { request ->
        val matcher = nodeMatcherFromArgs(request.args)
        val direction = requireNotNull(request.args.str("direction")) { "Missing 'direction' parameter" }.uppercase()
        onNode(matcher) { node ->
            node.performTouchInput {
                when (direction) {
                    "UP" -> swipeUp()
                    "DOWN" -> swipeDown()
                    "LEFT" -> swipeLeft()
                    "RIGHT" -> swipeRight()
                    else -> throw IllegalArgumentException("Unknown direction: $direction")
                }
            }
        }
        CallToolResult(content = listOf(TextContent(text = "ok")))
    }

    server.addTool(
        name = "navigate_back",
        description = "Trigger a back navigation event in the Compose UI.",
        inputSchema = emptySchema(),
    ) { _ ->
        withContext(runTestContext) {
            test.waitForIdle()
            val input = DirectNavigationEventInput()
            navigationEventDispatcher.addInput(input)
            input.backCompleted()
            test.waitForIdle()
        }
        CallToolResult(content = listOf(TextContent(text = "ok")))
    }

    server.addTool(
        name = "pointer_down",
        description = "Press a touch pointer at the center of the matched node.",
        inputSchema = nodeSchema(
            extra = {
                putJsonObject("pointerId") {
                    put("type", "integer")
                    put("description", "Pointer ID for multi-touch (default 0).")
                }
            },
        ),
    ) { request ->
        val matcher = nodeMatcherFromArgs(request.args)
        val pointerId = request.args.str("pointerId")?.toInt() ?: 0
        onNode(matcher) { it.performTouchInput { down(pointerId, center) } }
        CallToolResult(content = listOf(TextContent(text = "ok")))
    }

    server.addTool(
        name = "pointer_move_by",
        description = "Move a touch pointer by an offset (x, y) relative to its current position.",
        inputSchema = nodeSchema(
            extra = {
                putJsonObject("x") { put("type", "number"); put("description", "Horizontal offset in pixels.") }
                putJsonObject("y") { put("type", "number"); put("description", "Vertical offset in pixels.") }
                putJsonObject("pointerId") { put("type", "integer"); put("description", "Pointer ID (default 0).") }
            },
            required = listOf("x", "y"),
        ),
    ) { request ->
        val matcher = nodeMatcherFromArgs(request.args)
        val x = requireNotNull(request.args.str("x")) { "Missing 'x' parameter" }.toFloat()
        val y = requireNotNull(request.args.str("y")) { "Missing 'y' parameter" }.toFloat()
        val pointerId = request.args.str("pointerId")?.toInt() ?: 0
        onNode(matcher) { it.performTouchInput { moveBy(pointerId, androidx.compose.ui.geometry.Offset(x, y)) } }
        CallToolResult(content = listOf(TextContent(text = "ok")))
    }

    server.addTool(
        name = "pointer_move_to",
        description = "Move a touch pointer to an absolute position (x, y).",
        inputSchema = nodeSchema(
            extra = {
                putJsonObject("x") { put("type", "number"); put("description", "Absolute x position in pixels.") }
                putJsonObject("y") { put("type", "number"); put("description", "Absolute y position in pixels.") }
                putJsonObject("pointerId") { put("type", "integer"); put("description", "Pointer ID (default 0).") }
            },
            required = listOf("x", "y"),
        ),
    ) { request ->
        val matcher = nodeMatcherFromArgs(request.args)
        val x = requireNotNull(request.args.str("x")) { "Missing 'x' parameter" }.toFloat()
        val y = requireNotNull(request.args.str("y")) { "Missing 'y' parameter" }.toFloat()
        val pointerId = request.args.str("pointerId")?.toInt() ?: 0
        onNode(matcher) { it.performTouchInput { moveTo(pointerId, androidx.compose.ui.geometry.Offset(x, y)) } }
        CallToolResult(content = listOf(TextContent(text = "ok")))
    }

    server.addTool(
        name = "pointer_up",
        description = "Release a touch pointer.",
        inputSchema = nodeSchema(
            extra = {
                putJsonObject("pointerId") { put("type", "integer"); put("description", "Pointer ID (default 0).") }
            },
        ),
    ) { request ->
        val matcher = nodeMatcherFromArgs(request.args)
        val pointerId = request.args.str("pointerId")?.toInt() ?: 0
        onNode(matcher) { it.performTouchInput { up(pointerId) } }
        CallToolResult(content = listOf(TextContent(text = "ok")))
    }
}
