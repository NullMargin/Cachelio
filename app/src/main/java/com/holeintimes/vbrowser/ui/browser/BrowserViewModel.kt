package com.holeintimes.vbrowser.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.holeintimes.vbrowser.AppContainer
import com.holeintimes.vbrowser.data.sniff.VideoFormatUtil
import com.holeintimes.vbrowser.domain.BookmarkEntry
import com.holeintimes.vbrowser.domain.DetectedVideoInfo
import com.holeintimes.vbrowser.domain.HistoryEntry
import com.holeintimes.vbrowser.domain.UserPreferences
import com.holeintimes.vbrowser.domain.VideoInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal const val HOME_URL = "file:///android_asset/home.html"

data class BrowserUiState(
    val currentUrl: String = HOME_URL,
    val pageTitle: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val progress: Int = 0,
    val isLoading: Boolean = true,
    val found: List<VideoInfo> = emptyList(),
    val downloadedUrls: Set<String> = emptySet(),
    val prefs: UserPreferences = UserPreferences(),
    val history: List<HistoryEntry> = emptyList(),
    val bookmarks: List<BookmarkEntry> = emptyList(),
    val privacyUnlocked: Boolean = false,
    val pendingLoadUrl: String? = null
) {
    val visibleBookmarks: List<BookmarkEntry>
        get() = if (privacyUnlocked) bookmarks else bookmarks.filterNot { it.isPrivate }

    val isCurrentBookmarked: Boolean
        get() = visibleBookmarks.any { it.url == currentUrl }
}

class BrowserViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(BrowserUiState())

    val uiState: StateFlow<BrowserUiState> = combine(
        combine(
            _ui,
            container.sniffer.foundList,
            container.prefs.downloadedUrls,
            container.prefs.preferences,
            container.prefs.history
        ) { ui, found, urls, prefs, history ->
            ui.copy(
                found = found,
                downloadedUrls = urls,
                prefs = prefs,
                history = history
            )
        },
        container.prefs.bookmarks,
        container.privacySession.isUnlocked
    ) { ui, bookmarks, unlocked ->
        ui.copy(bookmarks = bookmarks, privacyUnlocked = unlocked)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BrowserUiState())

    init {
        viewModelScope.launch {
            var auto = false
            var downloaded = emptySet<String>()
            launch {
                container.prefs.preferences.collect { auto = it.autoDownload }
            }
            launch {
                container.prefs.downloadedUrls.collect { downloaded = it }
            }
            container.sniffer.newItem.collect { info ->
                if (auto && info.url !in downloaded) {
                    container.downloadManager.addFromVideo(info)
                }
            }
        }
    }

    fun onUrlSubmitted(raw: String) {
        val url = normalizeUrl(raw)
        _ui.value = _ui.value.copy(
            pendingLoadUrl = url,
            currentUrl = url,
            progress = 0,
            isLoading = true
        )
    }

    fun consumePendingLoad() {
        _ui.value = _ui.value.copy(pendingLoadUrl = null)
    }

    fun onPageStarted(url: String) {
        // Never let resources from the new page inherit the previous page's title.
        _ui.value = _ui.value.copy(
            currentUrl = url,
            pageTitle = "",
            progress = 0,
            isLoading = true
        )
        // Page document itself is rarely media; only enqueue when URL looks like a media resource.
        if (VideoFormatUtil.isSniffCandidate(url)) {
            maybeEnqueue(url)
        }
    }

    fun onTitleReceived(url: String?, title: String?) {
        val value = title?.trim().orEmpty()
        if (value.isBlank()) return

        val state = _ui.value
        // Ignore late callbacks from the previous document and WebView's temporary
        // URL-as-title value, which is not a useful human-readable page title.
        if (!url.isNullOrBlank() && normalizeForComparison(url) != normalizeForComparison(state.currentUrl)) {
            return
        }
        if (normalizeForComparison(value) == normalizeForComparison(state.currentUrl)) return

        _ui.value = state.copy(pageTitle = value)
    }

    fun onPageFinished(url: String, title: String?) {
        // A redirect can finish its previous document after the destination starts.
        // Do not let that stale callback hide the destination's loading indicator.
        if (normalizeForComparison(url) != normalizeForComparison(_ui.value.currentUrl)) return

        val finalTitle = title?.trim().orEmpty()
            .takeUnless { normalizeForComparison(it) == normalizeForComparison(url) }
            .orEmpty()
        val pageTitle = finalTitle.ifBlank { _ui.value.pageTitle }
        _ui.value = _ui.value.copy(
            currentUrl = url,
            pageTitle = pageTitle,
            progress = 100,
            isLoading = false
        )
        viewModelScope.launch {
            container.prefs.addHistory(HistoryEntry(url = url, title = pageTitle))
        }
    }

    fun onProgress(p: Int) {
        _ui.value = _ui.value.copy(progress = p)
    }

    fun onPageFailed(url: String) {
        if (normalizeForComparison(url) != normalizeForComparison(_ui.value.currentUrl)) return
        _ui.value = _ui.value.copy(progress = 100, isLoading = false)
    }

    fun onNavState(canBack: Boolean, canForward: Boolean) {
        _ui.value = _ui.value.copy(canGoBack = canBack, canGoForward = canForward)
    }

    fun onOverrideUrl(url: String): Boolean {
        if (url == HOME_URL) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return true
        if (VideoFormatUtil.isSniffCandidate(url) && VideoFormatUtil.isLikeVideo(url)) {
            maybeEnqueue(url)
            // Still navigate for HTML-like pages; block direct media file navigation.
            if (VideoFormatUtil.containsVideoExtension(url)) return true
        }
        return false
    }

    fun maybeEnqueue(url: String, mediaTitle: String = "") {
        if (!url.startsWith("http")) return
        if (!VideoFormatUtil.isSniffCandidate(url)) return
        val state = _ui.value
        container.sniffer.enqueue(
            DetectedVideoInfo(
                url = url,
                sourcePageUrl = state.currentUrl,
                sourcePageTitle = state.pageTitle,
                mediaTitle = mediaTitle
            )
        )
    }

    fun clearFound() = container.sniffer.clear()

    fun download(info: VideoInfo) {
        container.downloadManager.addFromVideo(info)
    }

    fun openExternalUrl(url: String) = onUrlSubmitted(url)

    fun toggleBookmark() {
        val state = uiState.value
        val url = state.currentUrl
        if (url.isBlank() || url == HOME_URL) return
        val existing = state.visibleBookmarks.firstOrNull { it.url == url }
        viewModelScope.launch {
            if (existing != null) {
                container.prefs.removeBookmark(existing.url, existing.isPrivate)
            } else {
                container.prefs.addBookmark(
                    BookmarkEntry(
                        url = url,
                        title = state.pageTitle,
                        isPrivate = state.privacyUnlocked
                    )
                )
            }
        }
    }

    fun removeBookmark(entry: BookmarkEntry) {
        viewModelScope.launch {
            container.prefs.removeBookmark(entry.url, entry.isPrivate)
        }
    }

    private fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty() || t == HOME_URL) return HOME_URL
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        if (t.contains('.') && !t.contains(' ')) return "https://$t"
        return "https://www.google.com/search?q=${java.net.URLEncoder.encode(t, "UTF-8")}"
    }

    private fun normalizeForComparison(value: String): String =
        value.trim().trimEnd('/').substringBefore('#')

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BrowserViewModel(container) as T
    }
}
