package eu.kanade.tachiyomi.data.library

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.domain.manga.interactor.RestoreFromDownloads
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.util.lang.withIOContext

class RestoreDownloadsJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val interactor = RestoreFromDownloads()
    private val errorLog: eu.kanade.tachiyomi.data.errorlog.ImportErrorLogManager by uy.kohesive.injekt.injectLazy()

    override suspend fun doWork(): Result {
        setForegroundSafely()

        val rootUri = inputData.getString(KEY_ROOT_URI)?.toUri()
            ?: return Result.failure()
        val categoryId = inputData.getLong(KEY_CATEGORY_ID, 0L).takeIf { it != 0L }

        return withIOContext {
            try {
                val result = interactor.restore(
                    context = context,
                    rootUri = rootUri,
                    categoryId = categoryId,
                    onProgress = { current, total, title ->
                        val progressData = workDataOf(
                            KEY_PROGRESS_CURRENT to current,
                            KEY_PROGRESS_TOTAL to total,
                            KEY_PROGRESS_TITLE to title,
                        )
                        setProgressAsync(progressData)
                        updateProgressNotification(current, total, title)
                    },
                )

                context.cancelNotification(Notifications.ID_RESTORE_DOWNLOADS_PROGRESS)
                errorLog.logMessages("Restore from downloads", result.errors)
                showCompleteNotification(result)

                Result.success(
                    workDataOf(
                        KEY_RESTORED to result.restored,
                        KEY_SKIPPED to result.skipped,
                        KEY_ERRORS to result.errors.joinToString("\n"),
                    ),
                )
            } catch (e: CancellationException) {
                context.cancelNotification(Notifications.ID_RESTORE_DOWNLOADS_PROGRESS)
                throw e
            } catch (e: Exception) {
                context.cancelNotification(Notifications.ID_RESTORE_DOWNLOADS_PROGRESS)
                Result.failure()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = context.notificationBuilder(Notifications.CHANNEL_RESTORE_DOWNLOADS) {
            setSmallIcon(android.R.drawable.ic_menu_upload)
            setContentTitle("Restore from downloads")
            setContentText("Starting…")
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(0, 0, true)
        }.build()
        return ForegroundInfo(
            Notifications.ID_RESTORE_DOWNLOADS_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun updateProgressNotification(current: Int, total: Int, title: String) {
        val notification = context.notificationBuilder(Notifications.CHANNEL_RESTORE_DOWNLOADS) {
            setSmallIcon(android.R.drawable.ic_menu_upload)
            setContentTitle("Restore from downloads")
            setContentText("$current/$total — $title")
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(total, current, false)
        }.build()
        context.notify(Notifications.ID_RESTORE_DOWNLOADS_PROGRESS, notification)
    }

    private fun showCompleteNotification(result: RestoreFromDownloads.Result) {
        val message = buildString {
            append("Restored ${result.restored} novel${if (result.restored != 1) "s" else ""}")
            if (result.skipped > 0) append(", skipped ${result.skipped}")
            if (result.errors.isNotEmpty()) append(", ${result.errors.size} error${if (result.errors.size != 1) "s" else ""}")
        }
        val notification = context.notificationBuilder(Notifications.CHANNEL_RESTORE_DOWNLOADS) {
            setSmallIcon(android.R.drawable.ic_menu_upload)
            setContentTitle("Restore complete")
            setContentText(message)
            setAutoCancel(true)
        }.build()
        context.notify(Notifications.ID_RESTORE_DOWNLOADS_COMPLETE, notification)
    }

    companion object {
        private const val TAG = "RestoreDownloadsJob"

        const val KEY_ROOT_URI = "root_uri"
        const val KEY_CATEGORY_ID = "category_id"

        const val KEY_PROGRESS_CURRENT = "progress_current"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_PROGRESS_TITLE = "progress_title"

        const val KEY_RESTORED = "restored"
        const val KEY_SKIPPED = "skipped"
        const val KEY_ERRORS = "errors"

        fun start(context: Context, rootUri: Uri, categoryId: Long? = null) {
            val data = workDataOf(
                KEY_ROOT_URI to rootUri.toString(),
                KEY_CATEGORY_ID to (categoryId ?: 0L),
            )
            val request = OneTimeWorkRequestBuilder<RestoreDownloadsJob>()
                .addTag(TAG)
                .setInputData(data)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }

        fun getTag() = TAG
    }
}
