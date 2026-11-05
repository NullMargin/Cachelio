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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.holeintimes.vbrowser.AppContainer
import com.holeintimes.vbrowser.data.media.MediaLibrary
import com.holeintimes.vbrowser.data.media.PlaylistOrder
import kotlin.math.abs
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val packageDir = remember(mediaPath) { File(mediaPath) }
    val isM3u8 = File(packageDir, "index.m3u8").exists()

    val player = remember {
        ExoPlayer.Builder(context).build()
    }

    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val latestPlayerView = rememberUpdatedState(playerView)
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
        val positionFile = File(packageDir, MediaLibrary.PLAYBACK_POSITION_FILE)
        val startPos = positionFile.takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0L
        player.setMediaItem(MediaItem.fromUri(playUrl))
        player.prepare()
        if (startPos > 0) player.seekTo(startPos)
        player.playWhenReady = true
    }

    // Keep the screen on while playback is active (incl. buffering, so a long
    // buffer can't let the display sleep mid-stream). Pausing, ending, or
    // leaving the player clears the flag and restores the system timeout.
    // FLAG_KEEP_SCREEN_ON needs no WAKE_LOCK permission and is scoped to this
    // window; the ACTION_SCREEN_OFF receiver below still pauses on power key.
    DisposableEffect(activity, player) {
        val window = activity?.window
        fun updateKeepScreenOn() {
            val keepOn = player.playWhenReady &&
                (player.playbackState == Player.STATE_BUFFERING ||
                    player.playbackState == Player.STATE_READY)
            if (keepOn) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                updateKeepScreenOn()
            }
        }
        player.addListener(listener)
        updateKeepScreenOn()
        onDispose {
            player.removeListener(listener)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(player, mediaPath) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED) return
                if (!advancedToNext.compareAndSet(false, true)) return
                scope.launch {
                    val prefs = container.prefs.preferences.first()
                    if (!prefs.autoPlayNext) return@launch
                    // Same filter/sort as Files; refresh so deleted/hidden items drop out
                    // without reshuffling date order (see MediaLibrary.packageSortTime).
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

    // Pause when the app leaves the foreground (Home, Recents, another app).
    // Do not auto-resume — user must press play. Screen-off also pauses via
    // the receiver below; both paths are idempotent.
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                // Finished items restart at 0 so auto-play-next doesn't instantly re-skip them.
                val pos = if (player.playbackState == Player.STATE_ENDED) {
                    0L
                } else {
                    player.currentPosition
                }
                File(packageDir, MediaLibrary.PLAYBACK_POSITION_FILE).writeText(pos.toString())
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
                    playerView = this
                }
            },
            update = {
                it.player = player
                playerView = it
            }
        )

        // Left-edge zone: vertical drag adjusts brightness; tap toggles Media3
        // controls (this overlay would otherwise eat taps meant for PlayerView).
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(48.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalDragY = 0f
                        var dragged = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!dragged) {
                                    latestPlayerView.value?.let { view ->
                                        if (view.isControllerFullyVisible) {
                                            view.hideController()
                                        } else {
                                            view.showController()
                                        }
                                    }
                                }
                                break
                            }
                            val dy = change.positionChange().y
                            totalDragY += dy
                            if (!dragged && abs(totalDragY) > viewConfiguration.touchSlop) {
                                dragged = true
                            }
                            if (dragged) {
                                change.consume()
                                applyBrightness(brightness - dy / 600f)
                            }
                        }
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
