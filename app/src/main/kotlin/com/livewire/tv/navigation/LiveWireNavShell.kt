package com.livewire.tv.navigation

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.ConfigurationCompat
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.livewire.tv.R
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireFonts
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The five top-level sections in the top bar. */
enum class TopLevel(val route: String, val label: String, @DrawableRes val icon: Int) {
    HOME(Routes.HOME, "Home", R.drawable.ic_nav_home),
    GUIDE(Routes.GUIDE, "Guide", R.drawable.ic_nav_guide),
    SPORTS(Routes.SPORTS, "Sports", R.drawable.ic_nav_sports),
    SEARCH(Routes.SEARCH, "Search", R.drawable.ic_nav_search),
    SETTINGS(Routes.SETTINGS, "Settings", R.drawable.ic_nav_settings),
    ;

    companion object {
        fun of(route: String?): TopLevel? = entries.firstOrNull { it.route == route }

        /**
         * The tab the top bar marks as current for [route], or null when the screen has no bar
         * (onboarding, the player). Sub-screens reached from a section (Providers and
         * Favourites, opened from Settings) keep the bar with their parent marked.
         */
        fun barSectionOf(route: String?): TopLevel? = of(route) ?: when (route) {
            Routes.PROVIDERS -> SETTINGS
            Routes.FAVORITES -> SETTINGS
            else -> null
        }

        /** Where Back goes while the bar has focus: Home, or null on Home so the app exits. */
        fun backFromBar(current: TopLevel): TopLevel? = if (current == HOME) null else HOME
    }
}

