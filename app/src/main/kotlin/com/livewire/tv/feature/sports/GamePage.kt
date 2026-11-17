package com.livewire.tv.feature.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import com.livewire.tv.R
import com.livewire.tv.feature.home.ChannelLogo
import com.livewire.tv.feature.sports.domain.GameState
import com.livewire.tv.feature.sports.domain.SportsGame
import com.livewire.tv.feature.sports.domain.SportsLeague
import com.livewire.tv.feature.sports.domain.TeamSide
import com.livewire.tv.ui.theme.KeyHints
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme
import com.livewire.tv.ui.theme.LiveWireType
import com.livewire.tv.ui.theme.LocalOnFocus

/**
 * A game's page (mockup `bgrid/sports-page/b-*`): OK on a game opens it over the dimmed list.
 * The left column has the scoreboard, the league and network, the pool's game description, Watch
 * for the best source (focused, so OK twice still plays at once) and Watch from start when a
 * channel keeps an archive. The right column lists the other channels best first, each with why
 * it matched; a guide match shows the guide's listing. With nothing found, Search takes Watch's
 * place. A [Dialog] keeps D-pad focus inside it and Back closes it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun GamePage(
    game: SportsGame,
    league: SportsLeague?,
    source: GameSource?,
    options: List<SourceOption>,
    onPick: (SourceOption) -> Unit,
    onFromStart: (FromStart) -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        val firstChannel = remember { FocusRequester() }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val final = game.status.state == GameState.FINAL
        val shown = if (final) emptyList() else options
        val best = shown.firstOrNull()
        val others = shown.drop(1)
        val fromStart = remember(game, shown) { SportsList.fromStart(game, shown) }
        val none = !final && shown.isEmpty() && source == GameSource.None
        // Focus Watch (or Search) once it exists: a Finding page gains it when matching ends.
        LaunchedEffect(best?.channel?.streamId, none) { runCatching { first.requestFocus() } }
        val shape = RoundedCornerShape(12.dp)
        Box(
            Modifier.fillMaxSize().background(LiveWireColors.Scrim).padding(horizontal = 70.dp, vertical = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(LiveWireColors.Surface)
                    .border(LiveWireDimens.RestBorder, LiveWireColors.BorderStrong, shape)
                    .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 12.dp),
            ) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        Modifier.width(330.dp).fillMaxHeight().onPreviewKeyEvent { e ->
                            // Right always lands on the best of the other channels, not the row level with Watch.
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && others.isNotEmpty()) {
                                scope.launch { listState.scrollToItem(0); runCatching { firstChannel.requestFocus() } }
                                true
                            } else false
                        },
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    ) {
                        Board(game)
                        Text(
                            listOfNotNull(league?.name ?: game.leagueId.uppercase(), game.broadcastNetworks.takeIf { it.isNotEmpty() }?.let { "On ${it.joinToString(", ")}" })
                                .joinToString(" · "),
                            style = LiveWireType.Mono.copy(fontSize = 8.5.sp),
                            color = LiveWireColors.OnSurfaceMuted,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        shown.firstOrNull { it.liveNow }?.channel?.poolEvent?.description?.trim()?.takeIf { it.isNotEmpty() }?.let {
                            Text(
                                it,
                                style = LiveWireType.Sub.copy(fontSize = 10.sp, lineHeight = 14.sp),
                                color = LiveWireColors.OnSurfaceSoft,
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        when {
                            final -> Message("This game has finished.")
                            best != null -> {
                                BigButton(
                                    icon = R.drawable.ic_fav_play,
                                    title = if (best.liveNow) "Watch on Live Now" else "Watch on ${best.channel.name.trim()}",
                                    sub = best.reason,
                                    onClick = { onPick(best) },
                                    modifier = Modifier.focusRequester(first),
                                )
                                fromStart?.let { fs ->
                                    BigButton(
                                        icon = R.drawable.ic_opt_reload,
                                        title = "Watch from start · ${SportsList.clock(fs.startMs)}",
                                        sub = "Catch-up on ${fs.channel.name.trim()}",
                                        onClick = { onFromStart(fs) },
                                    )
                                }
                            }
                            none -> {
                                Text(
                                    SportsFormat.noChannelMessage(game),
                                    style = LiveWireType.Title.copy(fontSize = 13.sp, lineHeight = 17.sp),
                                    color = LiveWireColors.OnSurface,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    "None of your channels is named after this game or lists it in the guide.",
                                    style = LiveWireType.Sub,
                                    color = LiveWireColors.OnSurfaceSoft,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                BigButton(
                                    icon = R.drawable.ic_nav_search,
                                    title = "Search my channels for “${SportsList.searchQuery(game)}”",
                                    sub = "Opens Search with the team’s name",
                                    onClick = onSearch,
                                    modifier = Modifier.focusRequester(first),
                                )
                            }
                            else -> Message("Still matching channels…")
                        }
                    }
                    Spacer(Modifier.width(26.dp))
                    Box(Modifier.width(1.dp).fillMaxHeight().background(LiveWireColors.LineFaint))
                    Spacer(Modifier.width(26.dp))
                    Column(
                        Modifier.weight(1f).fillMaxHeight().onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && best != null) {
                                runCatching { first.requestFocus() }
                                true
                            } else false
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("OTHER CHANNELS", style = LiveWireType.Overline, color = LiveWireColors.OnSurfaceMuted)
                            if (!final && source != GameSource.Finding || others.isNotEmpty()) {
                                Text(others.size.toString(), style = LiveWireType.Mono.copy(fontSize = 8.sp), color = LiveWireColors.OnSurfaceFaint)
                            }
                        }
                        if (others.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    when {
                                        final -> "Nothing to watch once a game is over."
                                        source == GameSource.Finding -> "Still matching channels…"
                                        best != null -> "No other channel carries this game."
                                        else -> "No channel matched this game."
                                    },
                                    style = LiveWireType.Sub,
                                    color = LiveWireColors.OnSurfaceFaint,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            LazyColumn(
                                Modifier.weight(1f).fillMaxWidth(),
                                state = listState,
                                // Room for the focused row's ring at the top and bottom.
                                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 3.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                itemsIndexed(others, key = { _, o -> o.channel.streamId }) { i, o ->
                                    ChannelRow(o, onClick = { onPick(o) }, modifier = if (i == 0) Modifier.focusRequester(firstChannel) else Modifier)
                                }
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.CenterEnd) {
                    when {
                        none -> KeyHints("OK" to "Search", "BACK" to "Close")
                        others.isNotEmpty() -> KeyHints("OK" to "Watch", "▶" to "Other channels", "BACK" to "Close")
                        best != null -> KeyHints("OK" to "Watch", "BACK" to "Close")
                        else -> KeyHints("BACK" to "Close")
                    }
                }
            }
        }
    }
}

/** The two teams with their logos, the score (or start time) and the status between them. */
@Composable
private fun Board(game: SportsGame) {
    val live = game.status.state == GameState.IN_PROGRESS
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Side(game.away)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (SportsFormat.showScores(game)) {
                val a = game.away.score
                val h = game.home.score
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Score(a, lead = a != null && h != null && a >= h)
                    Text("–", style = LiveWireType.Heading.copy(fontSize = 18.sp), color = LiveWireColors.OnSurfaceFaint)
                    Score(h, lead = a != null && h != null && h >= a)
                }
            } else {
                Text(
                    SportsList.startLabel(game.startTimeMs, System.currentTimeMillis()),
                    style = LiveWireType.Heading.copy(fontSize = 22.sp, lineHeight = 26.sp),
                    color = LiveWireColors.OnSurface,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (live) Box(Modifier.size(5.dp).clip(CircleShape).background(LiveWireColors.Live))
                Text(
                    if (game.status.state == GameState.PRE) "COMING UP" else SportsFormat.statusLine(game),
                    style = LiveWireType.Mono.copy(fontSize = 9.sp),
                    color = if (live) LiveWireColors.Live else LiveWireColors.OnSurfaceMuted,
                    maxLines = 1,
                )
            }
        }
        Side(game.home)
    }
}

