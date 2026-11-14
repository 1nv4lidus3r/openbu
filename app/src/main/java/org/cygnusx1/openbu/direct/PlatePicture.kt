package org.cygnusx1.openbu.direct

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.Inflater

/**
 * Finds the plate picture (Metadata/plate_N.png) at the start of a .3mf while it is still
 * downloading, so the whole file (often many MB of G-code) doesn't have to be fetched.
 *
 * Bambu's slicers write the zip entries with "data descriptors": the sizes are only known after
 * each entry, so java.util.zip.ZipInputStream refuses the stored (uncompressed) pictures. Instead
 * this walks the local headers itself: compressed entries end where the deflate stream ends, and
 * a stored PNG ends at its IEND chunk. Anything else it can't measure stops the search.
 *
 * Returns the PNG bytes of plate [wanted] (or of the first plate picture when [wanted] is null or
 * isn't found before the search has to stop), or null.
 */
object PlatePicture {
    private const val LOCAL_HEADER = 0x04034b50
    private const val DESCRIPTOR = 0x08074b50
    private val PLATE = Regex("^metadata/plate_(\\d+)\\.png$")

    fun find(input: InputStream, wanted: String?): ByteArray? {
        val pin = PushbackInputStream(input, 64 * 1024)
        var first: ByteArray? = null
        while (true) {
            val (name, data) = nextEntry(pin) { PLATE.matches(it) } ?: return first
            val plate = PLATE.find(name)?.groupValues?.get(1) ?: continue
            if (data == null) continue
            if (wanted == null || plate == wanted) return data
            if (first == null) first = data
        }
    }

    /**
     * Returns the contents of the entry called [entryName] (e.g. "Metadata/plate_1.json", any
     * case), as long as it comes before anything that can't be measured while streaming.
     */
    fun findEntry(input: InputStream, entryName: String): ByteArray? {
        val pin = PushbackInputStream(input, 64 * 1024)
        val wanted = entryName.lowercase()
        while (true) {
            val (name, data) = nextEntry(pin) { it == wanted } ?: return null
            if (name == wanted) return data
        }
    }

    /**
     * Reads the next entry: its lowercase name, and its contents when [keep] says so. Returns null
     * at the end of the entries or when this entry's length can't be told.
     */
    private fun nextEntry(pin: PushbackInputStream, keep: (String) -> Boolean): Pair<String, ByteArray?>? {
        val sig = readIntLE(pin) ?: return null
        if (sig != LOCAL_HEADER) return null // central directory reached, or something unexpected
        val header = ByteArray(26)
        readFully(pin, header)
        val flags = u16(header, 2)
        val method = u16(header, 4)
        val compressedSize = u32(header, 14)
        val nameLen = u16(header, 22)
        val extraLen = u16(header, 24)
        val nameBytes = ByteArray(nameLen)
        readFully(pin, nameBytes)
        skipFully(pin, extraLen.toLong())
        val name = String(nameBytes, Charsets.UTF_8).lowercase()
        val hasDescriptor = flags and 0x08 != 0
        val wantIt = keep(name)
        val data: ByteArray? = when {
            method == 8 -> inflate(pin, keep = wantIt)
            method == 0 && !hasDescriptor -> ByteArray(compressedSize.toInt()).also { readFully(pin, it) }
            method == 0 && name.endsWith(".png") -> readPng(pin)
            else -> return null // can't tell where this entry ends
        }
        if (hasDescriptor) skipDescriptor(pin)
        return name to (if (wantIt) data else null)
    }

    /** Inflates one deflate stream, pushing back what the inflater read past its end. */
    private fun inflate(pin: PushbackInputStream, keep: Boolean): ByteArray? {
        val inflater = Inflater(true)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var lastRead = 0
        val res = ByteArray(16 * 1024)
        try {
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    val n = pin.read(buf)
                    if (n < 0) throw EOFException("Truncated .3mf")
                    lastRead = n
                    inflater.setInput(buf, 0, n)
                }
                val m = inflater.inflate(res)
                if (keep && m > 0) out.write(res, 0, m)
                if (m == 0 && inflater.needsDictionary()) throw IllegalStateException("Unsupported .3mf")
            }
            // Whatever the inflater didn't use belongs to the next part of the file.
            val left = inflater.remaining
            if (left > 0) pin.unread(buf, lastRead - left, left)
        } finally {
            inflater.end()
        }
        return if (keep) out.toByteArray() else null
    }

    /** Reads one PNG (signature, then chunks up to and including IEND). */
    private fun readPng(pin: PushbackInputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val sig = ByteArray(8)
        readFully(pin, sig)
        out.write(sig)
        while (true) {
            val lenBytes = ByteArray(4)
            readFully(pin, lenBytes)
            val len = ((lenBytes[0].toInt() and 0xFF) shl 24) or ((lenBytes[1].toInt() and 0xFF) shl 16) or
                ((lenBytes[2].toInt() and 0xFF) shl 8) or (lenBytes[3].toInt() and 0xFF)
            if (len < 0 || len > 64 * 1024 * 1024) throw IllegalStateException("Unreadable picture")
            val rest = ByteArray(4 + len + 4) // type, data, crc
            readFully(pin, rest)
            out.write(lenBytes)
            out.write(rest)
            if (rest[0] == 'I'.code.toByte() && rest[1] == 'E'.code.toByte() &&
                rest[2] == 'N'.code.toByte() && rest[3] == 'D'.code.toByte()
            ) {
                return out.toByteArray()
            }
        }
    }

    /** Data descriptor: optional signature, CRC, sizes (4 + 4 + 4, or 8 + 8 for zip64; Bambu uses 4). */
    private fun skipDescriptor(pin: PushbackInputStream) {
        val first = ByteArray(4)
        readFully(pin, first)
        if (le(first) == DESCRIPTOR) skipFully(pin, 12) else skipFully(pin, 8)
    }

    private fun le(b: ByteArray): Int =
        (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)

    private fun readIntLE(pin: InputStream): Int? {
        val b = ByteArray(4)
        var off = 0
        while (off < 4) {
            val n = pin.read(b, off, 4 - off)
            if (n < 0) return null
            off += n
        }
        return le(b)
    }

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24))

    private fun readFully(input: InputStream, b: ByteArray) = DataInputStream(input).readFully(b)

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) {
                if (input.read() < 0) throw EOFException("Truncated .3mf")
                left--
            } else {
                left -= s
            }
        }
    }
}
