package com.livewire.tv.ui.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme
import com.livewire.tv.ui.theme.dpadVerticalExit

/**
 * Shared provider add/edit form primitives, used by both the Providers screen
 * (`ProvidersScreen.ProviderFormPage`) and first-run onboarding, so the two never
 * drift (plan section 1, step 2).
 *
 * Every input is a [ProviderFilledField] built on [BasicTextField] — there is no
 * `OutlinedTextField` anywhere in the form, which is what lets the app drop its last
 * Material outlined field (plan section 1, step 7). The field renders the design
 * system's filled box: [LiveWireColors.SurfaceRaised] at rest, [LiveWireColors.SurfaceFocused]
 * with a 2dp amber ring when focused, a label above and an optional help/error line below.
 *
 * D-pad: each field takes explicit `up`/`down` [FocusRequester]s and installs
 * [dpadVerticalExit] so a single-line field never traps a remote user.
 */

/** How a field's label is drawn, so the two callers keep their existing look. */
enum class ProviderFieldLabelStyle {
    /** Uppercase mono overline (Providers screen edit form). */
    OVERLINE,

    /** Sentence-case Inter label (onboarding, matching the Option 1 mockup). */
    SENTENCE,
}

private val FieldMinHeight = 31.dp
private val FieldRadius = LiveWireDimens.RadiusCell

