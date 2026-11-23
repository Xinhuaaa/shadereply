package io.shadereply

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

class WeChatPreviewProvider : ContentProvider() {
    override fun onCreate() = true

    @Synchronized
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = requireContext()
        val uid = ctx.packageManager.getPackageUid(WeChatNotifications.PACKAGE, PackageManager.PackageInfoFlags.of(0))
        check(method == "store" && Binder.getCallingUid() == uid) { "WeChat preview access denied" }
        val bytes = extras?.getByteArray("image")?.takeIf { it.size in 1..MAX_BYTES } ?: return null
        val mime = extras.getString("mime")?.takeIf { it in extensions } ?: return null
        val dir = File(ctx.cacheDir, "wechat-previews").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val existing = dir.listFiles().orEmpty().sortedByDescending(File::lastModified)
        existing.forEachIndexed { index, file ->
            if (index >= 127 || now - file.lastModified() > 24 * 60 * 60_000) file.delete()
        }
        val name = "${UUID.randomUUID()}.${extensions[mime]}"
        File(dir, name).writeBytes(bytes)
        return Bundle().apply { putString("uri", ROOT.buildUpon().appendPath(name).build().toString()) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = requireContext()
        val uid = Binder.getCallingUid()
        val callers = ctx.packageManager.getPackagesForUid(uid).orEmpty()
        if (mode != "r" || (uid != Process.SYSTEM_UID && uid != Process.myUid() &&
                callers.none { it == WeChatNotifications.PACKAGE || it == "com.android.systemui" })) {
            throw FileNotFoundException()
        }
        val name = name(uri) ?: throw FileNotFoundException()
        return ParcelFileDescriptor.open(File(File(ctx.cacheDir, "wechat-previews"), name),
            ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? =
        name(uri)?.substringAfterLast('.')?.let { ext -> extensions.entries.firstOrNull { it.value == ext }?.key }

    private fun name(uri: Uri): String? = uri.pathSegments.singleOrNull()?.takeIf(pattern::matches)
    override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?,
        order: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        internal val ROOT = Uri.parse("content://io.shadereply.previews")
        internal const val MAX_BYTES = 448 * 1024
        private val pattern = Regex("[a-f0-9-]{36}\\.(jpg|png|gif|webp)")
        private val extensions = mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp")
    }
}
