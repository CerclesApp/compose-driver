package io.github.jdemeulenaere.compose.driver

import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.http.ContentType
import io.ktor.server.routing.RoutingContext
import java.io.OutputStream
import java.nio.file.Files
import kotlin.io.path.absolutePathString

internal expect fun writePng(image: ImageBitmap, out: OutputStream)

internal fun gifBytes(frames: List<ImageBitmap>, timeBetweenFramesMs: Long): ByteArray {
    val dir = Files.createTempDirectory("compose-driver-gif")
    try {
        frames.forEachIndexed { index, image ->
            val frameName = "frame_${index.toString().padStart(3, '0')}.png"
            Files.newOutputStream(dir.resolve(frameName)).use { writePng(image, it) }
        }

        // Make the time between frames at least 20ms, otherwise browsers will slow down the GIF.
        val framerate = 1_000.0 / timeBetweenFramesMs.coerceAtLeast(20L)
        val outputGif = dir.resolve("output.gif")
        val cmd =
            listOf(
                "ffmpeg",
                "-y",
                "-framerate",
                framerate.toString(),
                "-i",
                "$dir/frame_%03d.png",
                "-vf",
                "split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse",
                outputGif.absolutePathString(),
            )

        val process = ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start()
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            val errorOutput = process.inputStream.bufferedReader().readText()
            "${cmd.first()} failed with exit code $exitCode: $errorOutput"
        }

        return Files.readAllBytes(outputGif)
    } finally {
        dir.toFile().deleteRecursively()
    }
}

internal suspend fun RoutingContext.respondGif(
    frames: List<ImageBitmap>,
    timeBetweenFramesMs: Long,
) {
    val bytes = gifBytes(frames, timeBetweenFramesMs)
    call.respondStream(ContentType.Image.GIF) { write(bytes) }
}