@Composable
private fun Side(team: TeamSide) {
    Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(LiveWireColors.SurfaceRaised), contentAlignment = Alignment.Center) {
            if (team.logoUrl != null) {
                AsyncImage(model = team.logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(30.dp))
            } else {
                Text(team.abbreviation.take(3), style = LiveWireType.Tag.copy(fontSize = 9.sp), color = LiveWireColors.OnSurface)
            }
        }
        Text(team.abbreviation, style = LiveWireType.Mono.copy(fontSize = 10.sp), color = LiveWireColors.OnSurface, maxLines = 1)
        Text(
            team.shortName.ifBlank { team.name },
            style = LiveWireType.Sub.copy(fontSize = 9.5.sp),
            color = LiveWireColors.OnSurfaceSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Score(score: Int?, lead: Boolean) {
    Text(
        SportsFormat.scoreLabel(score),
        style = LiveWireType.Display.copy(fontSize = 34.sp, lineHeight = 36.sp),
        color = if (lead) LiveWireColors.OnSurface else LiveWireColors.OnSurfaceSoft,
    )
}

@Composable
private fun Message(text: String) {
    Text(text, style = LiveWireType.Body, color = LiveWireColors.OnSurfaceSoft, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}

/** Watch, Watch from start and Search: a full-width button with a title and a second line. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BigButton(icon: Int, title: String, sub: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LiveWireSurface(
        onClick = onClick,
        restingColor = LiveWireColors.SurfaceRaised,
        shape = RoundedCornerShape(9.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(painterResource(icon), contentDescription = null, tint = LiveWireTheme.onSurface, modifier = Modifier.size(10.dp))
                Text(
                    title,
                    style = LiveWireType.Title.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
                    color = LiveWireTheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                sub,
                style = LiveWireType.Sub.copy(fontSize = 9.sp, lineHeight = 12.sp),
                color = LiveWireTheme.onSurfaceMuted,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A channel on the page: its logo, name and why it is listed. A guide match reads NOW (red) while
 * its listing airs, then the listing ("NFL Football: Bears at Seahawks · 8:15 PM–11:30 PM").
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelRow(option: SourceOption, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LiveWireSurface(onClick = onClick, restingColor = Color.Transparent, restingBorder = false, modifier = modifier.fillMaxWidth()) {
        val onFocus = LocalOnFocus.current
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChannelLogo(option.channel, Modifier.width(38.dp).height(26.dp), textSize = 7)
            Column(Modifier.weight(1f)) {
                Text(
                    if (option.liveNow) "Live Now · ${option.channel.name}" else option.channel.name,
                    style = LiveWireType.Title.copy(fontSize = 11.sp),
                    color = LiveWireTheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val now = option.listing?.let { System.currentTimeMillis() in it.startMs until it.stopMs } == true
                Text(
                    buildAnnotatedString {
                        if (now) {
                            withStyle(SpanStyle(color = if (onFocus) Color(0xFFD42A20) else LiveWireColors.Live, fontFamily = LiveWireType.Tag.fontFamily, fontSize = 7.sp, letterSpacing = LiveWireType.Tag.letterSpacing)) {
                                append("NOW  ")
                            }
                        }
                        append(option.reason)
                    },
                    style = LiveWireType.Sub.copy(fontSize = 9.sp, lineHeight = 12.sp),
                    color = if (option.listing != null) LiveWireTheme.onSurfaceSoft else LiveWireTheme.onSurfaceMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(if (onFocus) LiveWireColors.OnFocus else LiveWireColors.SurfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_fav_play),
                    contentDescription = null,
                    tint = if (onFocus) LiveWireColors.Focus else LiveWireColors.OnSurfaceSoft,
                    modifier = Modifier.size(8.dp),
                )
            }
        }
    }
}

