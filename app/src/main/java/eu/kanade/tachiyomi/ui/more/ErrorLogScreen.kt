package eu.kanade.tachiyomi.ui.more

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.errorlog.ErrorLogEntry
import eu.kanade.tachiyomi.data.errorlog.ImportErrorLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class ErrorLogScreen : Screen() {

    private class Model : ScreenModel {
        val manager: ImportErrorLogManager = Injekt.get()
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val model = rememberScreenModel { Model() }
        val entries by model.manager.entries.collectAsState()

        val sorted = remember(entries) { entries.sortedByDescending { it.timestamp } }

        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(model.manager.buildExportText().toByteArray())
                    }
                }
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = "Error log",
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = "Export",
                                    icon = Icons.Outlined.Save,
                                    onClick = { exportLauncher.launch("tsuntsun_error_log.txt") },
                                ),
                                AppBar.Action(
                                    title = "Clear",
                                    icon = Icons.Outlined.DeleteSweep,
                                    onClick = { model.manager.clear() },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            if (sorted.isEmpty()) {
                EmptyScreen(
                    message = "No import or update errors have been logged.",
                    modifier = Modifier.padding(paddingValues),
                )
                return@Scaffold
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                items(sorted) { entry ->
                    ErrorRow(entry)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ErrorRow(entry: ErrorLogEntry) {
    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = buildString {
                append(entry.category)
                append(" • ")
                append(timeFmt.format(java.util.Date(entry.timestamp)))
                append(" • ")
                append(DateUtils.getRelativeTimeSpanString(entry.timestamp))
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = entry.sourceName?.let { "$it — ${entry.item}" } ?: entry.item,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = entry.exceptionType?.let { "${entry.message} ($it)" } ?: entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        if (!entry.url.isNullOrBlank()) {
            Text(
                text = entry.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
