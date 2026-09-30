package com.livewire.tv.feature.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.BuildConfig
import com.livewire.tv.feature.settings.data.SettingsStore
import com.livewire.tv.feature.update.data.ApkDownloader
import com.livewire.tv.feature.update.data.DownloadProgress
import com.livewire.tv.feature.update.data.InstallResult
import com.livewire.tv.feature.update.data.InstallResultBus
import com.livewire.tv.feature.update.data.UpdateCheckResult
import com.livewire.tv.feature.update.data.UpdateChecker
import com.livewire.tv.feature.update.data.UpdateInstaller
import com.livewire.tv.feature.update.domain.ReleaseInfo
import com.livewire.tv.feature.update.domain.SelectedAssets
import com.livewire.tv.feature.update.domain.UpdateError
import com.livewire.tv.feature.update.domain.selectAssets
import com.livewire.tv.feature.update.domain.version
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Drives the update flow and exposes a single [UpdateUiState] for the UI (the dialog +
 * Settings rows) to render. The UI never touches the data layer directly; it calls the
 * action methods here and observes [state].
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val checker: UpdateChecker,
    private val downloader: ApkDownloader,
    private val installer: UpdateInstaller,
    private val settings: SettingsStore,
) : ViewModel() {

    /**
     * The states the UI binds to. A UI author reads only this file to know the surface.
     */
    sealed interface UpdateUiState {
        /** Nothing to show. */
        data object Idle : UpdateUiState
        /** A check is in flight. */
        data object Checking : UpdateUiState
        /** Installed build is current. [current] is the version string for the About row. */
        data class UpToDate(val current: String) : UpdateUiState
        /** A newer release is available. */
        data class Available(
            val version: String,
            val notes: String?,
            val sizeBytes: Long,
        ) : UpdateUiState
        /** Downloading the APK. */
        data class Downloading(val pct: Int, val bytes: Long, val total: Long) : UpdateUiState
        /** Verifying the downloaded APK (size, checksum, signer). */
        data object Verifying : UpdateUiState
        /** LiveWire needs the "install unknown apps" permission before it can install. */
        data class NeedsUnknownSourcesPermission(
            /** Non-null: launch to grant. Null: device hides the page — show [manualSteps]. */
            val settingsIntent: Intent?,
            val manualSteps: String,
        ) : UpdateUiState
        /** Handed to the system installer; may show the confirm screen. */
        data object Installing : UpdateUiState
        /** A step failed. [error] carries a plain-language message. */
        data class Failed(val error: UpdateError) : UpdateUiState
    }

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _pendingUpdatedVersion = MutableStateFlow<String?>(null)
    /** The version string just installed (for a "Updated to X" note on next launch), or null. */
    val pendingUpdatedVersion: StateFlow<String?> = _pendingUpdatedVersion.asStateFlow()

    private var currentRelease: ReleaseInfo? = null
    private var readyApk: File? = null
    private var downloadJob: Job? = null

    init {
        viewModelScope.launch {
            _pendingUpdatedVersion.value = settings.settings.first().pendingUpdatedVersion
        }
        // Bridge installer callbacks into the UI state.
        viewModelScope.launch {
            InstallResultBus.results.collect { result ->
                when (result) {
                    is InstallResult.Success -> {
                        currentRelease?.let { settings.setPendingUpdatedVersion(it.version) }
                        downloader.cleanupOldDownloads()
                        // App typically restarts; leave state as Installing.
                    }
                    is InstallResult.Aborted -> _state.value = UpdateUiState.Failed(UpdateError.INSTALL_ABORTED)
                    is InstallResult.Failed -> _state.value = UpdateUiState.Failed(UpdateError.INSTALL_FAILED)
                }
            }
        }
    }

    /** Automatic launch check (honours the 24h gate, auto-check switch and player flag). */
    fun autoCheck(playerOpen: Boolean) = check(auto = true, playerOpen = playerOpen)

    /** Manual "Check for updates" press. */
    fun checkNow() = check(auto = false, playerOpen = false)

    private fun check(auto: Boolean, playerOpen: Boolean) {
        _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val result = if (auto) checker.autoCheck(now, playerOpen) else checker.checkNow(now)
            _state.value = when (result) {
                is UpdateCheckResult.Available -> {
                    currentRelease = result.release
                    UpdateUiState.Available(
                        version = result.release.version,
                        notes = result.release.notes,
                        sizeBytes = apkSizeOf(result.release),
                    )
                }
                is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(result.currentVersion)
                is UpdateCheckResult.SkippedByUser -> UpdateUiState.Idle
                is UpdateCheckResult.Skipped -> UpdateUiState.Idle
                is UpdateCheckResult.Failed -> UpdateUiState.Failed(result.error)
            }
        }
    }

    /** Start downloading + verifying the available release. */
    fun download() {
        val release = currentRelease ?: return
        val assets = selectAssets(release.assets, release.version, BuildConfig.UPDATE_ASSET_PATTERN)
            ?: run { _state.value = UpdateUiState.Failed(UpdateError.UNKNOWN); return }

        if (!installer.canInstallPackages()) {
            _state.value = UpdateUiState.NeedsUnknownSourcesPermission(
                settingsIntent = installer.unknownSourcesSettingsIntent(),
                manualSteps = MANUAL_UNKNOWN_SOURCES_STEPS,
            )
            return
        }

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            downloader.download(assets).collect { p ->
                _state.value = when (p) {
                    is DownloadProgress.Downloading -> UpdateUiState.Downloading(p.pct, p.bytes, p.total)
                    is DownloadProgress.Verifying -> UpdateUiState.Verifying
                    is DownloadProgress.Done -> {
                        readyApk = p.apk
                        install()
                        UpdateUiState.Installing
                    }
                    is DownloadProgress.Failed -> UpdateUiState.Failed(p.error)
                }
            }
        }
    }

    /** Re-check the permission (call from onResume after the settings screen) and continue. */
    fun onPermissionMaybeGranted() {
        if (_state.value is UpdateUiState.NeedsUnknownSourcesPermission && installer.canInstallPackages()) {
            download()
        }
    }

    private fun install() {
        val apk = readyApk ?: return
        viewModelScope.launch {
            _state.value = UpdateUiState.Installing
            val error = installer.install(apk)
            if (error != null) _state.value = UpdateUiState.Failed(error)
            // Otherwise the InstallResultBus collector drives the terminal state.
        }
    }

    /** Cancel an in-flight download. */
    fun cancel() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = UpdateUiState.Idle
    }

    /** "Later": dismiss without recording a skip. */
    fun later() {
        _state.value = UpdateUiState.Idle
    }

    /** "Skip this version": remember it so it isn't surfaced again. */
    fun skip() {
        val v = currentRelease?.version ?: return
        viewModelScope.launch { settings.setSkippedVersion(v) }
        _state.value = UpdateUiState.Idle
    }

    /** Dismiss any terminal state back to idle. */
    fun dismiss() {
        _state.value = UpdateUiState.Idle
    }

    /** Clear the "Updated to X" marker after it has been shown once. */
    fun clearPendingUpdatedVersion() {
        viewModelScope.launch { settings.setPendingUpdatedVersion(null) }
    }

    private fun apkSizeOf(release: ReleaseInfo): Long =
        selectAssets(release.assets, release.version, BuildConfig.UPDATE_ASSET_PATTERN)?.apk?.sizeBytes ?: 0L

    companion object {
        private const val MANUAL_UNKNOWN_SOURCES_STEPS =
            "Open your device Settings → Apps → Special app access → Install unknown apps, " +
                "find LiveWire, and turn it on. Then return here and try again."
    }
}