/**
 * One filled text field.
 *
 * @param help a persistent help line under the box (shown when [error] is null).
 * @param error an error line under the box; when non-null it replaces [help] and is
 *   drawn in [LiveWireColors.OnSurface] with a red glyph (design system §3.3: errors
 *   are onSurface text with an icon, never a red fill).
 * @param trailing optional trailing content inside the box (e.g. a Show/Hide control).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProviderFilledField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    focusRequester: FocusRequester,
    upFocus: FocusRequester,
    downFocus: FocusRequester,
    modifier: Modifier = Modifier,
    leftFocus: FocusRequester? = null,
    rightFocus: FocusRequester? = null,
    optional: Boolean = false,
    password: Boolean = false,
    passwordVisible: Boolean = false,
    labelStyle: ProviderFieldLabelStyle = ProviderFieldLabelStyle.SENTENCE,
    help: String? = null,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    // TV remote model: moving onto a field only focuses it; OK opens the keyboard. Without
    // this the keyboard pops on every focus change and then swallows the D-pad, so Up/Down
    // walk the keys instead of the form.
    var editing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    @OptIn(ExperimentalLayoutApi::class)
    val imeVisible = WindowInsets.isImeVisible
    // readOnly until OK: a read-only field never starts an input session, so focusing it
    // cannot pop the keyboard. OK flips it editable and asks for the keyboard; closing the
    // keyboard (Back, or its Next/Done key) drops back to the D-pad focus model.
    LaunchedEffect(editing) { if (editing) keyboard?.show() }
    LaunchedEffect(imeVisible) { if (!imeVisible) editing = false }
    val visualTransformation =
        if (password && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None

    Column(modifier = modifier.padding(bottom = LiveWireDimens.SpaceM)) {
        FieldLabel(label, optional, focused, labelStyle)
        Spacer(Modifier.height(LiveWireDimens.SpaceXs))

        val shape = RoundedCornerShape(FieldRadius)
        val boxColor = if (focused) LiveWireColors.SurfaceFocused else LiveWireColors.SurfaceRaised
        val borderColor = if (focused) LiveWireColors.Accent else LiveWireColors.Border
        val borderWidth = if (focused) LiveWireDimens.FocusBorder else LiveWireDimens.RestBorder
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FieldMinHeight)
                .background(boxColor, shape)
                .border(borderWidth, borderColor, shape)
                .padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                readOnly = !editing,
                textStyle = MaterialTheme.typography.titleMedium.copy(color = LiveWireColors.OnSurface),
                cursorBrush = SolidColor(LiveWireColors.OnSurface),
                visualTransformation = visualTransformation,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(onNext = { onImeAction() }, onDone = { onImeAction() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (!it.isFocused) editing = false
                    }
                    .onPreviewKeyEvent { e ->
                        // Not editing: Left/Right go to the field beside this one (two-column
                        // grid). The text field would otherwise eat them as caret moves.
                        if (!editing && e.type == KeyEventType.KeyDown) {
                            val side = when (e.key) {
                                Key.DirectionLeft -> leftFocus
                                Key.DirectionRight -> rightFocus
                                else -> null
                            }
                            if (side != null) return@onPreviewKeyEvent runCatching { side.requestFocus() }.isSuccess
                        }
                        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                        if (ok && !editing && e.type == KeyEventType.KeyDown) {
                            editing = true
                            true
                        } else {
                            ok && !editing // swallow the matching KeyUp too
                        }
                    }
                    // All four directions live on the focusable node itself, not the
                    // wrapping Column — otherwise left/right are silently ignored.
                    .focusProperties {
                        up = upFocus
                        down = downFocus
                        leftFocus?.let { left = it }
                        rightFocus?.let { right = it }
                    }
                    .dpadVerticalExit(up = upFocus, down = downFocus),
            )
            if (trailing != null) {
                Spacer(Modifier.width(LiveWireDimens.SpaceS))
                trailing()
            }
        }

        val supporting = error ?: help
        if (supporting != null) {
            Spacer(Modifier.height(LiveWireDimens.SpaceXs))
            if (error != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Red alert glyph, onSurface text (never a red fill).
                    Text("!", style = MaterialTheme.typography.labelMedium, color = LiveWireColors.Live)
                    Spacer(Modifier.width(LiveWireDimens.SpaceXs))
                    Text(error, style = MaterialTheme.typography.labelMedium, color = LiveWireColors.OnSurface)
                }
            } else {
                Text(
                    supporting,
                    style = MaterialTheme.typography.labelMedium,
                    color = LiveWireColors.OnSurfaceMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FieldLabel(
    label: String,
    optional: Boolean,
    focused: Boolean,
    style: ProviderFieldLabelStyle,
) {
    when (style) {
        ProviderFieldLabelStyle.OVERLINE ->
            Text(
                label.uppercase(),
                style = LiveWireTheme.tokens.overline,
                color = LiveWireColors.OnSurfaceMuted,
            )
        ProviderFieldLabelStyle.SENTENCE ->
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (focused) LiveWireColors.OnSurface else LiveWireColors.OnSurfaceMuted,
                )
                if (optional) {
                    Spacer(Modifier.width(LiveWireDimens.SpaceS))
                    Text(
                        "optional",
                        style = MaterialTheme.typography.labelMedium,
                        color = LiveWireColors.OnSurfaceMuted,
                    )
                }
            }
    }
}

/**
 * A Show/Hide control for a password field, as a focusable row of its own so a remote
 * user can reach it with Up/Down and press OK to toggle (the caret eats Left/Right inside
 * the field). Built on [LiveWireSurface] so it is focusable, shows the amber ring, and
 * handles OK. The caller supplies up/down chaining via [modifier].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PasswordRevealToggle(
    visible: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LiveWireSurface(
        onClick = onToggle,
        restingColor = LiveWireColors.SurfaceRaised,
        shape = RoundedCornerShape(FieldRadius),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = LiveWireDimens.SpaceM, vertical = LiveWireDimens.SpaceS),
        ) {
            Text(
                if (visible) "Hide password" else "Show password",
                style = MaterialTheme.typography.titleMedium,
                color = LiveWireColors.OnSurface,
            )
        }
    }
}

/** Layout constants shared by both callers, tuned to the mockup at density 2.0. */
object ProviderFormLayout {
    /** Max content width for the form column (matches the Providers form / mockup). */
    val MaxWidth = 620.dp

    /** The single equal-width 2-column grid used for the field rows. */
    @Composable
    fun TwoColumnRow(
        left: @Composable () -> Unit,
        right: @Composable () -> Unit,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(LiveWireDimens.SpaceL)) {
            Box(Modifier.weight(1f)) { left() }
            Box(Modifier.weight(1f)) { right() }
        }
    }
}
