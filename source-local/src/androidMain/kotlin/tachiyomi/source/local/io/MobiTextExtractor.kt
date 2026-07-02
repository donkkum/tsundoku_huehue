package tachiyomi.source.local.io

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Extracts readable HTML from MOBI (PalmDOC) ebook files.
 *
 * Supports compression types 1 (none) and 2 (PalmDOC LZ77).
 * Huffman-compressed records (type 17480, used by newer AZW3 files) are
 * skipped — the uncompressed records that precede them usually contain
 * enough text to be useful.
 */
internal object MobiTextExtractor {

    fun extractHtml(stream: InputStream): String {
        val bytes = stream.readBytes()
        if (bytes.size < 78) return errorHtml("File too small to be a valid MOBI file.")

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        // PalmDB header: number of records is a 2-byte value at offset 76
        val numRecords = buf.getShort(76).toInt() and 0xFFFF
        if (numRecords < 2) return errorHtml("No content records found in MOBI file.")

        // Record list starts at offset 78; each entry is 8 bytes (4 offset + 4 attrs)
        val recordOffsets = IntArray(numRecords) { i ->
            buf.getInt(78 + i * 8)
        }

        val record0Offset = recordOffsets[0]
        if (record0Offset + 16 > bytes.size) return errorHtml("MOBI header record is truncated.")

        // PalmDOC header (at record 0):
        //   offset 0: compression (1=none, 2=PalmDOC, 17480=Huffman)
        //   offset 8: number of text records
        val compression = buf.getShort(record0Offset).toInt() and 0xFFFF
        val textRecordCount = buf.getShort(record0Offset + 8).toInt() and 0xFFFF

        if (textRecordCount == 0) return errorHtml("No text records found in MOBI file.")

        val content = buildString {
            for (i in 1..minOf(textRecordCount, numRecords - 1)) {
                val start = recordOffsets[i]
                val end = if (i + 1 < numRecords) recordOffsets[i + 1] else bytes.size
                if (start >= bytes.size || start >= end) continue
                val record = bytes.copyOfRange(start, minOf(end, bytes.size))

                when (compression) {
                    1 -> append(String(record, Charsets.ISO_8859_1))
                    2 -> append(decompressPalmDoc(record))
                    // Huffman (17480) — skip; we can't decode without the Huffman tables
                }
            }
        }

        if (content.isBlank()) return errorHtml("Could not extract text from MOBI file.")

        return if (content.contains("<html", ignoreCase = true) ||
            content.contains("<body", ignoreCase = true)
        ) {
            content
        } else {
            "<html><body>${escapeHtml(content).replace("\n", "<br>")}</body></html>"
        }
    }

    /**
     * PalmDOC LZ77 decompression.
     *
     * Byte ranges:
     *  0x00        → literal null byte
     *  0x01–0x08   → copy that many raw bytes that follow
     *  0x09–0x7F   → literal byte
     *  0x80–0xBF   → two-byte back-reference; 14-bit value encodes dist (11 bits) and len+3 (3 bits)
     *  0xC0–0xFF   → space + (byte XOR 0x80)
     */
    private fun decompressPalmDoc(input: ByteArray): String {
        val out = ArrayList<Byte>(input.size * 2)
        var i = 0
        while (i < input.size) {
            val c = input[i++].toInt() and 0xFF
            when {
                c == 0x00 -> out.add(0)
                c in 0x01..0x08 -> {
                    val end = minOf(i + c, input.size)
                    while (i < end) out.add(input[i++])
                }
                c in 0x09..0x7F -> out.add(c.toByte())
                c in 0x80..0xBF -> {
                    if (i >= input.size) break
                    val next = input[i++].toInt() and 0xFF
                    val v = ((c and 0x3F) shl 8) or next
                    val dist = v shr 3
                    val len = (v and 0x07) + 3
                    val base = out.size - dist
                    if (dist > 0 && base >= 0) {
                        repeat(len) { j -> out.add(out[base + (j % dist)]) }
                    }
                }
                else -> { // 0xC0–0xFF
                    out.add(' '.code.toByte())
                    out.add((c xor 0x80).toByte())
                }
            }
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun errorHtml(message: String) =
        "<html><body><p style='color:red'>$message</p></body></html>"
}
