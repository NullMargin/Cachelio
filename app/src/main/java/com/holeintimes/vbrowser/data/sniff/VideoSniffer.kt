package com.holeintimes.vbrowser.data.sniff

import android.util.Log
import android.webkit.CookieManager
import com.holeintimes.vbrowser.data.http.HttpClient
import com.holeintimes.vbrowser.domain.DetectedVideoInfo
import com.holeintimes.vbrowser.domain.VideoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class VideoSniffer(
    private val threadCount: Int = 5,
    private val retryCount: Int = 1
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<DetectedVideoInfo>(Channel.UNLIMITED)
    private val workers = mutableListOf<Job>()
    private val foundMap = ConcurrentHashMap<String, VideoInfo>()
    private val pendingOrDone = ConcurrentHashMap.newKeySet<String>()

    private val _foundList = MutableStateFlow<List<VideoInfo>>(emptyList())
    val foundList: StateFlow<List<VideoInfo>> = _foundList.asStateFlow()

    private val _newItem = MutableSharedFlow<VideoInfo>(extraBufferCapacity = 64)
    val newItem: SharedFlow<VideoInfo> = _newItem.asSharedFlow()

    fun start() {
        stop()
        repeat(threadCount) {
            workers += scope.launch {
                while (isActive) {
                    val detected = queue.receive()
                    var fail = 0
                    while (!detect(detected) && fail < retryCount) {
                        fail++
                    }
                    // Allow re-probe later if detection failed hard.
                    if (fail >= retryCount) {
                        pendingOrDone.remove(normalizeKey(detected.url))
                    }
                }
            }
        }
    }

    fun stop() {
        workers.forEach { it.cancel() }
        workers.clear()
    }

    fun enqueue(info: DetectedVideoInfo) {
        val key = normalizeKey(info.url)
        if (!pendingOrDone.add(key)) return
        val result = queue.trySend(info)
        if (result.isFailure) {
            pendingOrDone.remove(key)
        }
    }

    fun clear() {
        foundMap.clear()
        pendingOrDone.clear()
        _foundList.value = emptyList()
    }

    private fun detect(detected: DetectedVideoInfo): Boolean {
        return try {
            var url = detected.url
            val extra = HttpClient.ProbeHeaders(
                cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull(),
                referer = detected.sourcePageUrl.ifBlank { null }
            )
            val head = HttpClient.probe(url, extra)
            url = head.realUrl
            val contentType = head.contentType
            if (contentType.isNullOrBlank() && !VideoFormatUtil.containsVideoExtension(url)) {
                Log.d(TAG, "no content-type: $url code=${head.code}")
                return false
            }
            val format = VideoFormatUtil.detectVideoFormat(url, contentType) ?: run {
                Log.d(TAG, "not video: $url type=$contentType")
                return true
            }
            if (foundMap.containsKey(url)) return true

            val resolvedTitle = VideoTitleResolver.resolve(
                mediaUrl = url,
                pageTitle = detected.sourcePageTitle,
                mediaTitle = detected.mediaTitle,
                pageUrl = detected.sourcePageUrl
            )
            val videoInfo = if (format.name == "m3u8") {
                val duration = runCatching {
                    M3u8Util.figureDuration(url)
                }.getOrDefault(0.0)
                // Still accept playlist when duration parse fails (live / master quirks).
                if (duration <= 0) {
                    val body = runCatching { HttpClient.getString(url, extra).take(64) }.getOrNull()
                    if (body != null && !M3u8Util.looksLikeM3u8(body) &&
                        !VideoFormatUtil.containsVideoExtension(url)
                    ) {
                        Log.d(TAG, "reject non-m3u8 body: $url")
                        return true
                    }
                }
                VideoInfo(
                    fileName = UUID.randomUUID().toString().replace("-", ""),
                    url = url,
                    videoFormat = format,
                    duration = duration.coerceAtLeast(0.0),
                    sourcePageUrl = detected.sourcePageUrl,
                    sourcePageTitle = resolvedTitle
                )
            } else {
                VideoInfo(
                    fileName = UUID.randomUUID().toString().replace("-", ""),
                    url = url,
                    videoFormat = format,
                    size = head.contentLength ?: 0L,
                    sourcePageUrl = detected.sourcePageUrl,
                    sourcePageTitle = resolvedTitle
                )
            }
            foundMap[url] = videoInfo
            pendingOrDone.add(normalizeKey(url))
            _foundList.update { listOf(videoInfo) + it.filterNot { v -> v.url == url } }
            _newItem.tryEmit(videoInfo)
            Log.i(TAG, "found ${format.name}: $url")
            true
        } catch (e: Exception) {
            Log.d(TAG, "detect fail ${detected.url}: ${e.message}")
            false
        }
    }

    private fun normalizeKey(url: String): String =
        url.substringBefore('#').trim().lowercase()

    companion object {
        private const val TAG = "VideoSniffer"
    }
}