/**
 * The top bar above the whole app, replacing the old left rail.
 *
 * - Up from the top row of content lands on the current tab ([focusProperties] enter).
 * - Left and Right move between tabs; only OK switches section, so moving never reloads.
 * - Down returns to the item you left ([focusRestorer] on the content).
 * - Back from a section's content moves focus to the bar. Back from the bar goes to Home,
 *   and on Home it falls through so the app exits.
 * - Sub-screens (Providers, Favourites) keep the bar but their Back pops to Settings.
 * - The player and onboarding have no bar; the NavHost stays in the same composition slot
 *   either way, so it keeps its state.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveWireNavShell(
    currentRoute: String?,
    onSelect: (TopLevel) -> Unit,
    content: @Composable () -> Unit,
) {
    val current = TopLevel.barSectionOf(currentRoute)
    val isTopLevelSection = TopLevel.of(currentRoute) != null
    val requesters = remember { TopLevel.entries.associateWith { FocusRequester() } }
    val contentFocus = remember { FocusRequester() }
    var barFocused by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(LiveWireColors.Canvas)) {
        if (current != null) {
            TopBar(
                current = current,
                requesters = requesters,
                onFocusChanged = { barFocused = it },
                onSelect = { item ->
                    // OK on the current tab of a sub-screen (Settings while on Providers)
                    // returns to that section; on a real section it just goes back down.
                    if (item != current || !isTopLevelSection) {
                        onSelect(item)
                    } else {
                        runCatching { contentFocus.requestFocus() }
                    }
                },
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .focusRequester(contentFocus)
                .focusRestorer(),
        ) {
            content()
        }
    }

    // Registered after content so they win over the NavHost's own Back handling.
    BackHandler(enabled = isTopLevelSection && current != null && !barFocused) {
        runCatching { requesters.getValue(current!!).requestFocus() }
    }
    BackHandler(enabled = barFocused && current != null && TopLevel.backFromBar(current) != null) {
        onSelect(TopLevel.backFromBar(current!!)!!)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TopBar(
    current: TopLevel,
    requesters: Map<TopLevel, FocusRequester>,
    onFocusChanged: (Boolean) -> Unit,
    onSelect: (TopLevel) -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(LiveWireDimens.TopBarHeight)
                .padding(horizontal = LiveWireDimens.SafeHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lockup()
            Spacer(Modifier.width(45.dp))
            Row(
                Modifier
                    .onFocusChanged { onFocusChanged(it.hasFocus) }
                    .focusProperties { enter = { requesters.getValue(current) } }
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TopLevel.entries.forEach { item ->
                    Tab(
                        item = item,
                        selected = item == current,
                        onClick = { onSelect(item) },
                        modifier = Modifier.focusRequester(requesters.getValue(item)),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Clock()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(LiveWireColors.Border))
    }
}

/** "LiveWire / LIVE TV" with the mark. */
@Composable
fun Lockup(modifier: Modifier = Modifier, markSize: Int = 25) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.ic_brand_mark),
            contentDescription = null,
            modifier = Modifier.size(width = markSize.dp, height = (markSize * 0.914f).dp),
        )
        Spacer(Modifier.width((markSize * 0.35f).dp))
        Column {
            Text(
                "LiveWire",
                style = TextStyle(
                    fontFamily = LiveWireFonts.Display, fontWeight = FontWeight.Bold,
                    fontSize = 16.5.sp, lineHeight = 16.5.sp, letterSpacing = (-0.02).em,
                ),
                color = LiveWireColors.OnSurface,
            )
            Text(
                "LIVE TV",
                style = TextStyle(
                    fontFamily = LiveWireFonts.Mono, fontWeight = FontWeight.Medium,
                    fontSize = 7.sp, lineHeight = 9.sp, letterSpacing = 0.32.em,
                ),
                color = LiveWireColors.OnSurfaceMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * A pill tab. Current = raised fill with a red on-air dot after the label; focused = the
 * light focus fill (the current tab keeps its dot, dark on the fill).
 */
@Composable
private fun Tab(item: TopLevel, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LiveWireSurface(
        onClick = onClick,
        modifier = modifier.height(LiveWireDimens.TabHeight),
        shape = RoundedCornerShape(50),
        restingColor = if (selected) LiveWireColors.SurfaceRaised else androidx.compose.ui.graphics.Color.Transparent,
        restingBorder = false,
    ) {
        val color = if (selected) LiveWireTheme.onSurface else LiveWireTheme.onSurfaceMuted
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            NavIcon(item, color)
            Text(
                item.label,
                style = TextStyle(fontFamily = LiveWireFonts.Text, fontWeight = FontWeight.SemiBold, fontSize = 11.25.sp),
                color = color,
            )
            if (selected) Box(Modifier.size(5.dp).background(LiveWireColors.Live, CircleShape))
        }
    }
}

/** The tab icon. The Guide mark's red now-line is a second, untinted layer. */
@Composable
private fun NavIcon(item: TopLevel, tint: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(15.dp)) {
        Icon(painterResource(item.icon), contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize())
        if (item == TopLevel.GUIDE) {
            Image(painterResource(R.drawable.ic_nav_guide_now), contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}

/** "9:24 PM" over "SUN 19 OCT", updated on the minute. */
@Composable
private fun Clock() {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000L - value % 60_000L)
        }
    }
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    val clockFormat = remember(locale) { DateTimeFormatter.ofPattern("h:mm a", locale) }
    val dateFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE d MMM", locale) }
    val t = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
    Column(horizontalAlignment = Alignment.End) {
        Text(
            clockFormat.format(t),
            style = TextStyle(
                fontFamily = LiveWireFonts.Mono, fontWeight = FontWeight.SemiBold,
                fontSize = 13.5.sp, fontFeatureSettings = "tnum",
            ),
            color = LiveWireColors.OnSurface,
        )
        Text(
            dateFormat.format(t).uppercase(locale),
            style = TextStyle(
                fontFamily = LiveWireFonts.Mono, fontWeight = FontWeight.Medium,
                fontSize = 8.sp, letterSpacing = 0.14.em, fontFeatureSettings = "tnum",
            ),
            color = LiveWireColors.OnSurfaceMuted,
        )
    }
}
