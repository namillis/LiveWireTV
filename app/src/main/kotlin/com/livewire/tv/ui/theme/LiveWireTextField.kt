package com.livewire.tv.ui.theme

import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
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
