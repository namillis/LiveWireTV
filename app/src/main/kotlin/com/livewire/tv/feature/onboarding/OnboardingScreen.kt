package com.livewire.tv.feature.onboarding

import com.livewire.tv.ui.touchClickable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.livewire.tv.feature.providers.domain.ProviderDraft
import com.livewire.tv.feature.providers.domain.ProviderInputValidator
import com.livewire.tv.feature.providers.domain.ProviderType
import com.livewire.tv.ui.theme.dpadVerticalExit
import com.livewire.tv.ui.theme.liveWireTextFieldColors

/**
 * First-run provider setup, for an Xtream Codes panel or an M3U playlist. The form
 * is scrollable and keyboard-aware so every control stays reachable on small TV
 * screens, and D-pad order is explicit.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onConnected: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    var type by rememberSaveable { mutableStateOf(ProviderType.XTREAM) }
    var name by rememberSaveable { mutableStateOf(ProviderDraft.DEFAULT_NAME) }
    var url by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var epgUrl by rememberSaveable { mutableStateOf("") }
    val isM3u = type == ProviderType.M3U

    val xtreamFocus = remember { FocusRequester() }
    val m3uFocus = remember { FocusRequester() }
    val nameFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val epgFocus = remember { FocusRequester() }
    val connectFocus = remember { FocusRequester() }
    val afterUrl = if (isM3u) epgFocus else userFocus
    val beforeConnect = if (isM3u) epgFocus else passwordFocus

    // Local validation shows once the user has tried to connect, or for URLs as soon
    // as something has been typed.
    var attempted by rememberSaveable { mutableStateOf(false) }
    val urlNoun = if (isM3u) "playlist URL" else "server URL"
    val urlError = ProviderInputValidator.urlProblem(url, urlNoun)
        .takeIf { attempted || url.isNotBlank() }
    val epgError = ProviderInputValidator.urlProblem(epgUrl, "guide URL")
        .takeIf { isM3u && epgUrl.isNotBlank() }
    val userError = "Enter your username.".takeIf { !isM3u && attempted && user.isBlank() }
    val passwordError = "Enter your password.".takeIf { !isM3u && attempted && password.isEmpty() }

    fun draft() = ProviderDraft(
        type = type, name = name, url = url,
        username = user, password = password, epgUrl = epgUrl,
    )

    val connect: () -> Unit = {
        attempted = true
        val problem = ProviderInputValidator.validate(draft())
        if (problem == null) {
            keyboard?.hide()
            viewModel.connect(draft())
        } else {
            when (problem.field) {
                ProviderInputValidator.Field.URL -> urlFocus
                ProviderInputValidator.Field.USERNAME -> userFocus
                ProviderInputValidator.Field.PASSWORD -> passwordFocus
                ProviderInputValidator.Field.EPG_URL -> epgFocus
            }.requestFocus()
        }
    }

    fun selectType(newType: ProviderType) {
        if (type == newType) return
        type = newType
        attempted = false
        viewModel.clearError()
    }

    LaunchedEffect(Unit) {
        // Land on the provider-type switch: it is a real D-pad target, it does not pop the
        // on-screen keyboard, and the user picks Xtream or M3U before typing anything.
        runCatching { xtreamFocus.requestFocus() }
    }
    LaunchedEffect(state.success) {
        if (state.success) onConnected()
    }

    val fieldModifier = Modifier
        .widthIn(max = 480.dp)
        .fillMaxWidth()
        .padding(vertical = 6.dp)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 48.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Welcome to LiveWire", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Connect your IPTV provider to get started.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                ProviderTypeButton(
                    label = "Xtream Codes",
                    selected = !isM3u,
                    onClick = { selectType(ProviderType.XTREAM) },
                    modifier = Modifier
                        .focusRequester(xtreamFocus)
                        .focusProperties { right = m3uFocus; down = nameFocus },
                )
                ProviderTypeButton(
                    label = "M3U playlist",
                    selected = isM3u,
                    onClick = { selectType(ProviderType.M3U) },
                    modifier = Modifier
                        .focusRequester(m3uFocus)
                        .focusProperties { left = xtreamFocus; down = nameFocus },
                )
            }

            val colors = liveWireTextFieldColors()

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { urlFocus.requestFocus() }),
                colors = colors,
                modifier = fieldModifier
                    .focusRequester(nameFocus)
                    .focusProperties { up = if (isM3u) m3uFocus else xtreamFocus; down = urlFocus }
                    .dpadVerticalExit(up = if (isM3u) m3uFocus else xtreamFocus, down = urlFocus),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(if (isM3u) "Playlist URL" else "Server URL") },
                supportingText = {
                    Text(
                        urlError ?: if (isM3u) {
                            "Example: http://provider.example/get.php?type=m3u_plus&…"
                        } else {
                            "Example: http://provider.example:8080"
                        },
                    )
                },
                isError = urlError != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { afterUrl.requestFocus() }),
                colors = colors,
                modifier = fieldModifier
                    .focusRequester(urlFocus)
                    .focusProperties { up = nameFocus; down = afterUrl }
                    .dpadVerticalExit(up = nameFocus, down = afterUrl),
            )

            if (isM3u) {
                OutlinedTextField(
                    value = epgUrl,
                    onValueChange = { epgUrl = it },
                    label = { Text("Guide (XMLTV) URL, optional") },
                    supportingText = {
                        Text(epgError ?: "Leave blank to use the guide listed in the playlist.")
                    },
                    isError = epgError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { connect() }),
                    colors = colors,
                    modifier = fieldModifier
                        .focusRequester(epgFocus)
                        .focusProperties { up = urlFocus; down = connectFocus }
                        .dpadVerticalExit(up = urlFocus, down = connectFocus),
                )
            } else {
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Username") },
                    supportingText = userError?.let { { Text(it) } },
                    isError = userError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                    colors = colors,
                    modifier = fieldModifier
                        .focusRequester(userFocus)
                        .focusProperties { up = urlFocus; down = passwordFocus }
                        .dpadVerticalExit(up = urlFocus, down = passwordFocus),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    supportingText = passwordError?.let { { Text(it) } },
                    isError = passwordError != null,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { connect() }),
                    colors = colors,
                    modifier = fieldModifier
                        .focusRequester(passwordFocus)
                        .focusProperties { up = userFocus; down = connectFocus }
                        .dpadVerticalExit(up = userFocus, down = connectFocus),
                )
            }

            val usesHttp = url.trim().startsWith("http://", ignoreCase = true) ||
                (isM3u && epgUrl.trim().startsWith("http://", ignoreCase = true))
            if (usesHttp) {
                Text(
                    "HTTP is supported, but it is not encrypted. Use it only on a trusted network.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = fieldModifier.padding(top = 8.dp),
                )
            }

            state.error?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = fieldModifier.padding(top = 8.dp),
                )
            }

            // Stays enabled while idle: a disabled button is unfocusable on TV, which
            // would leave D-pad users with no way to discover what is missing.
            Button(
                onClick = connect,
                enabled = !state.busy,
                modifier = Modifier
                    .padding(top = 20.dp, bottom = 64.dp)
                    .focusRequester(connectFocus)
                    .focusProperties { up = beforeConnect }
                    .touchClickable { if (!state.busy) connect() },
            ) {
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text(if (isM3u) "Loading playlist…" else "Connecting…")
                } else {
                    Text("Connect")
                }
            }
        }
    }
}

/**
 * One stable button per type. Swapping composables on selection would replace the
 * focused node and drop D-pad focus, so selection is shown in the label instead.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ProviderTypeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.touchClickable(onClick),
    ) {
        Text(if (selected) "✓ $label" else label)
    }
}
