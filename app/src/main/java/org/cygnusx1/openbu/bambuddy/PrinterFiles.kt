package org.cygnusx1.openbu.bambuddy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.cygnusx1.openbu.direct.DirectPrinter
import org.cygnusx1.openbu.direct.JobFiles
import org.cygnusx1.openbu.direct.PlatePicture
import org.cygnusx1.openbu.direct.PrinterFtps
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.text.DateFormat
import java.util.Date

/**
 * The files on a printer's storage, reached through Bambuddy or directly (FTPS). The file screen,
 * downloads and the 3D viewer only talk to this, so they work the same with either connection.
 * All calls block; run them off the main thread.
 */
interface PrinterFiles {
    fun list(path: String): List<BbFile>
    /** "12.3 GB free of 29.1 GB" or similar, or null when it isn't known. */
    fun storage(): String?
    fun delete(file: BbFile)
    /** Opens a file for reading, with its size when known. The caller closes the stream. */
    fun open(path: String): Pair<InputStream, Long?>
    /** The plate picture inside a .3mf, or null when there is none. */
    fun thumbnail(path: String): Bitmap?
    /** The last raw reply, for the (i) button. */
    val lastRaw: String
}

/** Files through the Bambuddy server. */
class BambuddyFileSource(private val client: BambuddyClient, private val printerId: Int) : PrinterFiles {
    @Volatile override var lastRaw: String = ""
        private set

    override fun list(path: String): List<BbFile> {
        val body = client.listFiles(printerId, path)
        lastRaw = body
        return parseFiles(body, path)
    }

    override fun storage(): String? {
        val body = client.storage(printerId)
        lastRaw = body
        return parseStorage(body)
    }

    override fun delete(file: BbFile) = client.deleteFile(printerId, file.path)

    override fun open(path: String): Pair<InputStream, Long?> {
        val conn = client.openFileDownload(printerId, path)
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw BambuddyException(code, "Download failed (HTTP $code)")
        }
        val stream = object : FilterInputStream(conn.inputStream) {
            override fun close() {
                runCatching { super.close() }
                conn.disconnect()
            }
        }
        return stream to conn.contentLengthLong.takeIf { it > 0 }
    }

    override fun thumbnail(path: String): Bitmap? {
        val plate = parsePlateIndexes(client.filePlates(printerId, path)).first()
        val conn = client.openPlateThumbnail(printerId, path, plate, client.cameraToken())
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw BambuddyException(code, "Preview not available (HTTP $code)")
            return conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }
}

/** Files straight from the printer's storage over FTPS (direct connection). */
class DirectFileSource(printer: DirectPrinter) : PrinterFiles {
    private val ftps = PrinterFtps(printer.ip, printer.accessCode)

    @Volatile override var lastRaw: String = ""
        private set

    override fun list(path: String): List<BbFile> {
        val entries = ftps.list(path)
        lastRaw = entries.joinToString("\n") { "${if (it.isDir) "d" else "-"} ${it.size ?: ""} ${it.name}" }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        return entries.map { e ->
            BbFile(
                name = e.name,
                path = if (path.endsWith("/")) path + e.name else "$path/${e.name}",
                isDir = e.isDir,
                size = e.size,
                modified = e.time?.let { fmt.format(Date(it)) },
                time = e.time,
            )
        }
    }

    /**
     * The plate picture of the print that's running: finds its .3mf on the printer by the job
     * name (in the storage root, /cache or /model) and reads the picture from its start. The
     * plate number comes from the printer's file name ("/data/Metadata/plate_2.gcode").
     */
    fun jobPicture(job: String, jobFile: String?): Bitmap? {
        val path = JobFiles.find(ftps, job) ?: return null
        val (input, _) = ftps.download(path)
        val png = input.use { PlatePicture.find(it, JobFiles.plateNumber(jobFile)) } ?: return null
        return BitmapFactory.decodeByteArray(png, 0, png.size)
    }

    /** The printer's free space isn't available over FTP. */
    override fun storage(): String? = null

    override fun delete(file: BbFile) = ftps.delete(file.path, file.isDir)

    override fun open(path: String): Pair<InputStream, Long?> = ftps.download(path)

    /**
     * A .3mf is a zip, and Bambu's slicers put the plate pictures (Metadata/plate_N.png) near the
     * start, so only the first part of the file is read (see [PlatePicture]).
     */
    override fun thumbnail(path: String): Bitmap? {
        if (!path.endsWith(".3mf", ignoreCase = true)) return null
        // "..._plate_2.gcode.3mf" was sliced from plate 2: prefer that plate's picture.
        val wanted = Regex("plate_(\\d+)").findAll(path.substringAfterLast('/')).lastOrNull()?.groupValues?.get(1)
        val (input, _) = ftps.download(path)
        val png = input.use { PlatePicture.find(it, wanted) } ?: return null
        return BitmapFactory.decodeByteArray(png, 0, png.size) ?: throw IOException("The preview couldn't be read")
    }
}
