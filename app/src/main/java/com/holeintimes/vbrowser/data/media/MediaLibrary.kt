package com.holeintimes.vbrowser.data.media

import android.content.Context
import android.os.StatFs
import com.holeintimes.vbrowser.domain.LocalMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MediaLibrary(
    private val context: Context,
    private val rootDir: File
) {
    private val _items = MutableStateFlow<List<LocalMediaItem>>(emptyList())
    val items: StateFlow<List<LocalMediaItem>> = _items.asStateFlow()

    init {
        rootDir.mkdirs()
    }

    suspend fun refresh(includeHidden: Boolean = false) = withContext(Dispatchers.IO) {
        val result = mutableListOf<LocalMediaItem>()
        rootDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            if (dir.name.endsWith(".temp")) return@forEach
            val titleFile = File(dir, "videoTitle")
            val m3u8 = File(dir, "index.m3u8")
            val normalType = File(dir, "normalVideoType")
            if (!titleFile.exists()) return@forEach
            if (!m3u8.exists() && !normalType.exists()) return@forEach
            val hidden = File(dir, ".hidden").exists()
            if (hidden && !includeHidden) return@forEach
            val title = titleFile.readText().trim().ifBlank { dir.name }
            val isM3u8 = m3u8.exists()
            val ext = if (isM3u8) "m3u8" else normalType.readText().trim().ifBlank { "mp4" }
            val urlsFile = File(dir, "urls")
            val sourceUrl = if (urlsFile.exists()) {
                urlsFile.readLines().firstOrNull().orEmpty()
            } else ""
            result += LocalMediaItem(
                id = dir.name,
                title = title,
                path = dir.absolutePath,
                isM3u8 = isM3u8,
                fileExtension = ext,
                sizeBytes = dirSize(dir),
                sourceUrl = sourceUrl,
                isHidden = hidden,
                lastModified = dir.lastModified()
            )
        }
        _items.value = result.sortedByDescending { it.lastModified }
    }

    fun filter(keyword: String, source: List<LocalMediaItem> = _items.value): List<LocalMediaItem> {
        if (keyword.isBlank()) return source
        val q = keyword.lowercase(Locale.getDefault())
        return source.filter {
            it.title.lowercase(Locale.getDefault()).contains(q) ||
                it.sourceUrl.lowercase(Locale.getDefault()).contains(q)
        }
    }

    fun group(pattern: String, source: List<LocalMediaItem>): Map<String, List<LocalMediaItem>> {
        if (pattern.isBlank()) return mapOf("" to source)
        val regex = runCatching { Regex(pattern) }.getOrNull() ?: return mapOf("" to source)
        return source.groupBy { item ->
            regex.find(item.title)?.groupValues?.getOrNull(1) ?: item.title
        }
    }

    suspend fun delete(
        item: LocalMediaItem,
        includeHidden: Boolean = false
    ) = withContext(Dispatchers.IO) {
        File(item.path).deleteRecursively()
        refresh(includeHidden)
    }

    suspend fun setHidden(
        item: LocalMediaItem,
        hidden: Boolean,
        includeHidden: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val flag = File(item.path, ".hidden")
        if (hidden) flag.writeText("1") else flag.delete()
        refresh(includeHidden)
    }

    fun availableStorageLabel(): String {
        return try {
            val stat = StatFs(rootDir.absolutePath)
            val bytes = stat.availableBlocksLong * stat.blockSizeLong
            formatBytes(bytes)
        } catch (_: Exception) {
            "?"
        }
    }

    suspend fun clearTempPackages(): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        rootDir.listFiles()?.forEach { f ->
            if (f.isDirectory && f.name.endsWith(".temp")) {
                freed += dirSize(f)
                f.deleteRecursively()
            }
        }
        freed
    }

    fun videoFileFor(item: LocalMediaItem): File? {
        val dir = File(item.path)
        if (item.isM3u8) return File(dir, "index.m3u8")
        val ext = item.fileExtension.ifBlank { "mp4" }
        val candidate = File(dir, "video.$ext")
        if (candidate.exists()) return candidate
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("video.") }
    }

    private fun dirSize(dir: File): Long {
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
            val gb = mb / 1024.0
            return String.format(Locale.US, "%.2f GB", gb)
        }

        fun createRoot(context: Context): File {
            val root = File(context.filesDir, "VBrowserData")
            root.mkdirs()
            return root
        }
    }
}
