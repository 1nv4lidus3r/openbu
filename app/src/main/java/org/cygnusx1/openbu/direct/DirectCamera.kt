package org.cygnusx1.openbu.direct

import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.coroutineContext

private const val TAG = "DirectCamera"

/** X1, X1E, H2 and P2 printers stream H.264 over RTSPS; P1 and A1 send JPEG frames on port 6000. */
fun usesRtsp(model: String): Boolean {
    val m = model.uppercase()
    return m.startsWith("X1") || m.startsWith("H2") || m.startsWith("P2")
}

/**
 * Live camera straight from the printer (direct connection). Starts when shown, stops when it
 * leaves the screen or the app goes to the background ([onClose] flips the camera switch off).
 */
@Composable
fun DirectCameraView(printer: DirectPrinter, onClose: () -> Unit) {
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose { onClose() }
    }
    if (usesRtsp(printer.model)) RtspCameraView(printer) else JpegCameraView(printer)
}

@Composable
private fun CameraMessage(text: String, error: Boolean) {
    Text(
        text = text,
        modifier = Modifier.padding(12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ------------------------------------------------------------------ X1 / H2: RTSPS + H.264

@Composable
private fun RtspCameraView(printer: DirectPrinter) {
    var message by remember { mutableStateOf<String?>("Starting camera...") }
    var isError by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(16f / 9f) }
    val session = remember { RtspSession(printer) }

    DisposableEffect(Unit) { onDispose { session.stop() } }

    session.onState = { msg, err ->
        message = msg
        isError = err
    }
    session.onSize = { w, h -> if (w > 0 && h > 0) aspect = w.toFloat() / h }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = session.start(Surface(st))
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            session.stop()
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        message?.let { CameraMessage(it, isError) }
    }
}

/** Runs the RTSP client and the video decoder on a background thread for one camera view. */
private class RtspSession(private val printer: DirectPrinter) {
    @Volatile var onState: (String?, Boolean) -> Unit = { _, _ -> }
    @Volatile var onSize: (Int, Int) -> Unit = { _, _ -> }
    @Volatile private var client: RtspClient? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var stopped = false

    fun start(surface: Surface) {
        if (thread != null) return
        stopped = false
        thread = Thread({ run(surface) }, "camera-${printer.serial}").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopped = true
        client?.stop()
        thread?.interrupt()
        thread = null
    }

    private fun post(msg: String?, err: Boolean) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onState(msg, err) }
    }

    private fun run(surface: Surface) {
        val decoder = H264Decoder(surface) { w, h -> android.os.Handler(android.os.Looper.getMainLooper()).post { onSize(w, h) } }
        val c = RtspClient(printer.ip, printer.accessCode)
        client = c
        var shown = false
        c.onConfig = { sps, pps -> decoder.configure(sps, pps) }
        c.onFrame = { data, ts, key ->
            if (decoder.decode(data, ts, key) && !shown) {
                shown = true
                post(null, false)
            }
        }
        try {
            c.run()
            if (!stopped) post("The camera stream ended", true)
        } catch (e: Exception) {
            Log.w(TAG, "camera failed", e)
            if (!stopped) post("Camera unavailable: ${e.message ?: e.javaClass.simpleName}", true)
        } finally {
            decoder.release()
            runCatching { surface.release() }
        }
    }
}

/**
 * Feeds H.264 frames to Android's hardware decoder, which draws straight onto the view's surface.
 * Waits for the first key frame so the picture never starts as grey smudges.
 */
private class H264Decoder(private val surface: Surface, private val onSize: (Int, Int) -> Unit) {
    private var codec: MediaCodec? = null
    private var sawKey = false
    private val info = MediaCodec.BufferInfo()

    fun configure(sps: ByteArray, pps: ByteArray) {
        if (codec != null) return
        val start = byteArrayOf(0, 0, 0, 1)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(start + sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(start + pps))
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, surface, null, 0)
            start()
        }
    }

    /** Returns true once a frame has been drawn. */
    fun decode(data: ByteArray, timeUs: Long, key: Boolean): Boolean {
        val c = codec ?: return false
        if (!sawKey) {
            if (!key) return false
            sawKey = true
        }
        val index = c.dequeueInputBuffer(20_000)
        if (index >= 0) {
            val buf = c.getInputBuffer(index) ?: return false
            buf.clear()
            if (data.size > buf.capacity()) {
                c.queueInputBuffer(index, 0, 0, timeUs, 0)
                return false
            }
            buf.put(data)
            c.queueInputBuffer(index, 0, data.size, timeUs, 0)
        }
        var drew = false
        while (true) {
            val out = c.dequeueOutputBuffer(info, 0)
            when {
                out >= 0 -> {
                    c.releaseOutputBuffer(out, true) // show it right away (live view)
                    drew = true
                }
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    val w = f.getInteger(MediaFormat.KEY_WIDTH)
                    val h = f.getInteger(MediaFormat.KEY_HEIGHT)
                    onSize(w, h)
                }
                else -> break
            }
        }
        return drew
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }
}

// ------------------------------------------------------------------ P1 / A1: JPEG frames on port 6000

@Composable
private fun JpegCameraView(printer: DirectPrinter) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(printer) {
        try {
            withContext(Dispatchers.IO) {
                val socket = openJpegCamera(printer)
                val handle = coroutineContext.job.invokeOnCompletion { runCatching { socket.close() } }
                try {
                    val input = DataInputStream(socket.inputStream.buffered(64 * 1024))
                    val header = ByteArray(16)
                    while (true) {
                        input.readFully(header)
                        val size = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        if (size <= 0 || size > 8 * 1024 * 1024) throw IOException("Unexpected camera data")
                        val jpeg = ByteArray(size)
                        input.readFully(jpeg)
                        BitmapFactory.decodeByteArray(jpeg, 0, size)?.let { frame = it.asImageBitmap() }
                    }
                } finally {
                    handle.dispose()
                    runCatching { socket.close() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EOFException) {
            message = "The camera stream ended"
        } catch (e: Exception) {
            message = "Camera unavailable: ${e.message ?: e.javaClass.simpleName}"
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
            Image(bitmap = f, contentDescription = "Camera", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
        } else {
            CameraMessage(message ?: "Starting camera...", message != null)
        }
    }
}

/** Connects to the P1/A1 camera port and logs in (a fixed 80-byte packet with user and access code). */
private fun openJpegCamera(printer: DirectPrinter): SSLSocket {
    val tm = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
    val raw = Socket()
    raw.connect(InetSocketAddress(printer.ip, 6000), 8_000)
    val s = ctx.socketFactory.createSocket(raw, printer.ip, 6000, true) as SSLSocket
    s.soTimeout = 15_000
    s.startHandshake()
    val login = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0x40)
        putInt(0x3000)
        putInt(0)
        putInt(0)
        put("bblp".toByteArray(Charsets.US_ASCII).copyOf(32))
        put(printer.accessCode.toByteArray(Charsets.US_ASCII).copyOf(32))
    }
    s.outputStream.write(login.array())
    s.outputStream.flush()
    return s
}
