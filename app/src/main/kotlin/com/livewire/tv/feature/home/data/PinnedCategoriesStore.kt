package com.livewire.tv.feature.home.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pinsDataStore by preferencesDataStore(name = "livewire_home_pins")

/**
 * The categories pinned to Home, saved per provider as an ordered list of category ids. Pinning
 * and unpinning happen from the Guide's category column; Home shows the pins as its category
 * rails. List rules live in [HomeRails].
 */
@Singleton
class PinnedCategoriesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(String.serializer())

    fun pinned(providerId: String): Flow<List<String>> =
        context.pinsDataStore.data.map { p -> p[key(providerId)]?.let(::decode).orEmpty() }.distinctUntilChanged()

    /** Pin or unpin [categoryId] for [providerId]; see [HomeRails.togglePin]. */
    suspend fun toggle(providerId: String, categoryId: String): HomeRails.PinResult {
        var result = HomeRails.PinResult(emptyList(), isPinned = false)
        context.pinsDataStore.edit { p ->
            val current = p[key(providerId)]?.let(::decode).orEmpty()
            result = HomeRails.togglePin(current, categoryId)
            if (result.pinned != current) p[key(providerId)] = json.encodeToString(serializer, result.pinned)
        }
        return result
    }

    /** Forget [providerId]'s pins. Called when the provider is removed. */
    suspend fun removeProvider(providerId: String) {
        context.pinsDataStore.edit { it.remove(key(providerId)) }
    }

    private fun key(providerId: String) = stringPreferencesKey("pins:$providerId")

    private fun decode(raw: String): List<String> =
        runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
}
