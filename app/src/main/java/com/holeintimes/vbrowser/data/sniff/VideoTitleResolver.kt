package com.holeintimes.vbrowser.data.sniff

import com.holeintimes.vbrowser.domain.VideoInfo
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Picks the best human-readable title for a sniffed/downloaded video.
 *
 * Priority: element/OG title → cleaned page title → URL path segment → host.
 */
object VideoTitleResolver {

    private val genericPageTitles = setOf(
        "untitled", "video", "play", "player", "loading", "home", "index",
        "watch", "在线播放", "播放", "视频", "首页", "loading..."
    )

    fun resolve(
        mediaUrl: String,
        pageTitle: String = "",
        mediaTitle: String = "",
        pageUrl: String = ""
    ): String {
        val fromMedia = sanitize(mediaTitle)
        if (fromMedia.isNotBlank()) return fromMedia

        val fromPage = sanitize(pageTitle)
        if (fromPage.isNotBlank() && !isGenericTitle(fromPage)) return fromPage

        titleFromUrlPath(mediaUrl)?.let { return it }
        if (pageUrl.isNotBlank()) {
            titleFromUrlPath(pageUrl)?.let { return it }
        }

        hostLabel(mediaUrl)?.let { return it }
        if (fromPage.isNotBlank()) return fromPage
        return "Video"
    }

    fun displayTitle(info: VideoInfo): String {
        val stored = sanitize(info.sourcePageTitle)
        if (stored.isNotBlank() && !isGenericTitle(stored) && !looksLikeUuid(stored)) {
            return stored
        }
        return resolve(
            mediaUrl = info.url,
            pageTitle = info.sourcePageTitle,
            pageUrl = info.sourcePageUrl
        )
    }

    private fun sanitize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace(Regex("\\s+"), " ")
            .trim()
            .removeSuffix(" - YouTube")
            .removeSuffix(" | YouTube")
            .take(120)
    }

    private fun isGenericTitle(title: String): Boolean {
        val lower = title.lowercase()
        if (lower in genericPageTitles) return true
        if (lower.length <= 2) return true
        // Site name only: "Example.com" or bare domain
        if (Regex("^[\\w.-]+\\.(com|net|org|tv|cc|io|cn)$", RegexOption.IGNORE_CASE).matches(lower)) {
            return true
        }
        return false
    }

    private fun looksLikeUuid(s: String): Boolean =
        s.length in 24..36 && s.all { it.isLetterOrDigit() }

    private fun titleFromUrlPath(url: String): String? {
        return try {
            val path = URL(url).path
            val segment = path.trim('/').substringAfterLast('/')
            if (segment.isBlank()) return null
            val decoded = URLDecoder.decode(segment, StandardCharsets.UTF_8.name())
            val base = decoded.substringBeforeLast('.')
            val candidate = sanitize(base.replace('_', ' ').replace('-', ' '))
            if (candidate.length < 3 || isGenericTitle(candidate)) null
            else candidate
        } catch (_: Exception) {
            null
        }
    }

    private fun hostLabel(url: String): String? {
        return try {
            val host = URL(url).host.removePrefix("www.")
            if (host.isBlank()) null else host
        } catch (_: Exception) {
            null
        }
    }
}
