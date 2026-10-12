package com.livewire.tv.feature.providers.data

import com.livewire.tv.feature.providers.domain.Failover
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderFailover
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The provider every screen should use right now: the active (first) saved provider, or the one
 * marked as backup while the active one isn't answering. See [ProviderFailover].
 */
@Singleton
class ActiveProvider @Inject constructor(
    private val storage: ProviderStorage,
    xtream: XtreamClient,
) {
    private val failover = ProviderFailover(check = { xtream.reachable(it) })

    suspend fun current(): ProviderConfig? = failover.choose(storage.load(), storage.backupId())

    /** Set while the screens are on the backup because the main provider is down. */
    val activeFailover: Failover? get() = failover.failover

    /** A screen couldn't load from the provider it was given: check the main one again next time. */
    fun recheck() = failover.recheck()
}
