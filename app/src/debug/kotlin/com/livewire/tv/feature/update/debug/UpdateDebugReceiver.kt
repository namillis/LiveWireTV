package com.livewire.tv.feature.update.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.livewire.tv.BuildConfig
import com.livewire.tv.feature.settings.data.SettingsStore
import com.livewire.tv.feature.update.data.ApkDownloader
import com.livewire.tv.feature.update.data.DownloadProgress
import com.livewire.tv.feature.update.data.UpdateChecker
import com.livewire.tv.feature.update.data.UpdateCheckResult
import com.livewire.tv.feature.update.data.UpdateInstaller
import com.livewire.tv.feature.update.domain.selectAssets
import com.livewire.tv.feature.update.domain.version
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DEBUG-ONLY. A headless end-to-end trigger for the self-update flow, so the whole
 * check → download → verify → install pipeline can be driven from `adb` with no UI while
 * the dialog/Settings mockups are still being chosen. Every state is logged to the
 * `LiveWireUpdate` logcat tag.
 *
 * This class lives in `app/src/debug/` and is therefore compiled out of release builds.
 * It is registered (also debug-only) in `app/src/debug/AndroidManifest.xml`, not exported,
 * and gated on [BuildConfig.DEBUG] at runtime as belt-and-braces.
 *
 * Usage (with the app installed and updates enabled):
 *   adb shell am broadcast -a com.livewire.tv.debug.RUN_UPDATE \
 *     -n <applicationId>/com.livewire.tv.feature.update.debug.UpdateDebugReceiver
 *   adb logcat -s LiveWireUpdate
 */
@AndroidEntryPoint
class UpdateDebugReceiver : BroadcastReceiver() {

    @Inject lateinit var checker: UpdateChecker
    @Inject lateinit var downloader: ApkDownloader
    @Inject lateinit var installer: UpdateInstaller
    @Inject lateinit var settings: SettingsStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.action != ACTION_RUN) return
        Log.i(TAG, "STATE=Idle — debug trigger received; UPDATES_ENABLED=${BuildConfig.UPDATES_ENABLED} " +
            "current=${BuildConfig.VERSION_NAME} api=${BuildConfig.UPDATE_API_BASE}")

        val pending = goAsync()
        scope.launch {
            try {
                runFlow(context)
            } catch (e: Exception) {
                Log.e(TAG, "STATE=Failed — ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun runFlow(context: Context) {
        Log.i(TAG, "STATE=Checking")
        val now = System.currentTimeMillis()
        when (val r = checker.checkNow(now)) {
            is UpdateCheckResult.UpToDate -> { Log.i(TAG, "STATE=UpToDate current=${r.currentVersion}"); return }
            is UpdateCheckResult.Skipped -> { Log.i(TAG, "STATE=Skipped (policy)"); return }
            is UpdateCheckResult.SkippedByUser -> { Log.i(TAG, "STATE=SkippedByUser version=${r.version}"); return }
            is UpdateCheckResult.Failed -> { Log.w(TAG, "STATE=Failed reason=${r.error} (${r.error.message})"); return }
            is UpdateCheckResult.Available -> {
                val release = r.release
                Log.i(TAG, "STATE=Available version=${release.version} size=${apkSize(release)} " +
                    "notes=${release.notes?.take(80)}")
                val assets = selectAssets(release.assets, release.version, BuildConfig.UPDATE_ASSET_PATTERN)
                if (assets == null) { Log.w(TAG, "STATE=Failed reason=no installable asset"); return }

                if (!installer.canInstallPackages()) {
                    val si = installer.unknownSourcesSettingsIntent()
                    Log.w(TAG, "STATE=NeedsUnknownSourcesPermission settingsResolvable=${si != null}")
                    // Launch the settings screen so a human can grant it during the e2e run.
                    if (si != null) {
                        runCatching { context.startActivity(si) }
                            .onFailure { Log.w(TAG, "Could not open unknown-sources settings", it) }
                    }
                    return
                }

                var ready: java.io.File? = null
                downloader.download(assets).collect { p ->
                    when (p) {
                        is DownloadProgress.Downloading -> Log.i(TAG, "STATE=Downloading pct=${p.pct} ${p.bytes}/${p.total}")
                        is DownloadProgress.Verifying -> Log.i(TAG, "STATE=Verifying")
                        is DownloadProgress.Done -> { ready = p.apk; Log.i(TAG, "STATE=Verified path=${p.apk.absolutePath}") }
                        is DownloadProgress.Failed -> Log.w(TAG, "STATE=Failed reason=${p.error} (${p.error.message})")
                    }
                }
                val apk = ready ?: return
                Log.i(TAG, "STATE=Installing")
                val err = installer.install(apk)
                if (err != null) Log.w(TAG, "STATE=Failed reason=$err (${err.message})")
                else Log.i(TAG, "STATE=Installing — handed to PackageInstaller; watch for confirm screen")
            }
        }
    }

    private fun apkSize(release: com.livewire.tv.feature.update.domain.ReleaseInfo): Long =
        selectAssets(release.assets, release.version, BuildConfig.UPDATE_ASSET_PATTERN)?.apk?.sizeBytes ?: 0L

    companion object {
        private const val TAG = "LiveWireUpdate"
        const val ACTION_RUN = "com.livewire.tv.debug.RUN_UPDATE"
    }
}
