package com.holeintimes.vbrowser.data.media

import com.holeintimes.vbrowser.domain.FilesSort
import com.holeintimes.vbrowser.domain.LocalMediaItem
import java.util.Locale

object PlaylistOrder {
    fun orderedVisible(
        items: List<LocalMediaItem>,
        filter: String,
        sort: FilesSort
    ): List<LocalMediaItem> {
        val filtered = if (filter.isBlank()) {
            items
        } else {
            val q = filter.lowercase(Locale.getDefault())
            items.filter {
                it.title.lowercase(Locale.getDefault()).contains(q) ||
                    it.sourceUrl.lowercase(Locale.getDefault()).contains(q)
            }
        }
        return sort(filtered, sort)
    }

    fun nextAfter(
        currentPath: String,
        items: List<LocalMediaItem>,
        filter: String,
        sort: FilesSort
    ): LocalMediaItem? {
        val ordered = orderedVisible(items, filter, sort)
        val index = ordered.indexOfFirst { it.path == currentPath }
        if (index < 0) return null
        return ordered.getOrNull(index + 1)
    }

    private fun sort(items: List<LocalMediaItem>, sort: FilesSort): List<LocalMediaItem> {
        return when (sort) {
            FilesSort.DateNewest -> items.sortedByDescending { it.lastModified }
            FilesSort.DateOldest -> items.sortedBy { it.lastModified }
            FilesSort.TitleAsc -> items.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
            FilesSort.TitleDesc -> items.sortedWith(
                compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
        }
    }
}
