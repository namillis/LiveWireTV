package com.livewire.tv.feature.settings.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
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
    /** Play the focused channel, muted, behind the Guide's details band. */
    val guidePreview: Boolean = true,
    /** How captions look and where they sit (Settings › Subtitles). */
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    // --- Self-update state ---
    /** Epoch ms of the last update check (0 = never). Gates the 24h auto-check. */
    val lastUpdateCheck: Long = 0L,
    /** A version the user chose to skip, so it isn't surfaced again (null = none). */
    val skippedVersion: String? = null,
    /** Whether to check for updates automatically on launch (default on). */
    val autoCheckUpdates: Boolean = true,
    /** Set to the target version just before an install commits; read once on next launch
     *  to show "Updated to X", then cleared (null = nothing pending). */
    val pendingUpdatedVersion: String? = null,
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
        val GUIDE_PREVIEW = booleanPreferencesKey("guidePreview")
        val LAST_UPDATE_CHECK = androidx.datastore.preferences.core.longPreferencesKey("lastUpdateCheck")
        val SKIPPED_VERSION = stringPreferencesKey("skippedVersion")
        val AUTO_CHECK_UPDATES = booleanPreferencesKey("autoCheckUpdates")
        val PENDING_UPDATED_VERSION = stringPreferencesKey("pendingUpdatedVersion")
        val CC_SIZE = stringPreferencesKey("captionSize")
        val CC_COLOR = stringPreferencesKey("captionColor")
        val CC_BACKGROUND = stringPreferencesKey("captionBackground")
        val CC_EDGE = stringPreferencesKey("captionEdge")
        val CC_FONT = stringPreferencesKey("captionFont")
        val CC_PLACEMENT = stringPreferencesKey("captionPlacement")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            streamFormat = StreamFormat.fromName(p[Keys.FORMAT]),
            guideWindowHours = p[Keys.GUIDE_HOURS] ?: 4,
            showNowPlayingOnCards = p[Keys.NOW_PLAYING] ?: true,
            guidePreview = p[Keys.GUIDE_PREVIEW] ?: true,
            subtitleStyle = subtitleStyleOf(p),
            lastUpdateCheck = p[Keys.LAST_UPDATE_CHECK] ?: 0L,
            skippedVersion = p[Keys.SKIPPED_VERSION]?.ifBlank { null },
            autoCheckUpdates = p[Keys.AUTO_CHECK_UPDATES] ?: true,
            pendingUpdatedVersion = p[Keys.PENDING_UPDATED_VERSION]?.ifBlank { null },
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

    suspend fun setGuidePreview(v: Boolean) {
        context.dataStore.edit { it[Keys.GUIDE_PREVIEW] = v }
    }

    /**
     * Read-modify-write the caption style in one DataStore transaction, so quick ◀ ▶ presses
     * each step from the value the previous press saved rather than from a stale copy.
     */
    suspend fun updateSubtitleStyle(change: (SubtitleStyle) -> SubtitleStyle) {
        context.dataStore.edit {
            val s = change(subtitleStyleOf(it))
            it[Keys.CC_SIZE] = s.size.name
            it[Keys.CC_COLOR] = s.color.name
            it[Keys.CC_BACKGROUND] = s.background.name
            it[Keys.CC_EDGE] = s.edge.name
            it[Keys.CC_FONT] = s.font.name
            it[Keys.CC_PLACEMENT] = s.placement.name
        }
    }

    private fun subtitleStyleOf(p: Preferences) = SubtitleStyle(
        size = enumOr(p[Keys.CC_SIZE], CaptionSize.MEDIUM),
        color = enumOr(p[Keys.CC_COLOR], CaptionColor.WHITE),
        background = enumOr(p[Keys.CC_BACKGROUND], CaptionBackground.BOX),
        edge = enumOr(p[Keys.CC_EDGE], CaptionEdge.NONE),
        font = enumOr(p[Keys.CC_FONT], CaptionFont.DEFAULT),
        placement = enumOr(p[Keys.CC_PLACEMENT], CaptionPlacement.AS_BROADCAST),
    )

    // --- Self-update state ---

    suspend fun setLastUpdateCheck(epochMs: Long) {
        context.dataStore.edit { it[Keys.LAST_UPDATE_CHECK] = epochMs }
    }

    suspend fun setSkippedVersion(version: String?) {
        context.dataStore.edit {
            if (version.isNullOrBlank()) it.remove(Keys.SKIPPED_VERSION) else it[Keys.SKIPPED_VERSION] = version
        }
    }

    suspend fun setAutoCheckUpdates(v: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_CHECK_UPDATES] = v }
    }

    suspend fun setPendingUpdatedVersion(version: String?) {
        context.dataStore.edit {
            if (version.isNullOrBlank()) it.remove(Keys.PENDING_UPDATED_VERSION) else it[Keys.PENDING_UPDATED_VERSION] = version
        }
    }
}
