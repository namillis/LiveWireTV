package com.livewire.tv.feature.update.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A tiny process-wide bridge from [UpdateInstallReceiver] (a system-instantiated
 * BroadcastReceiver, which Hilt can't inject a ViewModel into) to the collecting
 * [com.livewire.tv.feature.update.UpdateViewModel]. Buffered with replay=0 and a small
 * extra buffer so a result emitted before the ViewModel subscribes is not lost while the
 * app restarts through the installer.
 */
object InstallResultBus {
    private val _results = MutableSharedFlow<InstallResult>(replay = 1, extraBufferCapacity = 4)
    val results: SharedFlow<InstallResult> = _results.asSharedFlow()

    fun publish(result: InstallResult) {
        _results.tryEmit(result)
    }
}
