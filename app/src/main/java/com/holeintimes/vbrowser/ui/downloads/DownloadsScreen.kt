package com.holeintimes.vbrowser.ui.downloads

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.holeintimes.vbrowser.AppContainer
import com.holeintimes.vbrowser.R
import com.holeintimes.vbrowser.data.media.MediaLibrary
import com.holeintimes.vbrowser.domain.DetectedVideoInfo
import com.holeintimes.vbrowser.domain.DownloadTask
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

data class AddResourcesResult(val queued: Int, val skipped: Int)

class DownloadsViewModel(private val container: AppContainer) : ViewModel() {
    val tasks = container.downloadManager.tasksFlow

    private val _addResult = MutableSharedFlow<AddResourcesResult>(extraBufferCapacity = 1)
    val addResult: SharedFlow<AddResourcesResult> = _addResult.asSharedFlow()

    fun pause(id: String) = container.downloadManager.pause(id)
    fun resume(id: String) = container.downloadManager.resume(id)
    fun retry(id: String) = container.downloadManager.retry(id)
    fun stop(id: String) = container.downloadManager.stop(id)

    fun addResources(raw: String) {
        val urls = parseResourceUrls(raw)
        if (urls.isEmpty()) {
            _addResult.tryEmit(AddResourcesResult(queued = 0, skipped = 0))
            return
        }
        viewModelScope.launch {
            var queued = 0
            var skipped = 0
            for (url in urls) {
                val info = container.sniffer.probe(
                    DetectedVideoInfo(
                        url = url,
                        sourcePageUrl = url,
                        sourcePageTitle = ""
                    )
                )
                if (info != null) {
                    val name = fileNameForManualResource(info.url)
                    container.downloadManager.addFromVideo(
                        info.copy(fileName = name, sourcePageTitle = name)
                    )
                    queued++
                } else {
                    skipped++
                }
            }
            _addResult.emit(AddResourcesResult(queued = queued, skipped = skipped))
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DownloadsViewModel(container) as T
    }
}

internal fun parseResourceUrls(raw: String): List<String> {
    return raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            when {
                line.startsWith("http://") || line.startsWith("https://") -> line
                line.contains('.') && !line.contains(' ') -> "https://$line"
                else -> null
            }
        }
        .distinct()
        .toList()
}

/**
 * File/display name for a manually pasted resource: path base + query parameter values.
 * Falls back to a UUID when nothing usable can be derived.
 */
internal fun fileNameForManualResource(url: String): String {
    val fallback = UUID.randomUUID().toString().replace("-", "")
    return try {
        val u = URL(url)
        val pathSegment = u.path.trim('/').substringAfterLast('/')
        val pathBase = sanitizeFileNamePart(
            URLDecoder.decode(pathSegment, StandardCharsets.UTF_8.name())
                .substringBeforeLast('.')
        )
        val queryValues = u.query
            ?.split('&')
            ?.mapNotNull { pair ->
                val rawValue = pair.substringAfter('=', missingDelimiterValue = "")
                if (rawValue.isEmpty()) return@mapNotNull null
                sanitizeFileNamePart(
                    URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
                ).takeIf { it.isNotBlank() }
            }
            .orEmpty()
        val readable = buildList {
            if (pathBase.isNotBlank()) add(pathBase)
            addAll(queryValues)
        }.joinToString("_").take(160)
        readable.ifBlank { fallback }
    } catch (_: Exception) {
        fallback
    }
}

private fun sanitizeFileNamePart(raw: String): String =
    raw.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')
        .take(48)

@Composable
fun DownloadsScreen(
    container: AppContainer,
    modifier: Modifier = Modifier
) {
    val vm: DownloadsViewModel = viewModel(factory = DownloadsViewModel.Factory(container))
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(vm) {
        vm.addResult.collect { result ->
            Toast.makeText(
                context,
                context.getString(R.string.resources_queued, result.queued),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(stringResource(R.string.nav_downloads), style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(12.dp))
            if (tasks.isEmpty()) {
                Text(
                    stringResource(R.string.no_downloads),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(tasks, key = { it.taskId }) { task ->
                        DownloadCard(
                            task = task,
                            onPause = { vm.pause(task.taskId) },
                            onResume = { vm.resume(task.taskId) },
                            onRetry = { vm.retry(task.taskId) },
                            onStop = { vm.stop(task.taskId) }
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = {
                draft = ""
                showAddDialog = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_resources))
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.add_resources)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp),
                    placeholder = { Text(stringResource(R.string.add_resources_hint)) },
                    minLines = 5,
                    maxLines = 12
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val text = draft
                        showAddDialog = false
                        draft = ""
                        vm.addResources(text)
                    },
                    enabled = draft.isNotBlank()
                ) {
                    Text(stringResource(R.string.add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun DownloadCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = task.sourcePageTitle.ifBlank { task.fileName },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${task.videoType.uppercase()} · ${task.status}" +
                    if (task.failedReason.isNotBlank()) " · ${task.failedReason}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { task.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = buildString {
                    append("${(task.progress * 100).toInt()}%")
                    append(" · ${MediaLibrary.formatBytes(task.totalDownloaded)}")
                    if (task.size > 0) append(" / ${MediaLibrary.formatBytes(task.size)}")
                    if (task.currentSpeed > 0) append(" · ${MediaLibrary.formatBytes(task.currentSpeed)}/s")
                },
                style = MaterialTheme.typography.bodySmall
            )
            Row {
                when (task.status) {
                    "running", "loading", "saving", "ready" -> {
                        TextButton(onClick = onPause) { Text(stringResource(R.string.pause)) }
                    }
                    "paused" -> {
                        TextButton(onClick = onResume) { Text(stringResource(R.string.resume)) }
                    }
                    "error" -> {
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                    }
                }
                TextButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
            }
        }
    }
}
