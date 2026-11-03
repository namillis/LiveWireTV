package com.livewire.tv.feature.search.data

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A query another screen hands to Search before switching to it (Sports' "Search my channels").
 * Search takes it when it next shows, so the query runs once and is not replayed on later visits.
 */
@Singleton
class PendingSearch @Inject constructor() {
    private val query = AtomicReference<String?>(null)

    fun set(q: String) = query.set(q.trim().ifEmpty { null })

    /** The waiting query, cleared as it is read; null when there is none. */
    fun take(): String? = query.getAndSet(null)
}
