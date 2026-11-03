package com.livewire.tv.feature.epg

import androidx.annotation.OptIn
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.livewire.tv.R
import com.livewire.tv.core.player.ExoPlayerEngine
import com.livewire.tv.core.player.PlaybackState
import com.livewire.tv.feature.home.ChannelLogo
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.PlaybackSource
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireTheme
import com.livewire.tv.ui.theme.LiveWireType
import kotlinx.coroutines.delay

/** How long focus must rest on a cell before its channel starts previewing. */
internal const val PREVIEW_DELAY_MS = 2_000L

/**
 * One muted player that the Guide owns. It plays the channel under focus in the details band's
 * preview window once focus has rested for [PREVIEW_DELAY_MS], and stops the moment focus moves
 * on, the Guide leaves the screen, or the app goes to the background.
 */
@OptIn(UnstableApi::class)
internal class GuidePreviewPlayer(context: android.content.Context) {
    val engine = ExoPlayerEngine(context).also {
        // Muted, and never takes audio focus: whatever else is playing keeps its sound.
        it.exoPlayer.volume = 0f
    }
    /** Compose state, so the window drops the picture the moment a preview stops. */
    var playingStreamId: String? by mutableStateOf(null)
        private set

    fun play(streamId: String, source: PlaybackSource) {
        playingStreamId = streamId
        engine.open(source.url, play = true, headers = source.headers, retryUrl = source.retryUrl, onStop = source.onStop)
        engine.exoPlayer.volume = 0f
    }

    /** Stops playback and drops the stream, so the provider connection closes now. */
    fun stop() {
        playingStreamId = null
        engine.stop()
    }

    fun release() = engine.release()
}

/**
 * Drives [player] from the focused channel. [streamId] is null whenever nothing in the grid
 * holds focus (the category column or drawer does), which stops the preview.
 */
@Composable
internal fun rememberGuidePreview(
    streamId: String?,
    enabled: Boolean,
    resolve: suspend (String) -> PlaybackSource?,
): GuidePreviewState {
    val context = LocalContext.current
    val player = remember { GuidePreviewPlayer(context) }
    val state = remember { GuidePreviewState(player) }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // Backgrounding the app (Home button, screensaver) stops the stream.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val target = streamId.takeIf { enabled && resumed }
    LaunchedEffect(target) {
        state.countdown = 0f
        if (target != state.failedStreamId) state.failedStreamId = null
        if (target == null || target != player.playingStreamId) player.stop()
        if (target == null || target == player.playingStreamId) return@LaunchedEffect
        // Count down in small steps so the window can show a thin progress line.
        val steps = 20
        repeat(steps) { i ->
            delay(PREVIEW_DELAY_MS / steps)
            state.countdown = (i + 1f) / steps
        }
        val source = runCatching { resolve(target) }.getOrNull()
        state.countdown = 0f
        if (source != null) player.play(target, source) else state.failedStreamId = target
    }
    return state
}

internal class GuidePreviewState(val player: GuidePreviewPlayer) {
    /** 0 while idle or playing; rises to 1 during the wait before a preview starts. */
    var countdown by mutableFloatStateOf(0f)

    /** The channel whose preview could not start (no source), so its window says so. */
    var failedStreamId: String? by mutableStateOf(null)
}

/**
 * The preview window at the right of the details band: a 16:9 tile the height of the band.
 * It always takes its place, so the grid never moves when a preview starts or stops. At rest
 * it shows the focused channel's logo, with a thin line along the bottom while the preview
 * counts down. Once the first frame plays, the muted picture fades in over the logo,
 * letterboxed (never cropped), with a PREVIEW · MUTED tag in its top-left corner. Nothing
 * else is drawn over the picture, and the picture is drawn under nothing else.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun GuidePreviewWindow(state: GuidePreviewState, channel: LiveChannel, modifier: Modifier = Modifier) {
    val status by state.player.engine.status.collectAsStateWithLifecycle()
    val mine = state.player.playingStreamId == channel.streamId
    val showing = mine && status.state == PlaybackState.PLAYING
    val failed = state.failedStreamId == channel.streamId || (mine && status.state == PlaybackState.ERROR)
    val alpha by animateFloatAsState(if (showing) 1f else 0f, tween(300), label = "previewAlpha")
    val shape = RoundedCornerShape(LiveWireDimens.RadiusCard)

    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(LiveWireColors.Surface)
            .border(LiveWireDimens.RestBorder, LiveWireColors.BorderStrong, shape),
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ChannelLogo(channel, Modifier.fillMaxWidth(0.42f).aspectRatio(1.6f), textSize = 14)
            // Some providers take several seconds to open a stream; say so rather than look stuck.
            val note = when {
                failed -> "Preview unavailable"
                mine && !showing -> "Starting preview…"
                else -> null
            }
            if (note != null) {
                Spacer(Modifier.height(8.dp))
                Text(note, style = LiveWireType.Sub, color = LiveWireTheme.onSurfaceMuted, maxLines = 1)
            }
        }

        if (alpha > 0f) {
            Box(Modifier.matchParentSize().alpha(alpha).background(Color.Black)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        (android.view.LayoutInflater.from(ctx).inflate(R.layout.livewire_player_view, android.widget.FrameLayout(ctx), false) as PlayerView)
                            .apply {
                                player = state.player.engine.exoPlayer
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                    },
                    onRelease = { it.player = null },
                )
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(LiveWireDimens.RadiusCell))
                        .background(LiveWireColors.Canvas.copy(alpha = 0.72f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    // Smaller than the shared tag style: the window is small and this sits on the picture.
                    Text(
                        "PREVIEW · MUTED",
                        style = LiveWireTheme.tokens.tag.copy(fontSize = 6.5.sp, letterSpacing = 0.12.em),
                        color = LiveWireTheme.onSurface,
                    )
                }
            }
        }

        // A thin neutral line along the bottom while waiting to start (never a focus colour).
        val progress by animateFloatAsState(state.countdown, tween(100, easing = LinearEasing), label = "previewCountdown")
        if (state.countdown > 0f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp)) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(LiveWireColors.ProgressFill))
            }
        }
    }
}
