package com.holeintimes.vbrowser.data.sniff

import com.holeintimes.vbrowser.data.http.HttpClient
import com.holeintimes.vbrowser.domain.VideoFormat
import java.net.URL

object VideoFormatUtil {
    private val extensionList = listOf("m3u8", "mp4", "flv", "f4v", "webm", "mkv", "mpeg", "mpg", "mov", "ts")

    private val nonMediaExtensions = setOf(
        "css", "js", "mjs", "json", "xml", "html", "htm", "php", "asp", "aspx",
        "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif",
        "woff", "woff2", "ttf", "otf", "eot",
        "map", "txt", "pdf", "zip", "rar", "apk"
    )

    private val mediaPathHints = listOf(
        "m3u8", "mp4", "flv", "webm", "mkv", "playlist", "manifest",
        "/hls/", "/dash/", "/video/", "/media/", "/stream/", "playurl", "play_url"
    )

    private val formats = listOf(
        VideoFormat(
            "m3u8",
            listOf(
                "application/vnd.apple.mpegurl",
                "application/mpegurl",
                "application/x-mpegurl",
                "audio/mpegurl",
                "audio/x-mpegurl"
            )
        ),
        VideoFormat("mp4", listOf("video/mp4", "application/mp4", "video/h264")),
        VideoFormat("flv", listOf("video/x-flv")),
        VideoFormat("f4v", listOf("video/x-f4v")),
        VideoFormat("webm", listOf("video/webm")),
        VideoFormat("mpeg", listOf("video/vnd.mpegurl", "video/mpeg")),
        VideoFormat("mkv", listOf("video/x-matroska")),
        VideoFormat("mov", listOf("video/quicktime"))
    )

    fun containsVideoExtension(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val path = pathOf(url).lowercase()
        val query = queryOf(url).lowercase()
        return extensionList.any { ext ->
            path.endsWith(".$ext") || path.contains(".$ext/") ||
                query.contains(".$ext") || query.contains("format=$ext")
        }
    }

    fun isLikeVideo(fullUrl: String): Boolean {
        return try {
            val extension = pathExtension(fullUrl)
            if (extension.isBlank()) true else extensionList.contains(extension)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Broad intake filter for WebView resource interception.
     * Prefer false negatives over flooding the sniffer with every asset.
     */
    fun isSniffCandidate(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val path = pathOf(url).lowercase()
        val ext = pathExtension(url)
        if (ext in nonMediaExtensions) return false
        if (containsVideoExtension(url)) return true
        val haystack = (path + "?" + queryOf(url)).lowercase()
        return mediaPathHints.any { haystack.contains(it) }
    }

    fun detectVideoFormat(url: String, mime: String?): VideoFormat? {
        val extension = pathExtension(url)
        when (extension) {
            "m3u8" -> return formats.first { it.name == "m3u8" }
            "mp4" -> return formats.first { it.name == "mp4" }
            "flv" -> return formats.first { it.name == "flv" }
            "f4v" -> return formats.first { it.name == "f4v" }
            "webm" -> return formats.first { it.name == "webm" }
            "mkv" -> return formats.first { it.name == "mkv" }
            "mov" -> return formats.first { it.name == "mov" }
            "mpeg", "mpg" -> return formats.first { it.name == "mpeg" }
            "ts" -> return null // transport segment, not a downloadable package by itself
        }

        val effectiveMime = mime?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        if (effectiveMime.isBlank()) return null

        // Prefer explicit video/* before ambiguous octet-stream.
        if (effectiveMime.startsWith("video/")) {
            formats.firstOrNull { format ->
                format.mimeList.any { effectiveMime.contains(it) }
            }?.let { return it }
            return VideoFormat("mp4", listOf(effectiveMime))
        }

        formats.firstOrNull { format ->
            format.mimeList.any { effectiveMime.contains(it) }
        }?.let { return it }

        // Ambiguous binary: only treat as media when URL hints so.
        if (effectiveMime.contains("application/octet-stream") ||
            effectiveMime.contains("binary/octet-stream")
        ) {
            if (containsVideoExtension(url) || isSniffCandidate(url)) {
                return when {
                    url.lowercase().contains("m3u8") -> formats.first { it.name == "m3u8" }
                    else -> formats.first { it.name == "mp4" }
                }
            }
        }
        return null
    }

    private fun pathOf(url: String): String = try {
        URL(url).path.orEmpty()
    } catch (_: Exception) {
        url.substringBefore('?')
    }

    private fun queryOf(url: String): String = try {
        URL(url).query.orEmpty()
    } catch (_: Exception) {
        url.substringAfter('?', "")
    }

    private fun pathExtension(url: String): String {
        val path = pathOf(url)
        val name = path.substringAfterLast('/')
        if (!name.contains('.')) return ""
        return name.substringAfterLast('.').lowercase()
    }
}

object M3u8Util {
    fun figureDuration(url: String): Double {
        val content = HttpClient.getString(url)
        var subFileFound = false
        var total = 0.0
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (subFileFound) {
                if (line.startsWith("#")) return 0.0
                val sub = URL(URL(url), line).toString()
                return figureDuration(sub)
            }
            if (!line.startsWith("#")) continue
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                subFileFound = true
                continue
            }
            if (line.startsWith("#EXTINF:")) {
                val sep = line.indexOf(',').let { if (it <= "#EXTINF:".length) line.length else it }
                val value = line.substring("#EXTINF:".length, sep).trim().toDoubleOrNull() ?: return 0.0
                total += value
            }
        }
        return total
    }

    fun looksLikeM3u8(content: String): Boolean {
        val head = content.trimStart().take(32)
        return head.startsWith("#EXTM3U")
    }
}
