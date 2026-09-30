package com.livewire.tv.feature.epg

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.livewire.tv.R
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme
import com.livewire.tv.ui.theme.dpadVerticalExit
import java.text.NumberFormat

private val COLLAPSED_WIDTH = 36.dp
private val EXPANDED_WIDTH = 200.dp
private val ITEM_HEIGHT = 30.dp

/**
 * The Guide's category column (mockup option 3). Collapsed it is a narrow strip showing a
 * search icon and the current filter; it opens while it holds focus, the way the nav drawer
 * does. Inside: a keyword field that filters channel names as you type, then "All channels"
 * and the provider's categories. D-pad: Left from the grid enters here on the selected
 * category, Left again reaches the nav drawer, Right returns to the grid via [onExitRight].
 *
 * The list is always composed (hidden while collapsed) so gaining focus never removes the
 * focused node, which would drop focus and collapse the column again.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun GuideCategoryColumn(
    categories: List<GuideCategory>,
    totalChannels: Int,
    selectedId: String?,
    query: String,
    onSelect: (String?) -> Unit,
    onQueryChange: (String) -> Unit,
    onExitRight: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) EXPANDED_WIDTH else COLLAPSED_WIDTH, label = "categoryWidth")
    val shape = RoundedCornerShape(LiveWireDimens.RadiusCard)
    val fieldRequester = remember { FocusRequester() }
    // One requester per entry: null id = "All channels".
    val entries: List<GuideCategory?> = remember(categories) { listOf(null) + categories }
    val requesters = remember(entries) { entries.associate { it?.id to FocusRequester() } }
    val selectedIndex = entries.indexOfFirst { it?.id == selectedId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    // Entering always lands on the selected category, which must be composed to take focus.
    LaunchedEffect(expanded) {
        if (!expanded) listState.scrollToItem((selectedIndex - 2).coerceAtLeast(0))
    }

    Box(
        modifier
            .width(width)
            .fillMaxHeight()
            .clip(shape)
            .background(LiveWireColors.Surface)
            .border(LiveWireDimens.RestBorder, LiveWireColors.Border, shape)
            .onFocusChanged { expanded = it.hasFocus }
            .focusProperties {
                enter = { requesters[selectedId] ?: requesters.getValue(null) }
            }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                // Right from a category (not the text field, where Right moves the caret)
                // goes back to the grid row the user left.
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight && !fieldFocused) {
                    onExitRight()
                } else false
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(LiveWireDimens.SpaceS)
                .alpha(if (expanded) 1f else 0f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            KeywordField(
                value = query,
                onValueChange = onQueryChange,
                focusRequester = fieldRequester,
                downTarget = requesters.getValue(null),
                onFocusChange = { fieldFocused = it },
            )
            Spacer(Modifier.height(LiveWireDimens.SpaceXs))
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(entries, key = { it?.id ?: "\u0000all" }) { entry ->
                    CategoryItem(
                        name = entry?.name ?: "All channels",
                        count = entry?.channelCount ?: totalChannels,
                        selected = entry?.id == selectedId,
                        requester = requesters.getValue(entry?.id),
                        onClick = { onSelect(entry?.id) },
                    )
                }
            }
        }
        if (!expanded) CollapsedLabel(selectedName(categories, selectedId), query)
    }
}

@Composable
private fun CollapsedLabel(categoryName: String, query: String) {
    Column(
        Modifier.fillMaxSize().padding(vertical = LiveWireDimens.SpaceS),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_nav_search),
            contentDescription = null,
            tint = if (query.isNotBlank()) LiveWireColors.Accent else LiveWireColors.OnSurfaceMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.height(LiveWireDimens.SpaceM))
        Text(
            "CATEGORY",
            style = LiveWireTheme.tokens.overline,
            color = LiveWireColors.OnSurfaceMuted,
            maxLines = 1,
            modifier = Modifier.verticalText(),
        )
        Spacer(Modifier.height(LiveWireDimens.SpaceS))
        Text(
            if (query.isBlank()) categoryName else "“${query.trim()}” · $categoryName",
            style = MaterialTheme.typography.titleSmall,
            color = LiveWireColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.verticalText(),
        )
    }
}

@Composable
private fun KeywordField(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    downTarget: FocusRequester?,
    onFocusChange: (Boolean) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(LiveWireDimens.RadiusCell)
    Row(
        Modifier
            .fillMaxWidth()
            .height(ITEM_HEIGHT)
            .clip(shape)
            .background(LiveWireColors.SurfaceRaised)
            .border(
                width = if (focused) LiveWireDimens.FocusBorder else LiveWireDimens.RestBorder,
                color = if (focused) LiveWireColors.Accent else LiveWireColors.Border,
                shape = shape,
            )
            .padding(horizontal = LiveWireDimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_nav_search),
            contentDescription = null,
            tint = if (focused) LiveWireColors.Accent else LiveWireColors.OnSurfaceMuted,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(LiveWireDimens.SpaceXs))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text("Filter", style = MaterialTheme.typography.bodyMedium, color = LiveWireColors.OnSurfaceMuted, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = LiveWireColors.OnSurface),
                cursorBrush = SolidColor(LiveWireColors.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        focused = it.isFocused
                        onFocusChange(it.isFocused)
                    }
                    .dpadVerticalExit(down = downTarget),
            )
        }
    }
}

@Composable
private fun CategoryItem(
    name: String,
    count: Int,
    selected: Boolean,
    requester: FocusRequester,
    onClick: () -> Unit,
) {
    LiveWireSurface(
        onClick = onClick,
        restingColor = if (selected) LiveWireColors.SurfaceRaised else LiveWireColors.Surface,
        shape = RoundedCornerShape(LiveWireDimens.RadiusCell),
        focusedScale = 1f,
        modifier = Modifier.fillMaxWidth().height(ITEM_HEIGHT).focusRequester(requester),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = LiveWireDimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // "You are here" bar on the selected category (amber = selection, per §2).
            Box(
                Modifier
                    .width(2.dp)
                    .height(12.dp)
                    .background(if (selected) LiveWireColors.Accent else LiveWireColors.Surface.copy(alpha = 0f)),
            )
            Spacer(Modifier.width(LiveWireDimens.SpaceXs))
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) LiveWireColors.OnSurface else LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                NumberFormat.getIntegerInstance().format(count),
                style = LiveWireTheme.tokens.tag,
                color = LiveWireColors.OnSurfaceMuted,
                maxLines = 1,
            )
        }
    }
}

private fun selectedName(categories: List<GuideCategory>, id: String?): String =
    categories.firstOrNull { it.id == id }?.name ?: "All channels"

/** Lays text out rotated a quarter-turn anticlockwise, reading bottom to top. */
private fun Modifier.verticalText(): Modifier = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(
            Constraints(maxWidth = constraints.maxHeight.takeIf { it != Constraints.Infinity } ?: Constraints.Infinity),
        )
        layout(placeable.height, placeable.width) {
            placeable.place(
                x = -(placeable.width - placeable.height) / 2,
                y = (placeable.width - placeable.height) / 2,
            )
        }
    }
    .rotate(-90f)
