package com.livewire.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// LiveWire brand teal, matching the Flutter prototype's seed (0xFF00E0D1).
private val Teal = Color(0xFF00E0D1)
private val TealDark = Color(0xFF00A79B)

private val LiveWireColors = darkColorScheme(
    primary = Teal,
    secondary = TealDark,
)

/** App theme. Dark, TV-first. Uses Compose-for-TV MaterialTheme. */
@Composable
fun LiveWireTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LiveWireColors,
        content = content,
    )
}
