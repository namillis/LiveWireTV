package com.livewire.tv.feature.providers.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.livewire.tv.feature.providers.domain.ProviderConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists provider configs (URL + credentials) to EncryptedSharedPreferences.
 * Credentials never leave the device. The FIRST provider in the list is "active"
 * (Home/Guide/Sports read providers.first), so ordering is the source of truth.
 * Kotlin port of the Flutter ProviderStorageService.
 */
@Singleton
class ProviderStorage @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): List<ProviderConfig> {
        val raw = prefs.getString(KEY, null)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ProviderConfig.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun save(providers: List<ProviderConfig>) {
        prefs.edit()
            .putString(KEY, json.encodeToString(ListSerializer(ProviderConfig.serializer()), providers))
            .apply()
    }

    /** Upsert by id (new providers append to the end). */
    fun add(cfg: ProviderConfig) {
        val all = load().filterNot { it.id == cfg.id } + cfg
        save(all)
    }

    fun remove(id: String) {
        save(load().filterNot { it.id == id })
    }

    /** Move [id] to the front (front = active). */
    fun setActive(id: String) {
        val reordered = reorderActiveFirst(load(), id)
        if (reordered != null) save(reordered)
    }

    fun hasAny(): Boolean = load().isNotEmpty()

    companion object {
        private const val PREFS_NAME = "livewire_providers_secure"
        private const val KEY = "providers"

        /**
         * Pure helper: a new list with [id] moved to the front, or null when [id] is
         * absent or already first (so callers can skip a write). Unit-tested.
         */
        fun reorderActiveFirst(all: List<ProviderConfig>, id: String): List<ProviderConfig>? {
            val idx = all.indexOfFirst { it.id == id }
            if (idx <= 0) return null
            val copy = all.toMutableList()
            val cfg = copy.removeAt(idx)
            copy.add(0, cfg)
            return copy
        }
    }
}
