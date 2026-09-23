package com.livewire.tv.feature.providers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.livewire.tv.feature.providers.domain.ProviderConfig

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
                    onSubmit = { name, url, user, pass ->
                        viewModel.addOrUpdate(editing?.id, name, url, user, pass) {
                            adding = false; editing = null
                        }
                    },
                    onCancel = { adding = false; editing = null },
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
                                Text("${p.baseUrl}  •  ${p.username}",
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProviderForm(
    initial: ProviderConfig?,
    validating: Boolean,
    error: String?,
    onSubmit: (name: String, url: String, user: String, pass: String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "My Provider") }
    var url by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var user by remember { mutableStateOf(initial?.username ?: "") }
    var pass by remember { mutableStateOf(initial?.password ?: "") }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        val fm = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = fm)
        OutlinedTextField(url, { url = it }, label = { Text("Server URL (http://host:port)") }, modifier = fm)
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, modifier = fm)
        OutlinedTextField(
            pass, { pass = it }, label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(), modifier = fm,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Row(modifier = Modifier.padding(top = 12.dp)) {
            Button(onClick = { onSubmit(name, url, user, pass) }, modifier = Modifier.padding(end = 8.dp)) {
                Text(if (validating) "Validating…" else if (initial == null) "Add" else "Save")
            }
            Button(onClick = onCancel) { Text("Cancel") }
        }
    }
}
