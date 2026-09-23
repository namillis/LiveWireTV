package com.livewire.tv.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.livewire.tv.feature.settings.data.StreamFormat

/** On-device settings, wired into the player (format), guide (window), and cards (now-playing). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenProviders: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 24.dp))

            SettingRow("Stream format", s.streamFormat.label) {
                Button(onClick = {
                    viewModel.setStreamFormat(
                        if (s.streamFormat == StreamFormat.TS) StreamFormat.HLS else StreamFormat.TS,
                    )
                }) { Text("Toggle") }
            }

            SettingRow("Guide window", "${s.guideWindowHours} hours") {
                Row {
                    Button(onClick = {
                        viewModel.setGuideWindowHours((s.guideWindowHours - 1).coerceAtLeast(2))
                    }, modifier = Modifier.padding(end = 8.dp)) { Text("−") }
                    Button(onClick = {
                        viewModel.setGuideWindowHours((s.guideWindowHours + 1).coerceAtMost(8))
                    }) { Text("+") }
                }
            }

            SettingRow("Show now-playing on cards", if (s.showNowPlayingOnCards) "On" else "Off") {
                Switch(checked = s.showNowPlayingOnCards, onCheckedChange = { viewModel.setShowNowPlaying(it) })
            }

            SettingRow("Providers", "Add, edit, or switch") {
                Button(onClick = onOpenProviders) { Text("Manage") }
            }

            Text("LiveWire • v1.0.0", style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 24.dp))
        }
    }
}

@Composable
private fun SettingRow(title: String, value: String, control: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.labelSmall)
        }
        control()
    }
}
