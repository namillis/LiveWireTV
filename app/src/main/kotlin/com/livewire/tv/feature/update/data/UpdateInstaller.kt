package com.livewire.tv.feature.update.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.livewire.tv.feature.update.domain.UpdateError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LiveWireUpdate"

/**
 * Hands a verified APK to Android's [PackageInstaller]. Opens a `MODE_FULL_INSTALL`
 * session, streams the APK in, and commits to a PendingIntent aimed at the non-exported
 * [UpdateInstallReceiver]. On API 31+ it best-effort requests no user action (silent
 * update), which only takes effect when LiveWire is the installer of record — otherwise
 * Android still shows the confirm screen, which is handled in the receiver.
 *
 * Install progress and terminal status arrive via [InstallResultBus].
 */
@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** True when the app may install packages (API < 26 has no per-app gate). */
    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * The intent that sends the user to the "allow this app to install unknown apps"
     * screen, or null when the device hides it (some Google TV / Fire OS builds), so the
     * caller can fall back to on-screen manual steps instead of a dead button.
     */
    fun unknownSourcesSettingsIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Only offer it if something can handle it (Google TV/Fire OS may hide the page).
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }

    /**
     * Begin installing [apk]. Returns null on a clean hand-off to the system (watch
     * [InstallResultBus] for the outcome), or an [UpdateError] if the session couldn't
     * even be created/written.
     */
    suspend fun install(apk: File): UpdateError? = withContext(Dispatchers.IO) {
        if (!canInstallPackages()) return@withContext UpdateError.UNKNOWN_SOURCES_NOT_ALLOWED
        val installer = context.packageManager.packageInstaller
        try {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Best effort: silent install where we are the installer of record.
                    runCatching {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                }
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(apk.name, 0, apk.length()).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                        session.fsync(output)
                    }
                }
                val pending = UpdateInstallReceiver.pendingIntent(context, sessionId)
                session.commit(pending.intentSender)
                Log.i(TAG, "Committed install session $sessionId")
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Install session failed", e)
            UpdateError.INSTALL_FAILED
        }
    }
}
