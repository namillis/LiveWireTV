package com.livewire.tv.feature.onboarding

import androidx.compose.foundation.layout.Column
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
import com.livewire.tv.feature.providers.domain.ProviderInputValidator
import com.livewire.tv.ui.theme.liveWireTextFieldColors

/**
 * First-run Xtream provider setup. The form is scrollable and IME-aware so every
 * control remains reachable on smaller TV screens and while the on-screen keyboard
 * is visible.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onConnected: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    var name by rememberSaveable { mutableStateOf("My Provider") }
    var url by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    val nameFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val connectFocus = remember { FocusRequester() }

    // Local validation shows as soon as the user has attempted to connect, or
    // for the URL once something has been typed into it.
    var attempted by rememberSaveable { mutableStateOf(false) }
    val urlError = ProviderInputValidator.urlProblem(url)
        .takeIf { attempted || url.isNotBlank() }
    val userError = "Enter your username.".takeIf { attempted && user.isBlank() }
    val passwordError = "Enter your password.".takeIf { attempted && password.isEmpty() }

    val connect: () -> Unit = {
        attempted = true
        val problem = ProviderInputValidator.validate(url, user, password)
        if (problem == null) {
            keyboard?.hide()
            viewModel.connect(name, url, user, password)
        } else {
            when (problem.field) {
                ProviderInputValidator.Field.URL -> urlFocus
                ProviderInputValidator.Field.USERNAME -> userFocus
                ProviderInputValidator.Field.PASSWORD -> passwordFocus
            }.requestFocus()
        }
    }

    LaunchedEffect(Unit) {
        // TV has no pointer: always land on a real control so D-pad input has a target.
        runCatching { nameFocus.requestFocus() }
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
                "Connect your IPTV provider (Xtream Codes) to get started.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )

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
                    .focusProperties { down = urlFocus },
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Server URL") },
                supportingText = {
                    Text(urlError ?: "Example: http://provider.example:8080")
                },
                isError = urlError != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { userFocus.requestFocus() }),
                colors = colors,
                modifier = fieldModifier
                    .focusRequester(urlFocus)
                    .focusProperties { up = nameFocus; down = userFocus },
            )
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
                    .focusProperties { up = urlFocus; down = passwordFocus },
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
                    .focusProperties { up = userFocus; down = connectFocus },
            )

            if (url.trim().startsWith("http://", ignoreCase = true)) {
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
                    .focusProperties { up = passwordFocus },
            ) {
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text("Connecting…")
                } else {
                    Text("Connect")
                }
            }
        }
    }
}
