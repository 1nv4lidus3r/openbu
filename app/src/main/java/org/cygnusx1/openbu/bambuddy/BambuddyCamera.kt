package org.cygnusx1.openbu.bambuddy

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Live camera for one printer, streamed from the Bambuddy server as MJPEG.
 * Nothing is requested until this composable is shown, and the stream is closed as soon as it
 * leaves the screen or the app goes to the background (onClose is called so the caller can
 * flip its camera switch back off).
 */
@Composable
fun BambuddyCameraView(
    printerId: Int,
    vm: BambuddyViewModel,
    onClose: () -> Unit,
) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // Leaving the app stops the camera; it stays off until the icon is tapped again.
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose { onClose() }
    }

    LaunchedEffect(printerId) {
        frame = null
        message = null
        val client = vm.currentClient()
        if (client == null) {
            message = "No server configured"
            return@LaunchedEffect
        }
        val viewer = "bammate" + (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        try {
            withContext(Dispatchers.IO) {
                val token = client.cameraToken()
                val conn = client.openCameraStream(printerId, token, viewer)
                // The blocking read below can't see coroutine cancellation, so close the socket instead.
                val handle = coroutineContext.job.invokeOnCompletion { runCatching { conn.disconnect() } }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        throw BambuddyException(code, "Camera request failed (HTTP $code)")
                    }
                    val reader = MjpegReader(conn.inputStream)
                    while (true) {
                        ensureActive()
                        val jpeg = reader.nextFrame() ?: break
                        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                        frame = bmp.asImageBitmap()
                    }
                    message = "The camera stream ended"
                } finally {
                    handle.dispose()
                    runCatching { conn.disconnect() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (frame == null) message = "Camera unavailable: " + vm.describe(e)
        } finally {
            // Free the server's video pipe (the printer only allows a couple of connections).
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { client.stopCamera(printerId, viewer) }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val f = frame
        if (f != null) {
            Image(
                bitmap = f,
                contentDescription = "Camera",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                text = message ?: "Starting camera...",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Reads JPEG frames out of a multipart/x-mixed-replace (MJPEG) stream. Uses each part's
 * Content-Length when present, and otherwise scans for the JPEG end marker.
 */
private class MjpegReader(input: InputStream) {
    private val stream = input.buffered(64 * 1024)

    private fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val b = stream.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 4096) return sb.toString() // not a header line; don't buffer forever
        }
    }

    fun nextFrame(): ByteArray? {
        var length = -1
        var sawHeader = false
        while (true) {
            val line = readLine() ?: return null
            when {
                line.isEmpty() -> if (sawHeader) break
                line.startsWith("--") -> sawHeader = false
                else -> {
                    sawHeader = true
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        length = line.substringAfter(':').trim().toIntOrNull() ?: -1
                    }
                }
            }
        }
        if (length in 1..MAX_FRAME) {
            val data = ByteArray(length)
            var off = 0
            while (off < length) {
                val n = stream.read(data, off, length - off)
                if (n < 0) return null
                off += n
            }
            return data
        }
        // No usable length: copy bytes up to and including the JPEG end marker (FF D9).
        val out = ByteArrayOutputStream(64 * 1024)
        var prev = -1
        while (out.size() < MAX_FRAME) {
            val b = stream.read()
            if (b < 0) return null
            out.write(b)
            if (prev == 0xFF && b == 0xD9) return out.toByteArray()
            prev = b
        }
        return null
    }

    private companion object {
        const val MAX_FRAME = 8 * 1024 * 1024
    }
}
