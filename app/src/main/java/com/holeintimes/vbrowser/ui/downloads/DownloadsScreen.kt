package com.holeintimes.vbrowser.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.holeintimes.vbrowser.AppContainer
import com.holeintimes.vbrowser.R
import com.holeintimes.vbrowser.data.media.MediaLibrary
import com.holeintimes.vbrowser.domain.DownloadTask

class DownloadsViewModel(private val container: AppContainer) : ViewModel() {
    val tasks = container.downloadManager.tasksFlow

    fun pause(id: String) = container.downloadManager.pause(id)
    fun resume(id: String) = container.downloadManager.resume(id)
    fun retry(id: String) = container.downloadManager.retry(id)
    fun stop(id: String) = container.downloadManager.stop(id)

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DownloadsViewModel(container) as T
    }
}

@Composable
fun DownloadsScreen(
    container: AppContainer,
    modifier: Modifier = Modifier
) {
    val vm: DownloadsViewModel = viewModel(factory = DownloadsViewModel.Factory(container))
    val tasks by vm.tasks.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
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
