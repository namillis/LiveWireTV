package com.livewire.tv.ui.theme

import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.tv.material3.MaterialTheme

/**
 * Mobile Material3 text inputs do not inherit Compose-for-TV color locals. Supply
 * LiveWire's TV theme colors explicitly so entered text, labels, cursor, and borders
 * remain readable on the dark surface.
 */
@Composable
fun liveWireTextFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
    unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/**
 * Moves focus out of a single-line text field on D-pad up/down. Text fields can keep
 * those keys for cursor movement (especially from key sources other than a real
 * D-pad), which would strand a remote user inside the field.
 */
fun Modifier.dpadVerticalExit(
    up: FocusRequester? = null,
    down: FocusRequester? = null,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val target = when (event.key) {
        Key.DirectionUp -> up
        Key.DirectionDown -> down
        else -> null
    } ?: return@onPreviewKeyEvent false
    runCatching { target.requestFocus() }.isSuccess
}
