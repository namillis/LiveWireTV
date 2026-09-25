package com.livewire.tv.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * tv-material3 buttons and cards react to D-pad/OK clicks only, so on a phone or a
 * touch TV a tap does nothing. Add this to a TV clickable to make taps call [onClick]
 * too. It runs after the component in the pointer pass, so a touch the component
 * already consumed is not handled twice.
 */
fun Modifier.touchClickable(onClick: () -> Unit): Modifier =
    pointerInput(onClick) { detectTapGestures { onClick() } }
