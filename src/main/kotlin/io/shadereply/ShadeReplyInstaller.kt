package io.shadereply

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat

internal class ShadeReplyInstaller(private val context: Context) {
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("apk_update", Context.MODE_PRIVATE)

    sealed interface State {
        data object Idle : State
        data class Pending(val percent: Int?) : State
        data object Ready : State
        data object Failed : State
    }

    fun status(asset: ShadeReplyApk): State {
        val id = downloadId(asset)
        if (id < 0) return State.Idle
        return downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return@use State.Failed
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> State.Ready
                DownloadManager.STATUS_FAILED -> State.Failed
                else -> {
                    val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    State.Pending((100.0 * bytes / asset.size).toInt().coerceIn(0, 99).takeIf { bytes > 0 })
                }
            }
        }
    }

    fun trackedRelease(installed: String): ShadeReplyRelease? {
        val tag = prefs.getString("tag", null) ?: return null
        val id = prefs.getLong("asset", -1)
        val size = prefs.getLong("size", -1)
        if (id <= 0 || size <= 0) return null
        val version = tag.removePrefix("v")
        val name = "ShadeReply-$version.apk"
        val project = ShadeReplyReleases.PROJECT
        val asset = ShadeReplyApk(id, name, version, "$project/releases/download/$tag/$name", size,
            prefs.getString("sha256", null))
        if (!ShadeReplyReleases.isNewer(tag, installed)) {
            discard(asset)
            return null
        }
        return ShadeReplyRelease(tag, prefs.getBoolean("prerelease", false), prefs.getString("notes", "").orEmpty(),
            "$project/releases/tag/$tag", asset)
    }

    fun start(release: ShadeReplyRelease, asset: ShadeReplyApk) {
        if (status(asset) is State.Pending) return
        val request = DownloadManager.Request(Uri.parse(asset.url))
            .setTitle(asset.name)
            .setMimeType(APK_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, path(asset))
        val previousId = prefs.getLong("id", -1)
        if (previousId >= 0) downloads.remove(previousId)
        val file = apkFile(asset)
        val parent = file.parentFile ?: throw IOException("Missing download directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create download directory")
        if (file.exists() && !file.delete()) throw IOException("Cannot replace downloaded APK")
        val id = downloads.enqueue(request)
        val saved = prefs.edit().putLong("asset", asset.id).putLong("id", id).putLong("size", asset.size)
            .putString("tag", release.tag).putBoolean("prerelease", release.prerelease)
            .putString("notes", release.notes).putString("sha256", asset.sha256).commit()
        if (!saved) {
            downloads.remove(id)
            throw IOException("Cannot save download ID")
        }
    }

    fun discard(asset: ShadeReplyApk) {
        val id = downloadId(asset)
        if (id < 0) return
        downloads.remove(id)
        prefs.edit().clear().apply()
    }

    fun installationIntent(asset: ShadeReplyApk): Intent {
        val apk = apkFile(asset)
        if (!apk.isFile || apk.length() != asset.size) throw IOException("Incomplete APK")
        if (asset.sha256 != null) {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(apk.inputStream().buffered(), digest).use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (stream.read(buffer) != -1) { /* Read the entire APK to compute its digest. */ }
            }
            if (HexFormat.of().formatHex(digest.digest()) != asset.sha256) throw IOException("Invalid APK digest")
        }
        val manager = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val installed = manager.getPackageInfo(context.packageName, flags)
        val update = manager.getPackageArchiveInfo(apk.absolutePath, flags) ?: throw IOException("Invalid APK")
        val currentSigners = installed.signingInfo?.apkContentsSigners?.toSet()
        val updateSigners = update.signingInfo?.apkContentsSigners?.toSet()
        if (update.packageName != installed.packageName || update.versionName != asset.version
            || update.longVersionCode <= installed.longVersionCode || currentSigners.isNullOrEmpty()
            || currentSigners != updateSigners
        ) throw IOException("Invalid package, version, or signer")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun downloadId(asset: ShadeReplyApk): Long =
        if (prefs.getLong("asset", -1) == asset.id) prefs.getLong("id", -1) else -1

    private fun apkFile(asset: ShadeReplyApk): File {
        val storage = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("App download directory unavailable")
        return File(storage, path(asset))
    }

    private fun path(asset: ShadeReplyApk) = "updates/${asset.id}.apk"

    private companion object {
        const val APK_TYPE = "application/vnd.android.package-archive"
    }
}
