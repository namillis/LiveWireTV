package com.livewire.tv.feature.settings.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class StreamFormat(val ext: String, val label: String) {
    TS("ts", "MPEG-TS"),
    HLS("m3u8", "HLS (m3u8)");
    companion object { fun fromName(n: String?) = if (n == HLS.name) HLS else TS }
}

data class AppSettings(
    val streamFormat: StreamFormat = StreamFormat.TS,
    val guideWindowHours: Int = 4,
    val showNowPlayingOnCards: Boolean = true,
)

private val Context.dataStore by preferencesDataStore(name = "livewire_settings")

/** On-device settings (non-secret prefs). Kotlin/DataStore port of the Flutter SettingsService. */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val FORMAT = stringPreferencesKey("streamFormat")
        val GUIDE_HOURS = intPreferencesKey("guideWindowHours")
        val NOW_PLAYING = booleanPreferencesKey("showNowPlaying")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            streamFormat = StreamFormat.fromName(p[Keys.FORMAT]),
            guideWindowHours = p[Keys.GUIDE_HOURS] ?: 4,
            showNowPlayingOnCards = p[Keys.NOW_PLAYING] ?: true,
        )
    }

    suspend fun setStreamFormat(f: StreamFormat) {
        context.dataStore.edit { it[Keys.FORMAT] = f.name }
    }

    suspend fun setGuideWindowHours(h: Int) {
        context.dataStore.edit { it[Keys.GUIDE_HOURS] = h }
    }

    suspend fun setShowNowPlaying(v: Boolean) {
        context.dataStore.edit { it[Keys.NOW_PLAYING] = v }
    }
}
