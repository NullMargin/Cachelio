package com.holeintimes.vbrowser.ui.downloads

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class IntakeStatus {
    Waiting,
    Probing,
    Queued,
    Failed
}

data class IntakeItem(
    val id: String,
    val url: String,
    val status: IntakeStatus,
    val displayName: String? = null
)

data class IntakeStats(
    val waiting: Int = 0,
    val probing: Int = 0,
    val queued: Int = 0,
    val failed: Int = 0
) {
    val total: Int get() = waiting + probing + queued + failed
    val checking: Int get() = waiting + probing
    val hasFailed: Boolean get() = failed > 0
}

class DownloadsViewModel(private val container: AppContainer) : ViewModel() {
    val tasks = container.downloadManager.tasksFlow

    private val _intakeItems = MutableStateFlow<List<IntakeItem>>(emptyList())
    val intakeItems: StateFlow<List<IntakeItem>> = _intakeItems.asStateFlow()

    private var intakeJob: Job? = null

    fun pause(id: String) = container.downloadManager.pause(id)
    fun resume(id: String) = container.downloadManager.resume(id)
    fun retry(id: String) = container.downloadManager.retry(id)
    fun stop(id: String) = container.downloadManager.stop(id)

    fun addResources(raw: String, expandLinks: Boolean = false) {
        val urls = parseResourceUrls(raw, expandLinks = expandLinks)
        if (urls.isEmpty()) return
        val newItems = urls.map { url ->
            IntakeItem(
                id = UUID.randomUUID().toString().replace("-", ""),
                url = url,
                status = IntakeStatus.Waiting
            )
        }
        _intakeItems.update { it + newItems }
        ensureIntakeWorker()
    }

    fun retryFailed() {
        var changed = false
        _intakeItems.update { list ->
            list.map { item ->
                if (item.status == IntakeStatus.Failed) {
                    changed = true
                    item.copy(status = IntakeStatus.Waiting, displayName = null)
                } else {
                    item
                }
            }
        }
        if (changed) ensureIntakeWorker()
    }

    fun clearIntake() {
        intakeJob?.cancel()
        intakeJob = null
        _intakeItems.value = emptyList()
    }

