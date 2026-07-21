package com.holeintimes.vbrowser.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Surface
import com.holeintimes.vbrowser.data.sniff.VideoTitleResolver
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.holeintimes.vbrowser.R
import com.holeintimes.vbrowser.data.media.MediaLibrary
import com.holeintimes.vbrowser.data.sniff.VideoFormatUtil
import com.holeintimes.vbrowser.domain.VideoInfo
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onOpenMenu: () -> Unit = {},
    isHomeVisible: Boolean = true,
    isDrawerOpen: Boolean = false,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var urlInput by remember(state.currentUrl) { mutableStateOf(state.currentUrl) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var foundExpanded by remember { mutableStateOf(false) }
    var urlEditing by remember { mutableStateOf(false) }

    LaunchedEffect(state.found.size) {
        if (state.found.isNotEmpty() && !foundExpanded) foundExpanded = true
    }

    // Disabled while drawer is open so MainActivity BackHandler closes the drawer first.
    BackHandler(enabled = isHomeVisible && !isDrawerOpen && urlEditing) {
        urlEditing = false
        urlInput = state.currentUrl
    }

    BackHandler(enabled = isHomeVisible && !isDrawerOpen && state.canGoBack && !urlEditing) {
        webView?.goBack()
    }

    LaunchedEffect(state.pendingLoadUrl) {
        val pending = state.pendingLoadUrl ?: return@LaunchedEffect
        webView?.loadUrl(pending)
        viewModel.consumePendingLoad()
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (isHomeVisible) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 2.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onOpenMenu) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.nav_menu))
                        }
                        IconButton(onClick = { webView?.goBack() }, enabled = state.canGoBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                        IconButton(onClick = { webView?.goForward() }, enabled = state.canGoForward) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.forward))
                        }
                        IconButton(onClick = { webView?.reload() }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                        }
                    }
                    BrowserUrlBar(
                        currentUrl = state.currentUrl,
                        pageTitle = state.pageTitle,
                        editing = urlEditing,
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        onStartEdit = { urlEditing = true },
                        onSubmit = { raw ->
                            urlEditing = false
                            viewModel.onUrlSubmitted(raw)
                        },
                        onCancelEdit = {
                            urlEditing = false
                            urlInput = state.currentUrl
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    if (state.isLoading) {
                        if (state.progress in 1..99) {
                            LinearProgressIndicator(
                                progress = { state.progress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            BrowserWebView(
                forceDark = state.prefs.forceWebDarkMode,
                interactive = isHomeVisible,
                onCreated = { webView = it },
                onPageStarted = {
                    viewModel.onPageStarted(it)
                    if (!urlEditing) urlInput = it
                },
                onTitleReceived = viewModel::onTitleReceived,
                onPageFinished = { url, title ->
                    viewModel.onPageFinished(url, title)
                    if (!urlEditing) urlInput = url
                    viewModel.onNavState(
                        webView?.canGoBack() == true,
                        webView?.canGoForward() == true
                    )
                    if (state.prefs.forceWebDarkMode) {
                        webView?.evaluateJavascript(DARK_CSS_JS, null)
                    }
                },
                onPageFailed = viewModel::onPageFailed,
                onProgress = viewModel::onProgress,
                shouldOverride = viewModel::onOverrideUrl,
                onMediaDetected = viewModel::maybeEnqueue,
                initialUrl = state.currentUrl
            )

            if (isHomeVisible && state.prefs.showFoundList && (foundExpanded || state.found.isNotEmpty())) {
                FoundListPanel(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                    expanded = foundExpanded,
                    onToggle = { foundExpanded = !foundExpanded },
                    items = state.found,
                    downloaded = state.downloadedUrls,
                    onClear = viewModel::clearFound,
                    onDownload = viewModel::download
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserUrlBar(
    currentUrl: String,
    pageTitle: String,
    editing: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onSubmit: (String) -> Unit,
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboard = LocalSoftwareKeyboardController.current

    if (editing) {
        val focusRequester = remember { FocusRequester() }
        // Fresh each edit session so a prior cancel cannot leave gainedFocus stuck true.
        var gainedFocus by remember { mutableStateOf(false) }
        var fieldValue by remember {
            mutableStateOf(TextFieldValue(value, TextRange(0, value.length)))
        }

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        fun submit() {
            keyboard?.hide()
            onSubmit(fieldValue.text)
        }

        OutlinedTextField(
            value = fieldValue,
            onValueChange = {
                fieldValue = it
                onValueChange(it.text)
            },
            modifier = modifier
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        gainedFocus = true
                    } else if (gainedFocus) {
                        keyboard?.hide()
                        onCancelEdit()
                    }
                },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.url_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            leadingIcon = if (currentUrl.startsWith("https://")) {
                { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.padding(start = 4.dp)) }
            } else null,
            trailingIcon = {
                IconButton(
                    onClick = { submit() },
                    modifier = Modifier.focusProperties { canFocus = false }
                ) {
                    Icon(Icons.Default.Done, contentDescription = stringResource(R.string.go))
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    } else {
        val displayTitle = pageTitle.trim().takeIf { title ->
            title.isNotBlank() && !title.equals(formatUrlForDisplay(currentUrl), ignoreCase = true)
        }
        val isSecure = currentUrl.startsWith("https://")
        Surface(
            modifier = modifier,
            onClick = onStartEdit,
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSecure) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    if (displayTitle != null) {
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatUrlForDisplay(currentUrl),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = formatUrlForDisplay(currentUrl).ifBlank { stringResource(R.string.url_hint) },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun formatUrlForDisplay(url: String): String {
    if (url.isBlank()) return ""
    return runCatching {
        val uri = Uri.parse(url)
        val host = uri.host?.removePrefix("www.") ?: return url.stripScheme()
        val path = uri.path?.takeIf { it.isNotBlank() && it != "/" }.orEmpty()
        val query = uri.query?.let { "?$it" }.orEmpty()
        host + path + query
    }.getOrElse { url.stripScheme() }
}

private fun String.stripScheme(): String =
    removePrefix("https://").removePrefix("http://")

@Composable
private fun FoundListPanel(
    modifier: Modifier,
    expanded: Boolean,
    onToggle: () -> Unit,
    items: List<VideoInfo>,
    downloaded: Set<String>,
    onClear: () -> Unit,
    onDownload: (VideoInfo) -> Unit
) {
    if (!expanded) {
        Card(
            modifier = modifier.clickable(onClick = onToggle),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)
            ),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${stringResource(R.string.found)} (${items.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Icon(
                    Icons.Default.ExpandLess,
                    contentDescription = stringResource(R.string.expand),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
        return
    }

    Card(
        modifier = modifier.width(320.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${stringResource(R.string.found)} (${items.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (items.isNotEmpty()) {
                        TextButton(onClick = onClear) { Text(stringResource(R.string.clear)) }
                    }
                    IconButton(onClick = onToggle) {
                        Icon(Icons.Default.ExpandMore, contentDescription = stringResource(R.string.collapse))
                    }
                }
            }
            if (items.isEmpty()) {
                Text(
                    stringResource(R.string.no_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 240.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(items, key = { it.url }) { item ->
                        FoundRow(
                            item = item,
                            isDownloaded = item.url in downloaded,
                            onDownload = { onDownload(item) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun FoundRow(
    item: VideoInfo,
    isDownloaded: Boolean,
    onDownload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = VideoTitleResolver.displayTitle(item),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            val meta = buildString {
                append(item.videoFormat.name.uppercase())
                if (item.videoFormat.name == "m3u8" && item.duration > 0) {
                    append(" · ${item.duration.toInt()}s")
                } else if (item.size > 0) {
                    append(" · ${MediaLibrary.formatBytes(item.size)}")
                }
                if (isDownloaded) append(" · ${stringResource(R.string.downloaded)}")
            }
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(onClick = onDownload, enabled = !isDownloaded) {
            Icon(Icons.Default.Download, contentDescription = stringResource(R.string.download))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserWebView(
    forceDark: Boolean,
    interactive: Boolean,
    onCreated: (WebView) -> Unit,
    onPageStarted: (String) -> Unit,
    onTitleReceived: (String?, String?) -> Unit,
    onPageFinished: (String, String?) -> Unit,
    onPageFailed: (String) -> Unit,
    onProgress: (Int) -> Unit,
    shouldOverride: (String) -> Boolean,
    onMediaDetected: (String, String) -> Unit,
    initialUrl: String
) {
    val context = LocalContext.current
    val mediaCallback = rememberUpdatedState(onMediaDetected)
    val overrideCallback = rememberUpdatedState(shouldOverride)
    val pageStartedCb = rememberUpdatedState(onPageStarted)
    val titleReceivedCb = rememberUpdatedState(onTitleReceived)
    val pageFinishedCb = rememberUpdatedState(onPageFinished)
    val pageFailedCb = rememberUpdatedState(onPageFailed)
    val progressCb = rememberUpdatedState(onProgress)
    val forceDarkState = rememberUpdatedState(forceDark)

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            TouchAwareWebView(context).apply {
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                isNestedScrollingEnabled = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.userAgentString = com.holeintimes.vbrowser.data.http.HttpClient.USER_AGENT
                settings.mediaPlaybackRequiresUserGesture = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                addJavascriptInterface(
                    SniffJsBridge { url, title -> mediaCallback.value(url, title) },
                    "VBrowserSniffer"
                )
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        url?.let { pageStartedCb.value(it) }
                        view?.evaluateJavascript(SNIFF_HOOK_JS, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (url != null) pageFinishedCb.value(url, view?.title)
                        if (forceDarkState.value) view?.evaluateJavascript(DARK_CSS_JS, null)
                        view?.evaluateJavascript(SNIFF_HOOK_JS, null)
                        view?.evaluateJavascript(SCAN_MEDIA_ELEMENTS_JS, null)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) {
                            pageFailedCb.value(request.url.toString())
                        }
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        return overrideCallback.value(url)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        val url = request?.url?.toString()
                        if (url != null &&
                            request?.method.equals("GET", ignoreCase = true) &&
                            VideoFormatUtil.isSniffCandidate(url)
                        ) {
                            mediaCallback.value(url, "")
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        titleReceivedCb.value(view?.url, title)
                    }

                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        progressCb.value(newProgress)
                    }
                }
                loadUrl(initialUrl)
                onCreated(this)
            }
        },
        update = { view ->
            // Keep WebView alive for sniffing, but remove it from hit-testing when
            // another tab is shown (AndroidView can otherwise steal touches).
            view.visibility = if (interactive) View.VISIBLE else View.INVISIBLE
            view.isEnabled = interactive
            view.isClickable = interactive
            view.isFocusable = interactive
            view.isFocusableInTouchMode = interactive
            if (forceDark) view.evaluateJavascript(DARK_CSS_JS, null)
        }
    )
}

private class SniffJsBridge(
    private val onMedia: (String, String) -> Unit
) {
    @JavascriptInterface
    fun onMediaUrl(url: String?) {
        if (!url.isNullOrBlank()) onMedia(url, "")
    }

    @JavascriptInterface
    fun onMediaUrls(json: String?) {
        if (json.isNullOrBlank()) return
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                when (val item = arr.opt(i)) {
                    is String -> if (item.isNotBlank()) onMedia(item, "")
                    is JSONObject -> {
                        val u = item.optString("url")
                        if (u.isNotBlank()) onMedia(u, item.optString("title", ""))
                    }
                }
            }
        }
    }
}

private const val DARK_CSS_JS = """
(function(){
  var id='vbrowser-dark';
  if(document.getElementById(id)) return;
  var s=document.createElement('style');
  s.id=id;
  s.innerHTML='html,body{background:#121212!important;color:#e0e0e0!important;}a{color:#90caf9!important;}img,video{opacity:.9}';
  (document.head||document.documentElement).appendChild(s);
})();
"""

private const val SNIFF_HOOK_JS = """
(function(){
  if(window.__vbrowserSniffHooked) return;
  window.__vbrowserSniffHooked = true;
  function report(u){
    try{
      if(!u || typeof u !== 'string') return;
      if(u.indexOf('http')!==0) return;
      if(window.VBrowserSniffer && VBrowserSniffer.onMediaUrl){
        VBrowserSniffer.onMediaUrl(u);
      }
    }catch(e){}
  }
  function looksMedia(u){
    if(!u) return false;
    var s = String(u).toLowerCase();
    return s.indexOf('.m3u8')>=0 || s.indexOf('.mp4')>=0 || s.indexOf('.flv')>=0 ||
      s.indexOf('.webm')>=0 || s.indexOf('.mkv')>=0 || s.indexOf('playlist')>=0 ||
      s.indexOf('/hls/')>=0 || s.indexOf('manifest')>=0 || s.indexOf('playurl')>=0;
  }
  try{
    var xo = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url){
      try{ if(looksMedia(url)) report(url); }catch(e){}
      return xo.apply(this, arguments);
    };
  }catch(e){}
  try{
    var ofetch = window.fetch;
    if(ofetch){
      window.fetch = function(input, init){
        try{
          var u = (typeof input === 'string') ? input : (input && input.url);
          if(looksMedia(u)) report(u);
        }catch(e){}
        return ofetch.apply(this, arguments);
      };
    }
  }catch(e){}
})();
"""

private const val SCAN_MEDIA_ELEMENTS_JS = """
(function(){
  try{
    var pageTitle = '';
    try{
      var og = document.querySelector('meta[property="og:title"]');
      pageTitle = (og && og.content) || document.title || '';
    }catch(e){}
    function titleFor(el){
      if(!el) return pageTitle;
      var t = el.getAttribute('title') || el.getAttribute('aria-label') || '';
      if(t) return t;
      try{
        var cap = el.closest('figure');
        if(cap){
          var fig = cap.querySelector('figcaption');
          if(fig && fig.textContent) return fig.textContent.trim();
        }
        var h = el.closest('div,section,article');
        if(h){
          var heading = h.querySelector('h1,h2,h3');
          if(heading && heading.textContent) return heading.textContent.trim();
        }
      }catch(e){}
      return pageTitle;
    }
    var out = [];
    var seen = {};
    function add(u, title){
      if(!u) return;
      u = String(u);
      if(u.indexOf('http')!==0) return;
      if(seen[u]) return;
      seen[u] = true;
      out.push({url:u, title: title || pageTitle || ''});
    }
    document.querySelectorAll('video,audio').forEach(function(el){
      add(el.currentSrc || el.src, titleFor(el));
      if(el.getAttribute) add(el.getAttribute('src'), titleFor(el));
    });
    document.querySelectorAll('source').forEach(function(el){
      add(el.src || el.getAttribute('src'), titleFor(el.closest('video,audio') || el));
    });
    if(out.length && window.VBrowserSniffer && VBrowserSniffer.onMediaUrls){
      VBrowserSniffer.onMediaUrls(JSON.stringify(out));
    }
  }catch(e){}
})();
"""
