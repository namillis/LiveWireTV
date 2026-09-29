package com.livewire.tv.navigation

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import com.livewire.tv.R
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens

/** The five top-level sections reachable from the drawer (design system section 8). */
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
         * The section whose rail icon lights up for [route]. Top-level routes map to
         * themselves; sub-screens reached from a section (Providers, opened from Settings)
         * borrow that section so the rail shows and the parent stays highlighted, even
         * though they are not drawer-selectable destinations.
         */
        fun railSectionOf(route: String?): TopLevel? = of(route) ?: when (route) {
            Routes.PROVIDERS -> SETTINGS
            else -> null
        }
    }
}

/**
 * Left navigation drawer around the whole app.
 *
 * - On a top-level section the collapsed icon rail is shown; D-pad Left from the first
 *   column moves focus into it, which opens it.
 * - Back from a section's content opens the drawer on the current section. Back from the
 *   open drawer goes to Home, and on Home it falls through so the app exits.
 * - Elsewhere (onboarding, player, providers) the drawer has no items and takes no space,
 *   so the NavHost stays in the same composition slot and keeps its state.
 */
@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun LiveWireNavShell(
    currentRoute: String?,
    onSelect: (TopLevel) -> Unit,
    content: @Composable () -> Unit,
) {
    val current = TopLevel.railSectionOf(currentRoute)
    // A true drawer section (Home/Guide/…): its content's Back opens the drawer. A
    // sub-screen like Providers borrows the rail section for display but is NOT a section,
    // so its Back must fall through to the NavHost (pop back to Settings), not open the drawer.
    val isTopLevelSection = TopLevel.of(currentRoute) != null
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val requesters = remember { TopLevel.entries.associateWith { FocusRequester() } }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    // The drawer is open exactly when it holds focus. NavigationDrawer only tracks this for
    // D-pad entry, so programmatic focus (Back from content) is mirrored here too.
    var drawerFocused by remember { mutableStateOf(false) }
    val open = drawerFocused
    var focusedItem by remember { mutableStateOf<TopLevel?>(null) }
    var handoff by remember { mutableStateOf<Job?>(null) }

    NavigationDrawer(
        drawerState = drawerState,
        drawerContent = { value ->
            if (current == null) return@NavigationDrawer
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(if (value == DrawerValue.Open) LiveWireColors.Surface else LiveWireColors.Canvas)
                    // Keep drawer content inside the overscan-safe area (section 4): open, the
                    // focus ring's edge sits on the 48dp line; collapsed, the icons do (items
                    // inset their icon by 16dp), so the rail stays narrow.
                    .padding(
                        start = if (value == DrawerValue.Open) LiveWireDimens.SafeHorizontal
                        else LiveWireDimens.SafeHorizontal - 16.dp,
                        end = 12.dp,
                        top = LiveWireDimens.SafeVertical,
                        bottom = LiveWireDimens.SafeVertical,
                    )
                    .onFocusChanged { f ->
                        drawerFocused = f.hasFocus
                        if (!f.hasFocus) focusedItem = null
                        drawerState.setValue(if (f.hasFocus) DrawerValue.Open else DrawerValue.Closed)
                    }
                    .selectableGroup()
                    // Entering the drawer by D-pad lands on the CURRENT section, not on whichever
                    // item is geometrically level with the focused card (Left from Home's rail
                    // otherwise lands on Search, and OK then switches section by surprise).
                    .focusProperties { enter = { requesters.getValue(current ?: TopLevel.HOME) } }
                    .focusGroup(),
                horizontalAlignment = Alignment.Start,
            ) {
                BrandMark(expanded = value == DrawerValue.Open)
                Spacer(Modifier.height(LiveWireDimens.SpaceXl))
                TopLevel.entries.forEach { item ->
                    val selected = item == current
                    NavigationDrawerItem(
                        selected = selected,
                        onClick = {
                            // Navigate when picking a different section, OR when this section is
                            // only highlighted because we are on one of its sub-screens (e.g.
                            // Providers highlights Settings): choosing it should return there.
                            if (!selected || !isTopLevelSection) onSelect(item)
                            handoff?.cancel()
                            handoff = scope.launch {
                                // Wait for the new section to replace the old one; handing focus
                                // over earlier lands on the outgoing screen and is then lost.
                                delay(300)
                                // A section may still be loading with nothing focusable. Stay on
                                // the chosen item and retry until it has content, unless the user
                                // moves somewhere else in the meantime.
                                repeat(120) {
                                    if (focusedItem != item) return@launch
                                    if (focusManager.moveFocus(FocusDirection.Right)) return@launch
                                    delay(500)
                                }
                            }
                        },
                        leadingContent = {
                            Icon(
                                painter = painterResource(item.icon),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        modifier = Modifier
                            .padding(vertical = 3.dp)
                            .focusRequester(requesters.getValue(item))
                            .onFocusChanged { if (it.isFocused) focusedItem = item },
                        colors = drawerItemColors(),
                        border = drawerItemBorder(),
                        shape = NavigationDrawerItemDefaults.shape(RoundedCornerShape(LiveWireDimens.RadiusCard)),
                        scale = NavigationDrawerItemDefaults.scale(focusedScale = LiveWireDimens.FocusScaleWide),
                    ) {
                        Text(item.label, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            content()
            // Content behind the open drawer is dimmed, never blurred (section 8).
            if (open) Box(Modifier.fillMaxSize().background(LiveWireColors.Scrim))

            // Registered AFTER content so they take priority over the NavHost's own Back
            // handling (the most recently composed enabled callback wins).
            // Back from a section's content → into the drawer on that section. A sub-screen
            // (Providers) is not a section: its Back is left to the NavHost, which pops back
            // to Settings — the mockup's "Back Settings" hint.
            BackHandler(enabled = isTopLevelSection && current != null && !open) {
                runCatching { requesters.getValue(current!!).requestFocus() }
            }
            // Back from the open drawer → Home, where Home focuses its last card. On Home this
            // is disabled and so is the NavHost's handler (nothing to pop), so the app exits.
            BackHandler(enabled = open && current != null && current != TopLevel.HOME) {
                onSelect(TopLevel.HOME)
            }
        }
    }
}

@Composable
private fun BrandMark(expanded: Boolean) {
    Box(
        modifier = Modifier.padding(start = 16.dp).height(24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(LiveWireColors.Accent, RoundedCornerShape(50)))
            if (expanded) {
                Spacer(Modifier.size(LiveWireDimens.SpaceS))
                Text("LiveWire", style = MaterialTheme.typography.titleMedium, color = LiveWireColors.OnSurface)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun drawerItemColors() = NavigationDrawerItemDefaults.colors(
    containerColor = androidx.compose.ui.graphics.Color.Transparent,
    contentColor = LiveWireColors.OnSurface,
    inactiveContentColor = LiveWireColors.OnSurfaceMuted,
    focusedContainerColor = LiveWireColors.SurfaceFocused,
    focusedContentColor = LiveWireColors.OnSurface,
    pressedContainerColor = LiveWireColors.SurfaceFocused,
    pressedContentColor = LiveWireColors.OnSurface,
    // Current section: amber content, never a fill (section 8).
    selectedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    selectedContentColor = LiveWireColors.Accent,
    focusedSelectedContainerColor = LiveWireColors.SurfaceFocused,
    focusedSelectedContentColor = LiveWireColors.OnSurface,
    pressedSelectedContainerColor = LiveWireColors.SurfaceFocused,
    pressedSelectedContentColor = LiveWireColors.OnSurface,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun drawerItemBorder(): androidx.tv.material3.NavigationDrawerItemBorder {
    val shape = RoundedCornerShape(LiveWireDimens.RadiusCard)
    val ring = Border(BorderStroke(LiveWireDimens.FocusBorder, LiveWireColors.Accent), shape = shape)
    return NavigationDrawerItemDefaults.border(
        focusedBorder = ring,
        pressedBorder = ring,
        focusedSelectedBorder = ring,
        pressedSelectedBorder = ring,
    )
}
