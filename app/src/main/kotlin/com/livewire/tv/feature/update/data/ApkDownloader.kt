package com.livewire.tv.feature.update.data

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import com.livewire.tv.feature.update.domain.ApkVerificationInput
import com.livewire.tv.feature.update.domain.ReleaseAsset
import com.livewire.tv.feature.update.domain.SelectedAssets
import com.livewire.tv.feature.update.domain.UpdateError
import com.livewire.tv.feature.update.domain.parseSha256
import com.livewire.tv.feature.update.domain.verifyApk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LiveWireUpdate"

/** Progress events emitted while downloading. */
sealed interface DownloadProgress {
    data class Downloading(val bytes: Long, val total: Long) : DownloadProgress {
        val pct: Int get() = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
    }
    data object Verifying : DownloadProgress
    data class Done(val apk: File) : DownloadProgress
    data class Failed(val error: UpdateError) : DownloadProgress
}

/**
 * Downloads a release APK into `cacheDir/updates/`, reports progress, verifies size +
 * SHA-256 + package identity + signer against the installed app, and cleans up old
 * downloads. Cancel by cancelling the collecting coroutine — the partial file is deleted.
 *
 * Verification uses the pure [verifyApk]; this class only gathers the Android-side facts.
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    private val dir: File get() = File(context.cacheDir, "updates").apply { mkdirs() }

    /**
     * Download and verify [assets]. Emits [DownloadProgress]. Terminal events are
     * [DownloadProgress.Done] (verified, ready to install) or [DownloadProgress.Failed].
     * The APK's versionCode is checked against the installed app's inside verification.
     */
    fun download(assets: SelectedAssets): Flow<DownloadProgress> = flow {
        val apkAsset = assets.apk
        cleanupOldDownloads()

        // Free-space check: require 2x the asset size (download + installer copy headroom).
        val free = dir.usableSpace
        if (apkAsset.sizeBytes > 0 && free < apkAsset.sizeBytes * 2) {
            Log.w(TAG, "Not enough space: free=$free need=${apkAsset.sizeBytes * 2}")
            emit(DownloadProgress.Failed(UpdateError.NOT_ENOUGH_SPACE))
            return@flow
        }

        val target = File(dir, apkAsset.name)
        target.delete()

        try {
            emit(DownloadProgress.Downloading(0, apkAsset.sizeBytes))
            downloadTo(apkAsset, target) { bytes, total ->
                emit(DownloadProgress.Downloading(bytes, total))
            }

            emit(DownloadProgress.Verifying)
            val expectedSha = fetchExpectedSha(assets.sha256Asset)
            val actualSha = sha256Of(target)
            val archive = readArchiveInfo(target)
            val installed = readInstalledInfo()

            val error = verifyApk(
                ApkVerificationInput(
                    expectedSha256 = expectedSha,
                    actualSha256 = actualSha,
                    expectedSize = apkAsset.sizeBytes,
                    actualSize = target.length(),
                    apkPackageName = archive?.packageName,
                    apkVersionCode = archive?.versionCode,
                    apkSignerDigests = archive?.signerDigests ?: emptySet(),
                    installedPackageName = installed.packageName,
                    installedVersionCode = installed.versionCode,
                    installedSignerDigests = installed.signerDigests,
                ),
            )
            if (error != null) {
                Log.w(TAG, "Verification failed: $error")
                target.delete()
                emit(DownloadProgress.Failed(error))
                return@flow
            }
            Log.i(TAG, "Verified ${apkAsset.name} (versionCode=${archive?.versionCode})")
            emit(DownloadProgress.Done(target))
        } catch (e: CancellationException) {
            target.delete()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download failed", e)
            target.delete()
            emit(DownloadProgress.Failed(classify(e)))
        }
    }.flowOn(Dispatchers.IO)

    /** Delete every file under the updates dir (called before a fresh download and post-install). */
    fun cleanupOldDownloads() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    // --- IO helpers ------------------------------------------------------------

    private suspend inline fun downloadTo(
        asset: ReleaseAsset,
        target: File,
        crossinline onProgress: suspend (Long, Long) -> Unit,
    ) {
        val req = Request.Builder().url(asset.downloadUrl).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw java.io.IOException("Empty body")
            val total = if (asset.sizeBytes > 0) asset.sizeBytes else body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var sum = 0L
                    var lastReported = 0L
                    while (input.read(buf).also { read = it } >= 0) {
                        currentCoroutineContext().ensureActive() // cancellation point
                        output.write(buf, 0, read)
                        sum += read
                        // Throttle progress emissions to ~1 per 64KB block boundary of 1%.
                        if (total <= 0 || sum - lastReported >= total / 100 + 1) {
                            lastReported = sum
                            onProgress(sum, total)
                        }
                    }
                    output.flush()
                    onProgress(sum, if (total > 0) total else sum)
                }
            }
        }
    }

    private fun fetchExpectedSha(asset: ReleaseAsset?): String? {
        if (asset == null) return null
        val req = Request.Builder().url(asset.downloadUrl).build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                parseSha256(resp.body?.string())
            }
        }.getOrNull()
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } >= 0) md.update(buf, 0, read)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private data class ArchiveInfo(val packageName: String?, val versionCode: Long?, val signerDigests: Set<String>)
    private data class InstalledInfo(val packageName: String, val versionCode: Long, val signerDigests: Set<String>)

    @Suppress("DEPRECATION")
    private fun readArchiveInfo(apk: File): ArchiveInfo? {
        val pm = context.packageManager
        val path = apk.absolutePath
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val flags = PackageManager.GET_SIGNING_CERTIFICATES
                val info = pm.getPackageArchiveInfo(path, flags) ?: return null
                val sigs = info.signingInfo?.let { si ->
                    if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
                } ?: emptyArray()
                ArchiveInfo(info.packageName, versionCodeOf(info), digestsOf(sigs))
            } else {
                val flags = PackageManager.GET_SIGNATURES
                val info = pm.getPackageArchiveInfo(path, flags) ?: return null
                ArchiveInfo(info.packageName, versionCodeOf(info), digestsOf(info.signatures ?: emptyArray()))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read archive info", e)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun readInstalledInfo(): InstalledInfo {
        val pm = context.packageManager
        val pkg = context.packageName
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val sigs = info.signingInfo?.let { si ->
                if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            } ?: emptyArray()
            InstalledInfo(pkg, versionCodeOf(info), digestsOf(sigs))
        } else {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            InstalledInfo(pkg, versionCodeOf(info), digestsOf(info.signatures ?: emptyArray()))
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()

    private fun digestsOf(sigs: Array<Signature>): Set<String> {
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { sig ->
            md.reset()
            md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun classify(e: Exception): UpdateError =
        com.livewire.tv.feature.update.domain.mapNetworkError(e)
}
