package io.github.jdemeulenaere.compose.driver.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.Properties
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val emptyJsonObject = JsonObject(emptyMap())

// arguments is nullable in the Kotlin SDK despite the MCP spec requiring it
private val CallToolRequest.args: JsonObject get() = arguments ?: emptyJsonObject

fun runMcpServer() {
    val server = Server(
        serverInfo = Implementation(name = "compose-driver", version = composeDriverVersion()),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())
        )
    )

    registerTools(server)

    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered(),
    )

    runBlocking {
        server.createSession(transport)
        val done = Job()
        server.onClose { done.complete() }
        done.join()
    }
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

// --- HTTP helpers ---

private fun get(path: String, port: Int = 8080): Pair<Int, ByteArray> {
    val conn = URI.create("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    val code = conn.responseCode
    val bytes = if (code == 200) conn.inputStream.readBytes() else conn.errorStream?.readBytes() ?: ByteArray(0)
    conn.disconnect()
    return code to bytes
}

private fun buildPath(endpoint: String, params: Map<String, String?>): String {
    val query = params.entries
        .filter { it.value != null }
        .joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
    return if (query.isEmpty()) endpoint else "$endpoint?$query"
}

private fun JsonObject.str(key: String): String? =
    (get(key) as? JsonPrimitive)?.content

private fun nodeParams(args: JsonObject): Map<String, String?> = mapOf(
    "nodeTag" to args.str("nodeTag"),
    "nodeText" to args.str("nodeText"),
    "nodeTextSubstring" to args.str("nodeTextSubstring"),
    "nodeTextIgnoreCase" to args.str("nodeTextIgnoreCase"),
)

private fun textResult(code: Int, body: ByteArray): CallToolResult {
    val text = body.toString(Charsets.UTF_8)
    return if (code == 200) {
        CallToolResult(content = listOf(TextContent(text = text)))
    } else {
        CallToolResult(content = listOf(TextContent(text = "Error ($code): $text")), isError = true)
    }
}

private fun imageResult(code: Int, body: ByteArray, mimeType: String): CallToolResult {
    return if (code == 200) {
        val encoded = Base64.getEncoder().encodeToString(body)
        CallToolResult(content = listOf(ImageContent(data = encoded, mimeType = mimeType)))
    } else {
        val text = body.toString(Charsets.UTF_8)
        CallToolResult(content = listOf(TextContent(text = "Error ($code): $text")), isError = true)
    }
}

// --- Input schema helpers ---

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

private fun nodeSchema(
    extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null,
    required: List<String> = emptyList(),
): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        nodeSelectionProperties.forEach { key, value -> put(key, value) }
        extra?.invoke(this)
    },
    required = required,
)

private fun emptySchema(): ToolSchema = ToolSchema()

// --- Tool registration ---

