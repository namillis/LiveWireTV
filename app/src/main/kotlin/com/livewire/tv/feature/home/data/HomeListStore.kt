package com.livewire.tv.feature.home.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.homeListDataStore by preferencesDataStore(name = "livewire_home_list")

/** The list last picked in Home's filter dropdown, per provider. */
@Singleton
class HomeListStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun selected(providerId: String): String? =
        context.homeListDataStore.data.map { it[key(providerId)] }.first()

    suspend fun select(providerId: String, listId: String) {
        context.homeListDataStore.edit { it[key(providerId)] = listId }
    }

    /** Forget [providerId]'s choice. Called when the provider is removed. */
    suspend fun removeProvider(providerId: String) {
        context.homeListDataStore.edit { it.remove(key(providerId)) }
    }

    private fun key(providerId: String) = stringPreferencesKey("list:$providerId")
}