    private fun ensureIntakeWorker() {
        if (intakeJob?.isActive == true) return
        intakeJob = viewModelScope.launch {
            while (isActive) {
                val next = _intakeItems.value.firstOrNull { it.status == IntakeStatus.Waiting }
                    ?: break
                updateIntake(next.id) { it.copy(status = IntakeStatus.Probing) }
                try {
                    ensureActive()
                    val info = container.sniffer.probe(
                        DetectedVideoInfo(
                            url = next.url,
                            sourcePageUrl = next.url,
                            sourcePageTitle = ""
                        )
                    )
                    ensureActive()
                    if (info != null) {
                        val name = fileNameForManualResource(info.url)
                        container.downloadManager.addFromVideo(
                            info.copy(fileName = name, sourcePageTitle = name)
                        )
                        updateIntake(next.id) {
                            it.copy(status = IntakeStatus.Queued, displayName = name)
                        }
                    } else {
                        updateIntake(next.id) { it.copy(status = IntakeStatus.Failed) }
                    }
                } catch (e: CancellationException) {
                    updateIntake(next.id) {
                        if (it.status == IntakeStatus.Probing) {
                            it.copy(status = IntakeStatus.Failed)
                        } else {
                            it
                        }
                    }
                    throw e
                } catch (_: Exception) {
                    updateIntake(next.id) { it.copy(status = IntakeStatus.Failed) }
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (intakeJob === job) intakeJob = null
            }
        }
    }

    private fun updateIntake(id: String, transform: (IntakeItem) -> IntakeItem) {
        _intakeItems.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DownloadsViewModel(container) as T
    }
}

internal fun intakeStats(items: List<IntakeItem>): IntakeStats {
    var waiting = 0
    var probing = 0
    var queued = 0
    var failed = 0
    for (item in items) {
        when (item.status) {
            IntakeStatus.Waiting -> waiting++
            IntakeStatus.Probing -> probing++
            IntakeStatus.Queued -> queued++
            IntakeStatus.Failed -> failed++
        }
    }
    return IntakeStats(waiting, probing, queued, failed)
}

internal fun parseResourceUrls(raw: String, expandLinks: Boolean = false): List<String> {
    return raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .flatMap { line ->
            val expanded = if (expandLinks) expandLinkRanges(line) else listOf(line)
            expanded.mapNotNull { candidate ->
                when {
                    candidate.startsWith("http://") || candidate.startsWith("https://") -> candidate
                    candidate.contains('.') && !candidate.contains(' ') -> "https://$candidate"
                    else -> null
                }
            }
        }
        .distinct()
        .toList()
}

private val numericRangePattern = Regex("""\[(\d+)-(\d+)]""")
private const val maxExpandedLinks = 500

/**
 * Expands numeric ranges like `video[0-9].mp4` or `part[01-03].ts`.
 * Zero-pads to the width of the start token. Multiple ranges expand in order (cartesian).
 */
internal fun expandLinkRanges(input: String): List<String> {
    val match = numericRangePattern.find(input) ?: return listOf(input)
    val startToken = match.groupValues[1]
    val endToken = match.groupValues[2]
    val start = startToken.toIntOrNull() ?: return listOf(input)
    val end = endToken.toIntOrNull() ?: return listOf(input)
    if (start > end) return listOf(input)
    val count = end - start + 1
    if (count > maxExpandedLinks) return listOf(input)
    val width = startToken.length
    val prefix = input.substring(0, match.range.first)
    val suffix = input.substring(match.range.last + 1)
    val step = (start..end).map { n ->
        prefix + n.toString().padStart(width, '0') + suffix
    }
    val result = step.flatMap { expandLinkRanges(it) }
    return if (result.size > maxExpandedLinks) result.take(maxExpandedLinks) else result
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
    val intakeItems by vm.intakeItems.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var showExpandHelp by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var expandLinks by remember { mutableStateOf(false) }
    var intakeExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(stringResource(R.string.nav_downloads), style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(12.dp))
            if (intakeItems.isNotEmpty()) {
                IntakePanel(
                    items = intakeItems,
                    expanded = intakeExpanded,
                    onToggleExpanded = { intakeExpanded = !intakeExpanded },
                    onRetryFailed = { vm.retryFailed() },
                    onClear = {
                        vm.clearIntake()
                        intakeExpanded = false
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
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
                expandLinks = false
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
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.expand_links),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { showExpandHelp = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.HelpOutline,
                                contentDescription = stringResource(R.string.expand_links_help_cd),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Switch(
                            checked = expandLinks,
                            onCheckedChange = { expandLinks = it }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
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
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val text = draft
                        val expand = expandLinks
                        showAddDialog = false
                        draft = ""
                        expandLinks = false
                        vm.addResources(text, expandLinks = expand)
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

    if (showExpandHelp) {
        AlertDialog(
            onDismissRequest = { showExpandHelp = false },
            title = { Text(stringResource(R.string.expand_links_help_title)) },
            text = {
                Text(
                    text = stringResource(R.string.expand_links_help),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { showExpandHelp = false }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

@Composable
private fun IntakePanel(
    items: List<IntakeItem>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onRetryFailed: () -> Unit,
    onClear: () -> Unit
) {
    val stats = remember(items) { intakeStats(items) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.intake_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(
                            R.string.intake_summary,
                            stats.checking,
                            stats.queued,
                            stats.failed
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.collapse else R.string.expand
                    )
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items.forEach { item ->
                        IntakeRow(item = item)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onRetryFailed,
                        enabled = stats.hasFailed
                    ) {
                        Text(stringResource(R.string.intake_retry_failed))
                    }
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.intake_clear))
                    }
                }
            }
        }
    }
}

@Composable
private fun IntakeRow(item: IntakeItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.displayName?.takeIf { it.isNotBlank() } ?: item.url,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = intakeStatusLabel(item.status),
            style = MaterialTheme.typography.labelSmall,
            color = when (item.status) {
                IntakeStatus.Failed -> MaterialTheme.colorScheme.error
                IntakeStatus.Queued -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun intakeStatusLabel(status: IntakeStatus): String =
    stringResource(
        when (status) {
            IntakeStatus.Waiting -> R.string.intake_status_waiting
            IntakeStatus.Probing -> R.string.intake_status_probing
            IntakeStatus.Queued -> R.string.intake_status_queued
            IntakeStatus.Failed -> R.string.intake_status_failed
        }
    )

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
