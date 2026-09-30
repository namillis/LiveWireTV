package com.livewire.tv.feature.update.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

private const val TAG = "LiveWireUpdate"

/**
 * Receives the [PackageInstaller] commit callback. Declared `exported="false"` in the
 * manifest so only the system (delivering our own PendingIntent) can invoke it.
 *
 * On [PackageInstaller.STATUS_PENDING_USER_ACTION] it launches the system's confirm
 * screen. Other statuses are forwarded to [InstallResultBus] so the ViewModel can react.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { Log.w(TAG, "Could not launch install confirm", it) }
                } else {
                    Log.w(TAG, "PENDING_USER_ACTION with no confirm intent")
                    InstallResultBus.publish(InstallResult.Failed(status, message))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Install succeeded")
                InstallResultBus.publish(InstallResult.Success)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.i(TAG, "Install aborted by user")
                InstallResultBus.publish(InstallResult.Aborted)
            }
            else -> {
                Log.w(TAG, "Install failed: status=$status msg=$message")
                InstallResultBus.publish(InstallResult.Failed(status, message))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.livewire.tv.update.INSTALL_STATUS"

        /** Build the PendingIntent the installer session commits to. */
        fun pendingIntent(context: Context, sessionId: Int): PendingIntent {
            val intent = Intent(context, UpdateInstallReceiver::class.java)
                .setAction(ACTION_INSTALL_STATUS)
                .setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(context, sessionId, intent, flags)
        }
    }
}

/** The terminal (or interim) install outcome, published from the receiver. */
sealed interface InstallResult {
    data object Success : InstallResult
    data object Aborted : InstallResult
    data class Failed(val status: Int, val message: String?) : InstallResult
}
