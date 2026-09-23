package com.livewire.tv.feature.player

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import com.livewire.tv.core.player.ExoPlayerEngine
import com.livewire.tv.core.player.PlaybackState
import com.livewire.tv.core.player.PlaybackStatus
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * Full-screen player. Hosts a Media3 PlayerView bound to an [ExoPlayerEngine]
 * (hardware decode), overlays buffering/error/paused state, and handles TV remote
 * keys: OK/center = play-pause, Back = exit. Keeps the screen awake during playback.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    streamUrl: String,
    title: String,
    onExit: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val engine = remember { ExoPlayerEngine(context) }
    val status by engine.status.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    // Info bar visibility: shown on open and on any remote key, auto-hides after a beat.
    var controlsVisible by remember { mutableStateOf(true) }
    var revealTick by remember { mutableStateOf(0) }
    fun reveal() { controlsVisible = true; revealTick++ }

    // Open the stream once; release the engine when leaving.
    DisposableEffect(streamUrl) {
        engine.open(streamUrl)
        onDispose { engine.release() }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Auto-hide the info bar ~4s after the latest reveal, unless paused (keep it up while paused).
    LaunchedEffect(revealTick, status.state) {
        if (status.state == PlaybackState.PAUSED) return@LaunchedEffect
        kotlinx.coroutines.delay(4000)
        controlsVisible = false
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                    reveal() // any remote activity surfaces the info bar
                    when (ev.key.nativeKeyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_SPACE -> {
                            engine.togglePlayPause(); true
                        }
                        KeyEvent.KEYCODE_BACK,
                        KeyEvent.KEYCODE_ESCAPE -> {
                            onExit(); true
                        }
                        else -> false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false          // we drive controls via the remote
                        keepScreenOn = true            // wakelock while playing
                        player = engine.exoPlayer
                    }
                },
            )

            when (status.state) {
                PlaybackState.BUFFERING -> CircularProgressIndicator()
                PlaybackState.ERROR -> ErrorOverlay(
                    message = status.errorMessage ?: "Playback error",
                    onRetry = { engine.retry() },
                )
                else -> {}
            }

            // Auto-hiding now-playing info bar, pinned to the top.
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                InfoBar(title = title, status = status)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InfoBar(title: String, status: PlaybackStatus) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xCC000000), Color(0x00000000)),
                ),
            )
            .padding(horizontal = 32.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.ifBlank { "Now playing" },
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier.padding(end = 12.dp),
        )
        val badge = when {
            status.state == PlaybackState.PAUSED -> "❚❚ PAUSED"
            status.isLive -> "● LIVE"
            else -> null
        }
        if (badge != null) {
            Text(
                badge,
                style = MaterialTheme.typography.labelLarge,
                color = if (status.isLive && status.state != PlaybackState.PAUSED)
                    Color(0xFF00E0D1) else Color.White,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = Color(0xFFFF6E6E))
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text("Retry")
            }
        }
    }
}
