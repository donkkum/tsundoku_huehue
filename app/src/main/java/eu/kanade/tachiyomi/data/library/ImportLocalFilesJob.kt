package eu.kanade.tachiyomi.data.library

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.domain.manga.interactor.ImportEpub
import eu.kanade.domain.manga.interactor.ParseEpubPreview
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Background import of every supported local ebook file in a folder tree into the novel library.
 *
 * Used for large imports (>300 files) so the work survives leaving the import screen. The folder
 * tree URI is re-walked here (rather than passing hundreds of URIs through WorkManager's small
 * input-data limit); metadata is read offline via [ParseEpubPreview] / LocalFileMetadata.
 */
class ImportLocalFilesJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val parseEpubPreview = ParseEpubPreview()
    private val importEpub = ImportEpub()

    override suspend fun doWork(): Result {
        setForegroundSafely()

        val treeUri = inputData.getString(KEY_TREE_URI)?.toUri() ?: return Result.failure()
        val categoryId = inputData.getLong(KEY_CATEGORY_ID, 0L).takeIf { it != 0L }
        val autoAddToLibrary = inputData.getBoolean(KEY_AUTO_ADD, true)

        return withIOContext {
            try {
                val uris = collectBookFiles(treeUri)
                val total = uris.size
                var imported = 0
                val errors = mutableListOf<String>()

                uris.chunked(CHUNK_SIZE).forEach { chunk ->
                    val parsed = parseEpubPreview.parseSelected(context, chunk)
                    errors += parsed.errors
                    parsed.files.forEach { file ->
                        val importFile = ImportEpub.ImportFile(
                            uri = file.uri,
                            fileName = file.fileName,
                            title = file.title,
                            author = file.author,
                            description = file.description,
                            coverUri = file.coverUri,
                            genres = file.genres,
                            series = file.collection,
                        )
                        try {
                            val result = importEpub.execute(
                                context = context,
                                files = listOf(importFile),
                                customTitle = null,
                                combineAsOne = false,
                                autoAddToLibrary = autoAddToLibrary,
                                categoryId = if (autoAddToLibrary) categoryId else null,
                                forceUniqueFolderName = true,
                                onProgress = { _, _, _ -> },
                            )
                            imported += result.successCount
                            errors += result.errors
                        } catch (e: Exception) {
                            errors += "${file.fileName}: ${e.message.orEmpty()}"
                        }
                        val done = imported + errors.size
                        setProgressAsync(
                            workDataOf(
                                KEY_PROGRESS_CURRENT to done.coerceAtMost(total),
                                KEY_PROGRESS_TOTAL to total,
                                KEY_PROGRESS_TITLE to file.title,
                            ),
                        )
                        updateProgressNotification(done.coerceAtMost(total), total, file.title)
                    }
                }

                context.cancelNotification(Notifications.ID_IMPORT_LOCAL_PROGRESS)
                showCompleteNotification(imported, errors.size)

                Result.success(
                    workDataOf(
                        KEY_IMPORTED to imported,
                        KEY_ERRORS to errors.joinToString("\n"),
                    ),
                )
            } catch (e: CancellationException) {
                context.cancelNotification(Notifications.ID_IMPORT_LOCAL_PROGRESS)
                throw e
            } catch (e: Exception) {
                context.cancelNotification(Notifications.ID_IMPORT_LOCAL_PROGRESS)
                Result.failure()
            }
        }
    }

    private fun collectBookFiles(treeUri: Uri, maxDepth: Int = 5): List<Uri> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val results = mutableListOf<Uri>()
        fun walk(dir: DocumentFile, depth: Int) {
            if (depth > maxDepth) return
            for (child in dir.listFiles()) {
                when {
                    child.isDirectory -> walk(child, depth + 1)
                    child.isFile && isSupportedBookFile(child) -> results.add(child.uri)
                }
            }
        }
        walk(root, 0)
        return results
    }

    private fun isSupportedBookFile(file: DocumentFile): Boolean {
        if (file.type in SUPPORTED_MIME_TYPES) return true
        val ext = file.name?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext in SUPPORTED_EXTENSIONS
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = context.notificationBuilder(Notifications.CHANNEL_IMPORT_LOCAL) {
            setSmallIcon(android.R.drawable.ic_menu_upload)
            setContentTitle("Importing local files")
            setContentText("Scanning folder…")
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(0, 0, true)
        }.build()
        return ForegroundInfo(
            Notifications.ID_IMPORT_LOCAL_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun updateProgressNotification(current: Int, total: Int, title: String) {
        val notification = context.notificationBuilder(Notifications.CHANNEL_IMPORT_LOCAL) {
            setSmallIcon(android.R.drawable.ic_menu_upload)
            setContentTitle("Importing local files")
            setContentText("$current/$total — $title")
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(total, current, false)
        }.build()
        context.notify(Notifications.ID_IMPORT_LOCAL_PROGRESS, notification)
    }

    private fun showCompleteNotification(imported: Int, errorCount: Int) {
        val message = buildString {
            append("Imported $imported novel${if (imported != 1) "s" else ""}")
            if (errorCount > 0) append(", $errorCount error${if (errorCount != 1) "s" else ""}")
        }
        val notification = context.notificationBuilder(Notifications.CHANNEL_IMPORT_LOCAL) {
            setSmallIcon(android.R.drawable.ic_menu_upload)
            setContentTitle("Import complete")
            setContentText(message)
            setAutoCancel(true)
        }.build()
        context.notify(Notifications.ID_IMPORT_LOCAL_COMPLETE, notification)
    }

    companion object {
        private const val TAG = "ImportLocalFilesJob"
        private const val CHUNK_SIZE = 20

        private val SUPPORTED_EXTENSIONS = setOf("epub", "txt", "text", "mobi", "azw", "azw3", "pdf")
        private val SUPPORTED_MIME_TYPES = setOf(
            "application/epub+zip",
            "text/plain",
            "application/pdf",
            "application/x-mobipocket-ebook",
            "application/vnd.amazon.ebook",
        )

        const val KEY_TREE_URI = "tree_uri"
        const val KEY_CATEGORY_ID = "category_id"
        const val KEY_AUTO_ADD = "auto_add"

        const val KEY_PROGRESS_CURRENT = "progress_current"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_PROGRESS_TITLE = "progress_title"

        const val KEY_IMPORTED = "imported"
        const val KEY_ERRORS = "errors"

        fun start(context: Context, treeUri: Uri, categoryId: Long? = null, autoAddToLibrary: Boolean = true) {
            val data = workDataOf(
                KEY_TREE_URI to treeUri.toString(),
                KEY_CATEGORY_ID to (categoryId ?: 0L),
                KEY_AUTO_ADD to autoAddToLibrary,
            )
            val request = OneTimeWorkRequestBuilder<ImportLocalFilesJob>()
                .addTag(TAG)
                .setInputData(data)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }

        fun getTag() = TAG
    }
}
