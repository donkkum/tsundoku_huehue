package eu.kanade.tachiyomi.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.manga.interactor.RestoreFromDownloads
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.data.library.RestoreDownloadsJob
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RestoreDownloadsScreen : cafe.adriel.voyager.core.screen.Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        // Observe WorkManager job state
        val workInfoState by remember {
            context.workManager
                .getWorkInfosByTagLiveData(RestoreDownloadsJob.getTag())
                .asFlow()
                .map { list -> list.firstOrNull() }
        }.collectAsStateWithLifecycle(initialValue = null)

        val isRunning = workInfoState?.state == WorkInfo.State.RUNNING
        val isSucceeded = workInfoState?.state == WorkInfo.State.SUCCEEDED
        val isFailed = workInfoState?.state == WorkInfo.State.FAILED

        val progressCurrent = workInfoState?.progress?.getInt(RestoreDownloadsJob.KEY_PROGRESS_CURRENT, 0) ?: 0
        val progressTotal = workInfoState?.progress?.getInt(RestoreDownloadsJob.KEY_PROGRESS_TOTAL, 0) ?: 0
        val progressTitle = workInfoState?.progress?.getString(RestoreDownloadsJob.KEY_PROGRESS_TITLE) ?: ""

        val resultRestored = workInfoState?.outputData?.getInt(RestoreDownloadsJob.KEY_RESTORED, 0) ?: 0
        val resultSkipped = workInfoState?.outputData?.getInt(RestoreDownloadsJob.KEY_SKIPPED, 0) ?: 0
        val resultErrors = workInfoState?.outputData?.getString(RestoreDownloadsJob.KEY_ERRORS)
            ?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

        var pickedUri by remember { mutableStateOf<Uri?>(null) }
        var novels by remember { mutableStateOf<List<RestoreFromDownloads.NovelCandidate>>(emptyList()) }
        var isScanning by remember { mutableStateOf(false) }
        val interactor = remember { RestoreFromDownloads() }

        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            pickedUri = uri
            novels = emptyList()
            isScanning = true
            scope.launch {
                withContext(Dispatchers.IO) {
                    novels = interactor.scan(context, uri)
                }
                isScanning = false
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = "Restore from downloads",
                    navigateUp = navigator::pop,
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when {
                    // Restore completed — show results
                    isSucceeded || isFailed -> {
                        Text("Done!", style = MaterialTheme.typography.titleMedium)
                        Text("✓ Restored: $resultRestored", style = MaterialTheme.typography.bodyMedium)
                        if (resultSkipped > 0) {
                            Text("— Skipped (no chapters): $resultSkipped", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (resultErrors.isNotEmpty()) {
                            Text(
                                "✗ Errors: ${resultErrors.size}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                                items(resultErrors) { err ->
                                    Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = navigator::pop, modifier = Modifier.fillMaxWidth()) {
                            Text("Back to library")
                        }
                    }

                    // Restore running in background
                    isRunning -> {
                        Text(
                            text = "Running in background — you can leave this screen.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(
                                progress = { if (progressTotal > 0) progressCurrent.toFloat() / progressTotal else 0f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = if (progressTotal > 0) "$progressCurrent / $progressTotal — $progressTitle" else "Starting…",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Button(onClick = navigator::pop, modifier = Modifier.fillMaxWidth()) {
                            Text("Go to library")
                        }
                    }

                    // Scan / pick phase
                    else -> {
                        Text(
                            text = "Pick the folder where your downloaded novels are stored. The app will scan it for HTML chapters and add them to your library without needing an internet connection.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Button(
                            onClick = { folderPicker.launch(null) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isScanning,
                        ) {
                            Icon(Icons.Outlined.Folder, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (pickedUri == null) "Pick downloads folder" else "Change folder")
                        }

                        if (isScanning) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Scanning…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        if (novels.isNotEmpty()) {
                            Text(
                                text = "Found ${novels.size} novel${if (novels.size != 1) "s" else ""}:",
                                style = MaterialTheme.typography.titleSmall,
                            )

                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(novels) { novel ->
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        Text(
                                            text = novel.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${novel.chapters.size} chapter${if (novel.chapters.size != 1) "s" else ""}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    HorizontalDivider()
                                }
                            }

                            Button(
                                onClick = {
                                    val uri = pickedUri ?: return@Button
                                    RestoreDownloadsJob.start(context, uri)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Restore ${novels.size} novel${if (novels.size != 1) "s" else ""} to library")
                            }
                        }

                        if (novels.isEmpty() && !isScanning && pickedUri != null) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    "No downloadable novels found in that folder.\nMake sure it contains subfolders with HTML files.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