private fun registerTools(server: Server) {

    server.addTool(
        name = "status",
        description = "Health check — returns 'ok' when the compose-driver HTTP server is running.",
        inputSchema = emptySchema(),
    ) { _ ->
        val (code, body) = get("/status")
        textResult(code, body)
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
                        "Fully-qualified composable name, e.g. com.example.FooKt.FooScreen. Omit to reset current composable."
                    )
                }
            },
        ),
    ) { request ->
        val params = mapOf("composable" to request.args.str("composable"))
        val (code, body) = get(buildPath("/reset", params))
        textResult(code, body)
    }

    server.addTool(
        name = "screenshot",
        description = "Capture a PNG screenshot of the matched node, or the whole UI if no node is specified.",
        inputSchema = nodeSchema(),
    ) { request ->
        val (code, body) = get(buildPath("/screenshot", nodeParams(request.args)))
        imageResult(code, body, "image/png")
    }

    server.addTool(
        name = "print_tree",
        description = "Print the semantic tree of the matched node (or root). Useful for discovering node tags and text.",
        inputSchema = nodeSchema(),
    ) { request ->
        val (code, body) = get(buildPath("/printTree", nodeParams(request.args)))
        textResult(code, body)
    }

    server.addTool(
        name = "wait_for_idle",
        description = "Wait for the Compose UI to become idle (all recompositions and animations settled).",
        inputSchema = nodeSchema(),
    ) { request ->
        val (code, body) = get(buildPath("/waitForIdle", nodeParams(request.args)))
        textResult(code, body)
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
        val params = nodeParams(request.args) + mapOf("timeout" to request.args.str("timeout"))
        val (code, body) = get(buildPath("/waitForNode", params))
        textResult(code, body)
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
                        "If set (0–5000), returns a GIF recording the UI for this many milliseconds after the click."
                    )
                }
            },
        ),
    ) { request ->
        val gifMs = request.args.str("gifDurationMs")
        val params = nodeParams(request.args) + mapOf("gifDurationMs" to gifMs)
        val (code, body) = get(buildPath("/click", params))
        if (gifMs != null && code == 200) imageResult(code, body, "image/gif") else textResult(code, body)
    }

    server.addTool(
        name = "long_click",
        description = "Perform a long press on the matched node.",
        inputSchema = nodeSchema(),
    ) { request ->
        val (code, body) = get(buildPath("/longClick", nodeParams(request.args)))
        textResult(code, body)
    }

    server.addTool(
        name = "double_click",
        description = "Perform a double tap on the matched node.",
        inputSchema = nodeSchema(),
    ) { request ->
        val (code, body) = get(buildPath("/doubleClick", nodeParams(request.args)))
        textResult(code, body)
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
        val params = nodeParams(request.args) + mapOf("text" to request.args.str("text"))
        val (code, body) = get(buildPath("/textInput", params))
        textResult(code, body)
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
        val params = nodeParams(request.args) + mapOf("text" to request.args.str("text"))
        val (code, body) = get(buildPath("/textReplacement", params))
        textResult(code, body)
    }

    server.addTool(
        name = "text_clearance",
        description = "Clear all text from the matched text field.",
        inputSchema = nodeSchema(),
    ) { request ->
        val (code, body) = get(buildPath("/textClearance", nodeParams(request.args)))
        textResult(code, body)
    }

    server.addTool(
        name = "scroll_to",
        description = "Scroll the matched node into view.",
        inputSchema = nodeSchema(),
    ) { request ->
        val (code, body) = get(buildPath("/scrollTo", nodeParams(request.args)))
        textResult(code, body)
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
        val params = nodeParams(request.args) + mapOf("direction" to request.args.str("direction"))
        val (code, body) = get(buildPath("/swipe", params))
        textResult(code, body)
    }

    server.addTool(
        name = "navigate_back",
        description = "Trigger a back navigation event in the Compose UI.",
        inputSchema = emptySchema(),
    ) { _ ->
        val (code, body) = get("/navigateBack")
        textResult(code, body)
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
        val params = nodeParams(request.args) + mapOf("pointerId" to request.args.str("pointerId"))
        val (code, body) = get(buildPath("/pointerInput/down", params))
        textResult(code, body)
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
        val params = nodeParams(request.args) + mapOf(
            "x" to request.args.str("x"),
            "y" to request.args.str("y"),
            "pointerId" to request.args.str("pointerId"),
        )
        val (code, body) = get(buildPath("/pointerInput/moveBy", params))
        textResult(code, body)
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
        val params = nodeParams(request.args) + mapOf(
            "x" to request.args.str("x"),
            "y" to request.args.str("y"),
            "pointerId" to request.args.str("pointerId"),
        )
        val (code, body) = get(buildPath("/pointerInput/moveTo", params))
        textResult(code, body)
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
        val params = nodeParams(request.args) + mapOf("pointerId" to request.args.str("pointerId"))
        val (code, body) = get(buildPath("/pointerInput/up", params))
        textResult(code, body)
    }
}
