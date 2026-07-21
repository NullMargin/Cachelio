package com.holeintimes.vbrowser.ui.player

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.holeintimes.vbrowser.AppContainer
import com.holeintimes.vbrowser.data.media.PlaylistOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PlayerScreen(
    container: AppContainer,
    mediaPath: String,
    onBack: () -> Unit,
    onPlayNext: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val packageDir = remember(mediaPath) { File(mediaPath) }
    val isM3u8 = File(packageDir, "index.m3u8").exists()

    val player = remember {
        ExoPlayer.Builder(context).build()
    }

    var brightness by remember { mutableFloatStateOf(0.5f) }
    var showBrightnessHud by remember { mutableStateOf(false) }
    val advancedToNext = remember(mediaPath) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val originalWindowBrightness = remember(activity) {
        activity?.window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }

    LaunchedEffect(activity) {
        val window = activity?.window ?: return@LaunchedEffect
        val saved = container.prefs.preferences.first().lastBrightness
        val initial = when {
            saved in 0.05f..1f -> saved
            else -> {
                val lp = window.attributes
                if (lp.screenBrightness >= 0f) lp.screenBrightness else 0.5f
            }
        }
        brightness = initial
        val lp = window.attributes
        lp.screenBrightness = initial
        window.attributes = lp
    }

    LaunchedEffect(brightness, showBrightnessHud) {
        if (!showBrightnessHud) return@LaunchedEffect
        delay(1200)
        showBrightnessHud = false
    }

    LaunchedEffect(mediaPath) {
        withContext(Dispatchers.IO) {
            container.prefs.setLastPlayedPath(mediaPath)
        }
        val playUrl = if (isM3u8) {
            container.hlsServer.hlsUrlForPackage(packageDir, container.rootDir)
        } else {
            val video = packageDir.listFiles()?.firstOrNull { it.name.startsWith("video.") }
                ?: return@LaunchedEffect
            video.toURI().toString()
        }
        val positionFile = File(packageDir, "playback_position.txt")
        val startPos = positionFile.takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0L
        player.setMediaItem(MediaItem.fromUri(playUrl))
        player.prepare()
        if (startPos > 0) player.seekTo(startPos)
        player.playWhenReady = true
    }

    DisposableEffect(player, mediaPath) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED) return
                if (!advancedToNext.compareAndSet(false, true)) return
                scope.launch {
                    val prefs = container.prefs.preferences.first()
                    if (!prefs.autoPlayNext) return@launch
                    // Keep the library visibility aligned with the Files page session.
                    container.mediaLibrary.refresh(container.privacySession.isUnlocked.value)
                    val next = PlaylistOrder.nextAfter(
                        currentPath = mediaPath,
                        items = container.mediaLibrary.items.value,
                        filter = prefs.filesSearchFilter,
                        sort = prefs.filesSort
                    ) ?: return@launch
                    onPlayNext(next.path)
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> player.pause()
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            runCatching {
                File(packageDir, "playback_position.txt").writeText(player.currentPosition.toString())
            }
            activity?.window?.let { window ->
                val lp = window.attributes
                lp.screenBrightness = originalWindowBrightness
                window.attributes = lp
            }
            scope.launch(Dispatchers.IO) {
                container.prefs.setLastBrightness(brightness)
            }
            context.unregisterReceiver(receiver)
            player.release()
        }
    }

    fun applyBrightness(value: Float) {
        val next = value.coerceIn(0.05f, 1f)
        brightness = next
        showBrightnessHud = true
        activity?.window?.let { window ->
            val lp = window.attributes
            lp.screenBrightness = next
            window.attributes = lp
        }
        scope.launch(Dispatchers.IO) {
            container.prefs.setLastBrightness(next)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                }
            },
            update = { it.player = player }
        )

        // Left-edge brightness zone — avoids conflicting with ExoPlayer seek/volume gestures.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(48.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        applyBrightness(brightness - dragAmount / 600f)
                    }
                }
        )

        AnimatedVisibility(
            visible = showBrightnessHud,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Icon(
                    Icons.Default.Brightness6,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
                LinearProgressIndicator(
                    progress = { brightness },
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .width(120.dp)
                )
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
        }
    }
}
