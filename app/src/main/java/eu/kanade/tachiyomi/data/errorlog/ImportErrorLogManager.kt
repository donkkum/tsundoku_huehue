package eu.kanade.tachiyomi.data.errorlog

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Persistent, exportable log of import and library-update failures.
 *
 * Records survive app restarts (stored as a single JSON file in [context].filesDir, following the
 * same file-based pattern as CustomSourceManager) and are surfaced in the More tab. Each entry
 * captures what failed, when, and — where the catch site is under our control — the exception type.
 */
class ImportErrorLogManager(
    private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val logFile: File by lazy {
        File(context.filesDir, "error_logs").apply { mkdirs() }.let { File(it, "log.json") }
    }

    private val mutationLock = Any()

    private val _entries = MutableStateFlow<List<ErrorLogEntry>>(emptyList())
    val entries: StateFlow<List<ErrorLogEntry>> = _entries.asStateFlow()

    init {
        _entries.value = readFromDisk()
    }

    /** Append a single failure. [item] is the title/filename that failed. */
    fun log(
        category: String,
        item: String,
        message: String,
        exceptionType: String? = null,
        sourceName: String? = null,
        url: String? = null,
    ) {
        logAll(
            category,
            listOf(
                ErrorLogEntry(
                    timestamp = System.currentTimeMillis(),
                    category = category,
                    item = item,
                    sourceName = sourceName,
                    url = url,
                    message = message,
                    exceptionType = exceptionType,
                ),
            ),
        )
    }

    /**
     * Append many failures at once. Accepts pre-built entries; the [category] argument is applied to
     * any entry that didn't set one. Blank messages are skipped so a clean run logs nothing.
     */
    fun logAll(category: String, entries: List<ErrorLogEntry>) {
        val stamped = entries
            .filter { it.message.isNotBlank() }
            .map { if (it.category.isBlank()) it.copy(category = category) else it }
        if (stamped.isEmpty()) return
        synchronized(mutationLock) {
            val merged = (_entries.value + stamped).takeLast(MAX_ENTRIES)
            _entries.value = merged
            writeToDisk(merged)
        }
    }

    /** Convenience: turn raw "item: message" error strings from an import Result into entries. */
    fun logMessages(category: String, messages: List<String>) {
        val now = System.currentTimeMillis()
        logAll(
            category,
            messages.mapNotNull { raw ->
                val text = raw.trim().ifBlank { return@mapNotNull null }
                // Import results format errors as "item: message"; split on the first colon.
                val idx = text.indexOf(':')
                val item = if (idx > 0) text.substring(0, idx).trim() else category
                val message = if (idx > 0) text.substring(idx + 1).trim().ifBlank { text } else text
                ErrorLogEntry(
                    timestamp = now,
                    category = category,
                    item = item,
                    message = message,
                )
            },
        )
    }

    fun clear() {
        synchronized(mutationLock) {
            _entries.value = emptyList()
            runCatching { logFile.delete() }
        }
    }

    /** Newest-first plain-text rendering for the exportable .txt. */
    fun buildExportText(): String {
        val list = _entries.value
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return buildString {
            append("Tsuntsun import & update error log\n")
            append("Generated: ").append(fmt.format(nowDate())).append('\n')
            append("Total entries: ").append(list.size).append("\n\n")
            list.sortedByDescending { it.timestamp }.forEach { e ->
                append('[').append(fmt.format(java.util.Date(e.timestamp))).append("] ")
                append('[').append(e.category).append("] ")
                if (!e.sourceName.isNullOrBlank()) append(e.sourceName).append(" — ")
                append(e.item).append(" — ").append(e.message)
                if (!e.exceptionType.isNullOrBlank()) append(" (").append(e.exceptionType).append(')')
                append('\n')
                if (!e.url.isNullOrBlank()) append("    URL: ").append(e.url).append('\n')
            }
        }
    }

    private fun nowDate(): java.util.Date = java.util.Date(System.currentTimeMillis())

    private fun readFromDisk(): List<ErrorLogEntry> {
        return try {
            if (!logFile.exists()) return emptyList()
            json.decodeFromString<List<ErrorLogEntry>>(logFile.readText())
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to read error log" }
            emptyList()
        }
    }

    private fun writeToDisk(list: List<ErrorLogEntry>) {
        try {
            logFile.writeText(json.encodeToString(list))
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to write error log" }
        }
    }

    companion object {
        private const val MAX_ENTRIES = 1000
    }
}

@Serializable
data class ErrorLogEntry(
    val timestamp: Long,
    val category: String,
    val item: String,
    val sourceName: String? = null,
    val url: String? = null,
    val message: String,
    val exceptionType: String? = null,
)
