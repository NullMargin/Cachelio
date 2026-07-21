package com.holeintimes.vbrowser.ui.files

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.holeintimes.vbrowser.AppContainer
import com.holeintimes.vbrowser.R
import com.holeintimes.vbrowser.data.media.MediaLibrary
import com.holeintimes.vbrowser.data.media.PlaylistOrder
import com.holeintimes.vbrowser.domain.FilesSort
import com.holeintimes.vbrowser.domain.LocalMediaItem
import com.holeintimes.vbrowser.domain.UserPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FilesViewModel(private val container: AppContainer) : ViewModel() {
    val items = container.mediaLibrary.items
    val prefs = container.prefs.preferences.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        UserPreferences()
    )

    init {
        viewModelScope.launch {
            container.mediaLibrary.refresh(container.privacySession.isUnlocked.value)
        }
        viewModelScope.launch {
            container.downloadManager.finished.collect {
                container.mediaLibrary.refresh(container.privacySession.isUnlocked.value)
            }
        }
    }

    fun refresh(includeHidden: Boolean) = viewModelScope.launch {
        container.mediaLibrary.refresh(includeHidden)
    }

    fun setSearchFilter(value: String) = viewModelScope.launch {
        container.prefs.setFilesSearchFilter(value)
    }

    fun setSort(sort: FilesSort) = viewModelScope.launch {
        container.prefs.setFilesSort(sort)
    }

    fun delete(item: LocalMediaItem) = viewModelScope.launch {
        if (item.sourceUrl.isNotBlank()) container.prefs.removeUrlDownloaded(item.sourceUrl)
        container.mediaLibrary.delete(item, container.privacySession.isUnlocked.value)
    }

    fun setHidden(item: LocalMediaItem, hidden: Boolean) = viewModelScope.launch {
        container.mediaLibrary.setHidden(
            item = item,
            hidden = hidden,
            includeHidden = container.privacySession.isUnlocked.value
        )
    }

    fun availableStorage(): String = container.mediaLibrary.availableStorageLabel()

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FilesViewModel(container) as T
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    container: AppContainer,
    onPlay: (LocalMediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val vm: FilesViewModel = viewModel(factory = FilesViewModel.Factory(container))
    val items by vm.items.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val privacyUnlocked by container.privacySession.isUnlocked.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(prefs.filesSearchFilter) }
    var filterHydrated by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var menuItem by remember { mutableStateOf<LocalMediaItem?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(privacyUnlocked) { vm.refresh(privacyUnlocked) }

    LaunchedEffect(prefs.filesSearchFilter) {
        if (!filterHydrated) {
            filter = prefs.filesSearchFilter
            filterHydrated = true
        }
    }

    val ordered = remember(items, filter, prefs.filesSort) {
        PlaylistOrder.orderedVisible(items, filter, prefs.filesSort)
    }
    val grouped = remember(ordered, prefs.filesGroupOn, prefs.filesGroupPattern) {
        if (prefs.filesGroupOn && prefs.filesGroupPattern.isNotBlank()) {
            container.mediaLibrary.group(prefs.filesGroupPattern, ordered)
        } else {
            mapOf("" to ordered)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.nav_files), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.available_storage, vm.availableStorage()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = filter,
                onValueChange = {
                    filter = it
                    vm.setSearchFilter(it)
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.filter_hint)) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            ExposedDropdownMenuBox(
                expanded = sortMenuExpanded,
                onExpandedChange = { sortMenuExpanded = it }
            ) {
                OutlinedTextField(
                    value = sortLabel(prefs.filesSort),
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Sort, contentDescription = null)
                    },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = sortMenuExpanded)
                    },
                    modifier = Modifier
                        .menuAnchor()
                        .width(150.dp),
                    label = { Text(stringResource(R.string.sort)) }
                )
                ExposedDropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    FilesSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(sortLabel(option)) },
                            onClick = {
                                sortMenuExpanded = false
                                vm.setSort(option)
                            }
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (ordered.isEmpty()) {
            Text(stringResource(R.string.no_files), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                grouped.forEach { (group, list) ->
                    if (group.isNotBlank()) {
                        item(key = "g-$group") {
                            Text(
                                group,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                    items(list, key = { it.id }) { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onPlay(item) },
                                    onLongClick = { menuItem = item }
                                )
                                .padding(vertical = 10.dp)
                        ) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                buildString {
                                    append(
                                        if (item.isM3u8) {
                                            "M3U8"
                                        } else {
                                            item.fileExtension.uppercase()
                                        }
                                    )
                                    append(" · ${MediaLibrary.formatBytes(item.sizeBytes)}")
                                    if (item.isHidden) {
                                        append(" · ${context.getString(R.string.hidden)}")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    menuItem?.let { item ->
        AlertDialog(
            onDismissRequest = { menuItem = null },
            title = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(onClick = {
                        menuItem = null
                        onPlay(item)
                    }) { Text(stringResource(R.string.play)) }
                    TextButton(onClick = {
                        shareItem(context, container, item)
                        menuItem = null
                    }) { Text(stringResource(R.string.share)) }
                    if (!item.isHidden) {
                        TextButton(onClick = {
                            scope.launch {
                                vm.setHidden(item, true)
                                menuItem = null
                            }
                        }) { Text(stringResource(R.string.hide)) }
                    } else if (privacyUnlocked) {
                        TextButton(onClick = {
                            scope.launch {
                                vm.setHidden(item, false)
                                menuItem = null
                            }
                        }) { Text(stringResource(R.string.unhide)) }
                    }
                    TextButton(onClick = {
                        scope.launch { vm.delete(item); menuItem = null }
                    }) { Text(stringResource(R.string.delete)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuItem = null }) { Text(stringResource(R.string.clear)) }
            }
        )
    }
}

@Composable
private fun sortLabel(sort: FilesSort): String = when (sort) {
    FilesSort.DateNewest -> stringResource(R.string.sort_date_newest)
    FilesSort.DateOldest -> stringResource(R.string.sort_date_oldest)
    FilesSort.TitleAsc -> stringResource(R.string.sort_title_asc)
    FilesSort.TitleDesc -> stringResource(R.string.sort_title_desc)
}

fun shareItem(context: Context, container: AppContainer, item: LocalMediaItem) {
    val file = container.mediaLibrary.videoFileFor(item) ?: return
    if (item.isM3u8) {
        // Share title info only for HLS packages; media is a directory
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, item.title)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share)))
        return
    }
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "video/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share)))
}
