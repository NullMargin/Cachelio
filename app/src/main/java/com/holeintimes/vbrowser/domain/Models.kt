package com.holeintimes.vbrowser.domain

import kotlinx.serialization.Serializable

@Serializable
data class VideoFormat(
    val name: String,
    val mimeList: List<String> = emptyList()
)

@Serializable
data class VideoInfo(
    val fileName: String,
    val url: String,
    val videoFormat: VideoFormat,
    val size: Long = 0L,
    val duration: Double = 0.0,
    val sourcePageUrl: String = "",
    val sourcePageTitle: String = "",
    val dateAdd: Long = System.currentTimeMillis()
)

data class DetectedVideoInfo(
    val url: String,
    val sourcePageUrl: String = "",
    val sourcePageTitle: String = "",
    val mediaTitle: String = ""
)

enum class DownloadStatus {
    Ready, Loading, Running, Saving, Completed, Error, Paused
}

enum class VideoType {
    Normal, M3u8
}

@Serializable
data class DownloadTask(
    val taskId: String,
    val fileName: String,
    val videoType: String,
    val fileExtension: String,
    val url: String,
    val sourcePageUrl: String = "",
    val sourcePageTitle: String = "",
    val size: Long = 0L,
    val status: String = DownloadStatus.Ready.name.lowercase(),
    val failedReason: String = "",
    val totalDownloaded: Long = 0L,
    val currentSpeed: Long = 0L
) {
    val progress: Float
        get() = if (size > 0) (totalDownloaded.toFloat() / size.toFloat()).coerceIn(0f, 1f) else 0f

    val isM3u8: Boolean get() = videoType.equals("m3u8", ignoreCase = true)
}

data class LocalMediaItem(
    val id: String,
    val title: String,
    val path: String,
    val isM3u8: Boolean,
    val fileExtension: String = "",
    val sizeBytes: Long = 0L,
    val sourceUrl: String = "",
    val isHidden: Boolean = false,
    val lastModified: Long = 0L
)

data class AppConfig(
    val rootDataPath: String,
    val videoSnifferThreadNum: Int = 5,
    val videoSnifferRetryCountOnFail: Int = 1,
    val maxConcurrentTask: Int = 2,
    val m3U8DownloadThreadNum: Int = 20,
    val m3U8DownloadSizeDetectRetryCountOnFail: Int = 20,
    val downloadSubFileRetryCountOnFail: Int = 50,
    val normalFileHeaderCheckRetryCountOnFail: Int = 20,
    val normalFileSplitSize: Long = 2_000_000L,
    val normalFileDownloadThreadNum: Int = 5,
    val webServerPort: Int = 13746
)

enum class AppLanguage {
    System, English, Chinese
}

enum class FilesSort {
    DateNewest,
    DateOldest,
    TitleAsc,
    TitleDesc
}

data class UserPreferences(
    val language: AppLanguage = AppLanguage.System,
    val forceWebDarkMode: Boolean = false,
    val autoDownload: Boolean = false,
    val showFoundList: Boolean = true,
    val filesGroupOn: Boolean = false,
    val filesGroupPattern: String = "",
    val filesSort: FilesSort = FilesSort.DateNewest,
    val filesSearchFilter: String = "",
    val autoPlayNext: Boolean = false,
    val lastPlayedPath: String = "",
    val lastBrightness: Float = -1f
)

@Serializable
data class HistoryEntry(
    val url: String,
    val title: String = "",
    val visitedAt: Long = System.currentTimeMillis()
)

@Serializable
data class BookmarkEntry(
    val url: String,
    val title: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val isPrivate: Boolean = false
)
