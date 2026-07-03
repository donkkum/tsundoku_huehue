package eu.kanade.domain.manga.interactor

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Offline metadata extraction for local ebook files.
 *
 * Reads ONLY the file's own bytes — never touches the network. Modelled on the way calibre reads
 * embedded metadata (EPUB OPF / Dublin Core, MOBI EXTH records, PDF `/Info` dictionary).
 *
 * EPUB is handled separately by [ParseEpubPreview] (deep OPF + cover + calibre:series parsing).
 * This helper covers MOBI/AZW/AZW3, PDF and plain-text; anything it can't decode falls back to the
 * filename-derived title so an import never fails just because metadata is missing or unusual.
 */
object LocalFileMetadata {

    data class Metadata(
        val title: String,
        val author: String?,
        val series: String?,
        val description: String?,
        /** Comma-separated tags, or null. */
        val genres: String?,
    )

    /** PDFs can be huge; only the first N bytes are scanned for the (usually early) `/Info` dict. */
    private const val PDF_READ_CAP = 16 * 1024 * 1024

    /** MOBI/AZW files are typically small; guard against pathological sizes anyway. */
    private const val MOBI_READ_CAP = 48 * 1024 * 1024

    fun extract(context: Context, uri: Uri, fileName: String): Metadata {
        val fallbackTitle = fileName.substringBeforeLast('.', fileName).trim().ifBlank { fileName }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return runCatching {
            when (ext) {
                "mobi", "azw", "azw3" -> extractMobi(context, uri, fallbackTitle)
                "pdf" -> extractPdf(context, uri, fallbackTitle)
                else -> Metadata(fallbackTitle, null, null, null, null)
            }
        }.getOrDefault(Metadata(fallbackTitle, null, null, null, null))
    }

    // ── MOBI / AZW / AZW3 (PalmDB + MOBI header + EXTH records) ────────────────

    private fun extractMobi(context: Context, uri: Uri, fallbackTitle: String): Metadata {
        val bytes = readBytesCapped(context, uri, MOBI_READ_CAP)
            ?: return Metadata(fallbackTitle, null, null, null, null)
        if (bytes.size < 80) return Metadata(fallbackTitle, null, null, null, null)

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        // PalmDB database name lives in the first 32 bytes (null-terminated, latin1) — a common
        // title fallback when there's no updated-title EXTH record.
        val palmName = String(bytes, 0, 32, Charsets.ISO_8859_1).substringBefore('\u0000').trim()

        val numRecords = buf.getShort(76).toInt() and 0xFFFF
        if (numRecords < 1 || 78 + 4 > bytes.size) {
            return Metadata(palmName.ifBlank { fallbackTitle }, null, null, null, null)
        }
        val record0Offset = buf.getInt(78)

        var title: String? = null
        val authors = mutableListOf<String>()
        var description: String? = null
        val subjects = mutableListOf<String>()

        val mobiStart = record0Offset + 16
        if (mobiStart + 8 <= bytes.size && String(bytes, mobiStart, 4, Charsets.ISO_8859_1) == "MOBI") {
            val mobiHeaderLen = buf.getInt(mobiStart + 4)
            val exthFlagsOffset = record0Offset + 0x80
            val exthFlags = if (exthFlagsOffset + 4 <= bytes.size) buf.getInt(exthFlagsOffset) else 0

            if (exthFlags and 0x40 != 0 && mobiHeaderLen > 0) {
                val exthStart = mobiStart + mobiHeaderLen
                if (exthStart + 12 <= bytes.size &&
                    String(bytes, exthStart, 4, Charsets.ISO_8859_1) == "EXTH"
                ) {
                    val recordCount = buf.getInt(exthStart + 8)
                    var p = exthStart + 12
                    var read = 0
                    while (read < recordCount && p + 8 <= bytes.size) {
                        val type = buf.getInt(p)
                        val len = buf.getInt(p + 4)
                        if (len < 8 || p + len > bytes.size) break
                        val data = String(bytes, p + 8, len - 8, Charsets.UTF_8).trim()
                        if (data.isNotBlank()) {
                            when (type) {
                                100 -> authors += data // author / creator (may repeat)
                                103 -> if (description == null) description = data
                                105 -> subjects += data // subject / tags (may repeat)
                                503 -> if (title == null) title = data // updated title
                            }
                        }
                        p += len
                        read++
                    }
                }
            }
        }

        val finalTitle = (title ?: palmName).ifBlank { fallbackTitle }
        val author = authors.distinct().joinToString(", ").takeIf { it.isNotBlank() }
        val genres = subjects.distinct().joinToString(", ").takeIf { it.isNotBlank() }
        // MOBI has no standard series EXTH record; series is left to filename/OPF-based grouping.
        return Metadata(finalTitle, author, null, description, genres)
    }

