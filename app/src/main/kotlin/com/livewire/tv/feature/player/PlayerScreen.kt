package com.livewire.tv.feature.player

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.livewire.tv.core.player.ExoPlayerEngine
import com.livewire.tv.core.player.PlaybackState
import com.livewire.tv.core.player.PlaybackStatus
import com.livewire.tv.feature.providers.domain.PlaybackTarget

/**
 * Full-screen player. The navigation layer passes only [PlaybackTarget], so the
 * credential-bearing stream URL is resolved here and never placed in route state.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    target: PlaybackTarget,
    title: String,
    onExit: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val engine = remember { ExoPlayerEngine(context) }
    val status by engine.status.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    var controlsVisible by remember { mutableStateOf(true) }
    var revealTick by remember { mutableStateOf(0) }
    fun reveal() {
        controlsVisible = true
        revealTick++
    }

    LaunchedEffect(target) { viewModel.resolve(target) }
    LaunchedEffect(source.streamUrl) { source.streamUrl?.let(engine::open) }
    DisposableEffect(Unit) { onDispose { engine.release() } }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LaunchedEffect(revealTick, status.state) {
        if (status.state == PlaybackState.PAUSED) return@LaunchedEffect
        kotlinx.coroutines.delay(4_000)
        controlsVisible = false
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    reveal()
                    when (event.key.nativeKeyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_SPACE -> {
                            engine.togglePlayPause()
                            true
                        }
                        KeyEvent.KEYCODE_BACK,
                        KeyEvent.KEYCODE_ESCAPE -> {
                            onExit()
                            true
                        }
                        else -> false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        keepScreenOn = true
                        player = engine.exoPlayer
                    }
                },
            )

            when {
                source.loading -> CircularProgressIndicator()
                source.error != null -> SafeErrorOverlay(source.error!!, onRetry = { viewModel.resolve(target) })
                status.state == PlaybackState.BUFFERING -> CircularProgressIndicator()
                status.state == PlaybackState.ERROR -> SafeErrorOverlay(
                    message = status.errorMessage ?: "Playback failed. Try again.",
                    onRetry = engine::retry,
                )
            }

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
            .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color(0x00000000))))
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
                color = if (status.isLive && status.state != PlaybackState.PAUSED) Color(0xFF00E0D1) else Color.White,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SafeErrorOverlay(message: String, onRetry: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = Color(0xFFFF6E6E))
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text("Retry")
            }
        }
    }
}
