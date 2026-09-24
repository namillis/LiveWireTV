package com.livewire.tv.feature.providers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.livewire.tv.feature.onboarding.ProviderTypeButton
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderDraft
import com.livewire.tv.feature.providers.domain.ProviderType
import com.livewire.tv.ui.theme.liveWireTextFieldColors

/** Manage IPTV providers: list, set-active (first = active), delete, add/edit. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProvidersScreen(
    viewModel: ProvidersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Row {
                Text("Providers", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f))
                Button(onClick = { adding = true; editing = null }) { Text("Add provider") }
            }

            if (adding || editing != null) {
                ProviderForm(
                    initial = editing,
                    validating = state.validating,
                    error = state.formError,
                    onSubmit = { draft ->
                        viewModel.addOrUpdate(editing?.id, draft) {
                            adding = false; editing = null
                        }
                    },
                    onCancel = { adding = false; editing = null; viewModel.clearFormError() },
                )
            }

            LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
                items(state.providers) { p ->
                    val isActive = p.id == state.activeId
                    Card(
                        onClick = { if (!isActive) viewModel.setActive(p.id) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    (if (isActive) "✓ " else "") + p.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(providerSummary(p),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Button(onClick = { editing = p; adding = false },
                                modifier = Modifier.padding(end = 8.dp)) { Text("Edit") }
                            Button(onClick = { viewModel.remove(p.id) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

/** Host and type only: full URLs and playlist links can carry credentials. */
private fun providerSummary(p: ProviderConfig): String = when (p.type) {
    ProviderType.XTREAM -> "Xtream  •  ${p.displayHost()}  •  ${p.username}"
    ProviderType.M3U -> "M3U playlist  •  ${p.displayHost()}"
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProviderForm(
    initial: ProviderConfig?,
    validating: Boolean,
    error: String?,
    onSubmit: (ProviderDraft) -> Unit,
    onCancel: () -> Unit,
) {
    val start = initial?.let(ProviderDraft::from)
    var type by remember { mutableStateOf(start?.type ?: ProviderType.XTREAM) }
    var name by remember { mutableStateOf(start?.name ?: ProviderDraft.DEFAULT_NAME) }
    var url by remember { mutableStateOf(start?.url.orEmpty()) }
    var user by remember { mutableStateOf(start?.username.orEmpty()) }
    var pass by remember { mutableStateOf(start?.password.orEmpty()) }
    var epg by remember { mutableStateOf(start?.epgUrl.orEmpty()) }
    val isM3u = type == ProviderType.M3U

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        val fm = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        val colors = liveWireTextFieldColors()

        // Type is fixed once saved: switching an existing provider would silently
        // discard its credentials or playlist.
        if (initial == null) {
            Row(modifier = Modifier.padding(bottom = 8.dp)) {
                ProviderTypeButton(
                    "Xtream Codes", selected = !isM3u,
                    onClick = { type = ProviderType.XTREAM },
                    modifier = Modifier.padding(end = 8.dp),
                )
                ProviderTypeButton("M3U playlist", selected = isM3u, onClick = { type = ProviderType.M3U })
            }
        }

        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, colors = colors, modifier = fm)
        OutlinedTextField(
            url, { url = it },
            label = { Text(if (isM3u) "Playlist URL" else "Server URL (http://host:port)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors = colors, modifier = fm,
        )
        if (isM3u) {
            OutlinedTextField(
                epg, { epg = it },
                label = { Text("Guide (XMLTV) URL, optional") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = colors, modifier = fm,
            )
        } else {
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, colors = colors, modifier = fm)
            OutlinedTextField(
                pass, { pass = it }, label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = colors,
                modifier = fm,
            )
        }
        val usesHttp = url.trim().startsWith("http://", ignoreCase = true) ||
            (isM3u && epg.trim().startsWith("http://", ignoreCase = true))
        if (usesHttp) {
            Text(
                "This provider uses unencrypted HTTP. Use only a trusted network.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = fm,
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Row(modifier = Modifier.padding(top = 12.dp)) {
            Button(
                onClick = {
                    onSubmit(
                        ProviderDraft(
                            type = type, name = name, url = url,
                            username = user, password = pass, epgUrl = epg,
                        ),
                    )
                },
                enabled = !validating,
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Text(if (validating) "Validating…" else if (initial == null) "Add" else "Save")
            }
            Button(onClick = onCancel) { Text("Cancel") }
        }
    }
}