    // ── PDF (`/Info` dictionary, best-effort) ──────────────────────────────────

    private fun extractPdf(context: Context, uri: Uri, fallbackTitle: String): Metadata {
        val bytes = readBytesCapped(context, uri, PDF_READ_CAP)
            ?: return Metadata(fallbackTitle, null, null, null, null)
        // Latin1 keeps a 1:1 byte↔char mapping so byte offsets and string indices line up.
        val content = String(bytes, Charsets.ISO_8859_1)
        val title = readPdfInfoValue(content, "Title")?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val author = readPdfInfoValue(content, "Author")?.takeIf { it.isNotBlank() }
        return Metadata(title, author, null, null, null)
    }

    /** Finds `/Key` in the raw PDF and decodes the literal `(...)` or hex `<...>` string after it. */
    private fun readPdfInfoValue(content: String, key: String): String? {
        val marker = "/$key"
        var idx = content.indexOf(marker)
        while (idx >= 0) {
            var i = idx + marker.length
            while (i < content.length && content[i].isWhitespace()) i++
            if (i < content.length) {
                when (content[i]) {
                    '(' -> parsePdfLiteralString(content, i)?.let { return it }
                    '<' -> parsePdfHexString(content, i)?.let { return it }
                }
            }
            idx = content.indexOf(marker, idx + marker.length)
        }
        return null
    }

    private fun parsePdfLiteralString(content: String, openParenIndex: Int): String? {
        val raw = StringBuilder()
        var i = openParenIndex + 1
        var depth = 1
        while (i < content.length) {
            val c = content[i]
            when (c) {
                '\\' -> {
                    if (i + 1 >= content.length) break
                    when (val next = content[i + 1]) {
                        'n' -> { raw.append('\n'); i += 2 }
                        'r' -> { raw.append('\r'); i += 2 }
                        't' -> { raw.append('\t'); i += 2 }
                        'b' -> { raw.append('\b'); i += 2 }
                        'f' -> { raw.append('\u000C'); i += 2 }
                        '(', ')', '\\' -> { raw.append(next); i += 2 }
                        '\n' -> i += 2 // line continuation
                        '\r' -> i += if (i + 2 < content.length && content[i + 2] == '\n') 3 else 2
                        in '0'..'7' -> {
                            var j = i + 1
                            val oct = StringBuilder()
                            while (j < content.length && oct.length < 3 && content[j] in '0'..'7') {
                                oct.append(content[j]); j++
                            }
                            raw.append((oct.toString().toInt(8) and 0xFF).toChar())
                            i = j
                        }
                        else -> { raw.append(next); i += 2 }
                    }
                }
                '(' -> { depth++; raw.append(c); i++ }
                ')' -> { depth--; if (depth == 0) { i++; break }; raw.append(c); i++ }
                else -> { raw.append(c); i++ }
            }
        }
        return decodePdfText(raw.toString())
    }

    private fun parsePdfHexString(content: String, openAngleIndex: Int): String? {
        val end = content.indexOf('>', openAngleIndex + 1)
        if (end < 0) return null
        val hex = content.substring(openAngleIndex + 1, end).filter { !it.isWhitespace() }
        if (hex.isEmpty()) return null
        val padded = if (hex.length % 2 == 0) hex else hex + "0"
        val out = ByteArray(padded.length / 2)
        for (k in out.indices) {
            val byte = padded.substring(k * 2, k * 2 + 2).toIntOrNull(16) ?: return null
            out[k] = byte.toByte()
        }
        return decodePdfBytes(out)
    }

    /** A PDF text string is UTF-16BE when it starts with the FE FF byte-order mark, else PDFDocEncoding≈Latin1. */
    private fun decodePdfText(latin1: String): String {
        if (latin1.length >= 2 && latin1[0] == 'þ' && latin1[1] == 'ÿ') {
            val bytes = ByteArray(latin1.length) { (latin1[it].code and 0xFF).toByte() }
            return decodePdfBytes(bytes)
        }
        return latin1.trim()
    }

    private fun decodePdfBytes(bytes: ByteArray): String {
        val charset: Charset = if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            Charsets.UTF_16BE
        } else {
            Charsets.ISO_8859_1
        }
        val start = if (charset == Charsets.UTF_16BE) 2 else 0
        return String(bytes, start, bytes.size - start, charset).trim()
    }

    // ── Shared IO ──────────────────────────────────────────────────────────────

    private fun readBytesCapped(context: Context, uri: Uri, cap: Int): ByteArray? {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (total < cap) {
                val r = input.read(chunk)
                if (r == -1) break
                out.write(chunk, 0, r)
                total += r
            }
            out.toByteArray()
        }
    }
}
