package io.github.jdemeulenaere.compose.driver.mcp

import io.github.jdemeulenaere.compose.driver.startComposeDriverServer
import java.net.HttpURLConnection
import java.net.URI
import kotlin.concurrent.thread

fun main() {
    thread(isDaemon = true, name = "compose-driver-http") {
        startComposeDriverServer()
    }

    waitForHttpServer()
    runMcpServer()
}

private fun waitForHttpServer(port: Int = 8080, maxAttempts: Int = 60, delayMs: Long = 500) {
    repeat(maxAttempts) { attempt ->
        try {
            val connection = URI.create("http://localhost:$port/status").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            if (connection.responseCode == 200) return
        } catch (_: Exception) {
            // Not ready yet
        }
        if (attempt < maxAttempts - 1) Thread.sleep(delayMs)
    }
    error("compose-driver HTTP server did not start within ${maxAttempts * delayMs}ms")
}
