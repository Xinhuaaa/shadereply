package io.shadereply

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import org.luckypray.dexkit.DexKitBridge
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.UUID

// Media lookup signatures adapted from SymonChu/WeKite, GPL-3.0.
internal class WeChatMedia(dex: DexKitBridge, loader: ClassLoader, private val report: (String, Throwable) -> Unit) {
    val insert = dex.findMethod {
        searchPackages("com.tencent.mm.storage")
        matcher { usingEqStrings("MsgInfo processAddMsg insert db error") }
    }.singleOrNull()?.getMethodInstance(loader)?.apply { isAccessible = true }
        ?: error("WeChat message storage method unavailable")

    private fun field(name: String) = generateSequence(insert.parameterTypes.first()) { it.superclass }
        .firstNotNullOf { type -> runCatching { type.getDeclaredField("field_$name") }.getOrNull() }
        .apply { isAccessible = true }

    val talker = field("talker")
    val content = field("content")
    val sent = field("isSend")
    private val type = field("type")
    private val serverId = field("msgSvrId")
    val imagePath = field("imgPath")
    val localId = field("msgId")
    private val image = runCatching { ImageLookup(dex, loader, insert.parameterTypes.first()) }
        .onFailure { report("Picture lookup unavailable", it) }.getOrNull()
    private val sticker = runCatching { StickerLookup(dex, loader) }
        .onFailure { report("Sticker lookup unavailable", it) }.getOrNull()
    private val decoder = runCatching { loader.loadClass("com.tencent.mm.plugin.gif.MMWXGFJNI") }.getOrNull()
    private val fileProvider = runCatching {
        loader.loadClass("android.support.v4.content.FileProvider").getMethod(
            "getUriForFile", Context::class.java, String::class.java, File::class.java)
    }.getOrNull()
    fun typeOf(message: Any): Int = type.getInt(message)

    fun supportsType(messageType: Int): Boolean = when (messageType) {
        3 -> image != null
        47, 1048625 -> sticker != null
        else -> false
    }

    fun resolve(
        context: Context, messageType: Int, message: Any?, rawContent: String, config: WeChatConfig,
        callback: (String, Uri) -> Unit,
    ) {
        val isSticker = messageType != 3
        if (!isSticker && (message == null || config.imageMode == WeChatConfig.IMAGE_OFF)) return
        worker.execute {
            val timeout = if (isSticker && !config.waitSticker) 0L else 3_000L
            val deadline = SystemClock.elapsedRealtime() + timeout
            val path = message?.let { imagePath.get(it) as? String }
            val md5 = if (isSticker) {
                path?.takeIf(md5Pattern::matches)
                    ?: stickerId(rawContent)
                    ?: message?.let { (content.get(it) as? String)?.let(::stickerId) }
            } else null
            if (isSticker && md5 == null) return@execute
            var requestedSticker = false

            do {
                val decoded = runCatching {
                    val bytes = if (!isSticker) {
                        image?.read(requireNotNull(message), serverId.getLong(message), path, config.imageMode)
                    } else sticker?.read(md5!!.lowercase())
                    bytes?.let { decode(it, isSticker) }
                }.getOrNull()
                if (decoded != null) {
                    val (mime, payload) = decoded
                    runCatching { storePreview(context, mime, payload, callback) }
                        .onFailure { report("Could not publish the notification attachment", it) }
                    return@execute
                }
                if (md5 != null && config.waitSticker && !requestedSticker) {
                    requestedSticker = runCatching { sticker?.request(md5.lowercase()) == true }.getOrDefault(false)
                }
                if (SystemClock.elapsedRealtime() >= deadline) return@execute
                SystemClock.sleep(80)
            } while (true)
        }
    }

    private fun storePreview(context: Context, mime: String, bytes: ByteArray, callback: (String, Uri) -> Unit) {
        val provider = fileProvider ?: error("WeChat attachment provider unavailable")
        val folder = File(context.cacheDir, "shadereply-previews").apply { mkdirs() }
        val extension = if (mime == "image/jpeg") "jpg" else mime.substringAfter('/')
        val uri = synchronized(previewLock) {
            val now = System.currentTimeMillis()
            folder.listFiles().orEmpty().sortedByDescending(File::lastModified).forEachIndexed { index, file ->
                if (index >= 127 || now - file.lastModified() > 86_400_000L) file.delete()
            }
            val file = File(folder, "${UUID.randomUUID()}.$extension")
            file.writeBytes(bytes)
            try {
                provider.invoke(null, context, "com.tencent.mm.external.fileprovider", file) as Uri
            } catch (failure: ReflectiveOperationException) {
                file.delete()
                throw failure
            }
        }
        callback(mime, uri)
    }

    private fun decode(raw: ByteArray, isSticker: Boolean): Pair<String, ByteArray>? {
        val limit = WeChatPreviewProvider.MAX_BYTES
        val bytes = if (mime(raw) == null) {
            val gif = if (isSticker) runCatching {
                decoder?.getMethod("nativeWxamToGif", ByteArray::class.java)?.invoke(null, raw) as? ByteArray
            }.getOrNull() else null
            gif?.takeIf { mime(it) != null } ?: runCatching {
                decoder?.getMethod("wxam2PicBuf", ByteArray::class.java, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType)?.invoke(null, raw, 0, 5) as? ByteArray
            }.getOrNull() ?: return null
        } else raw
        val type = mime(bytes) ?: return null
        // GIF and WebP can carry animation. Keep the encoded frames in the file-backed preview.
        if (isSticker && type in ANIMATED_FORMATS && bytes.size <= MAX_INPUT) return type to bytes
        if (bytes.size <= limit) return type to bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 720) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 72, out)
            return out.toByteArray().takeIf { it.size <= limit }?.let { "image/webp" to it }
        } finally {
            bitmap.recycle()
        }
    }

    private class ImageLookup(dex: DexKitBridge, loader: ClassLoader, messageClass: Class<*>) {
        private val storage = dex.findClass {
            matcher { usingEqStrings("MicroMsg.ImgInfoStorage", "generateMd5: %s, %s") }
        }.single().getInstance(loader)
        private val download = dex.findMethod {
            matcher { usingEqStrings("ModelImage.DownloadImgService", "] add failed, task already done") }
        }.single().className
        private val feature = dex.findClass {
            matcher {
                addFieldForType(storage)
                addFieldForType(download)
            }
        }.single().getInstance(loader)
        private val getStorage = feature.declaredMethods.single {
            Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == storage
        }.apply { isAccessible = true }
        private val resolvePath = storage.declaredMethods.first {
            val p = it.parameterTypes
            p.size == 3 && p[0].isAssignableFrom(messageClass) && p[1].isEnum && p[2] == String::class.java &&
                it.returnType == String::class.java
        }.apply { isAccessible = true }
        private val kinds = resolvePath.parameterTypes[1].enumConstants.orEmpty().associateBy { (it as Enum<*>).name }
        private val vfs = dex.findClass {
            searchPackages("com.tencent.mm.vfs")
            matcher { usingStrings("MicroMsg.VFSFileOp", "Cannot resolve path or URI") }
        }.single().getInstance(loader).declaredMethods.first {
            Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                it.returnType == InputStream::class.java
        }.apply { isAccessible = true }

        private val dbField = generateSequence(storage) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { it.type.name == "com.tencent.wcdb.database.SQLiteDatabase" }
            ?.apply { isAccessible = true }

        fun read(message: Any, serverId: Long, thumbPath: String?, mode: Int): ByteArray? {
            val host = getStorage.invoke(null) ?: return null
            val names = imageNames(host, serverId)
            val options = if (mode == WeChatConfig.IMAGE_LARGE) {
                listOf("ORIGIN_IMAGE" to names?.large, "BIG_IMAGE" to names?.large,
                    "MID_IMAGE" to names?.mid, "THUMB_IMAGE" to (names?.thumb ?: thumbPath))
            } else listOf("MID_IMAGE" to names?.mid, "THUMB_IMAGE" to (names?.thumb ?: thumbPath))
            return options.firstNotNullOfOrNull { (kind, name) ->
                val category = kinds[kind]
                if (name.isNullOrEmpty() || category == null) return@firstNotNullOfOrNull null
                val file = runCatching { resolvePath.invoke(host, message, category, name) as? String }
                    .getOrNull() ?: return@firstNotNullOfOrNull null
                runCatching {
                    (vfs.invoke(null, file) as? InputStream)?.use { it.readNBytes(MAX_INPUT + 1) }
                        ?.takeIf { it.size in 1..MAX_INPUT }
                }.getOrNull()
            }
        }

        private data class Names(val thumb: String?, val large: String?, val mid: String?)

        private fun imageNames(host: Any, id: Long): Names? = runCatching {
            val db = dbField?.get(host) ?: return null
            val rawQuery = db.javaClass.getMethod("rawQuery", String::class.java, Array<String>::class.java)
            (rawQuery.invoke(db, "SELECT thumbImgPath,bigImgPath,midImgPath FROM ImgInfo2 WHERE msgSvrId=?",
                arrayOf(id.toString())) as? Cursor)?.use {
                if (it.moveToFirst()) Names(it.getString(0), it.getString(1), it.getString(2)) else null
            }
        }.getOrNull()
    }

    private class StickerLookup(dex: DexKitBridge, loader: ClassLoader) {
        private val manager = dex.findClass {
            searchPackages("com.tencent.mm.storage")
            matcher { usingEqStrings("MicroMsg.emoji.EmojiStorageMgr", "EmojiStorageMgr: %s") }
        }.single().getInstance(loader)
        private val storage = dex.findClass {
            matcher { usingEqStrings("MicroMsg.emoji.EmojiInfoStorage", "md5 is null or invalue. md5:%s") }
        }.single().getInstance(loader)
        private val encryptor = WeChatMethods.classWithMethod(dex, null,
            "MicroMsg.emoji.EmojiFileEncryptMgr", "decode emoji file failed. path is no exist")
            ?.getInstance(loader) ?: error("WeChat sticker decoder unavailable")
        private val getManager = singleton(manager)
        private val getEncryptor = singleton(encryptor)
        private val getStorage = manager.declaredMethods.first { it.parameterCount == 0 && it.returnType == storage }
            .apply { isAccessible = true }
        private val info = loader.loadClass("com.tencent.mm.storage.emotion.EmojiInfo")
        private val getInfo = storage.declaredMethods.first {
            it.parameterTypes.contentEquals(arrayOf(String::class.java)) && it.returnType == info
        }.apply { isAccessible = true }
        private val decode = encryptor.declaredMethods.first {
            it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(info) && it.returnType == ByteArray::class.java
        }.apply { isAccessible = true }
        private val load = dex.findMethod {
            matcher {
                usingEqStrings("MicroMsg.EmojiLoader", "load emoji file ")
                paramTypes("com.tencent.mm.storage.emotion.EmojiInfo", "boolean", null)
            }
        }.singleOrNull()?.getMethodInstance(loader)?.apply { isAccessible = true }

        fun read(md5: String): ByteArray? {
            val record = record(md5) ?: return null
            return (decode.invoke(getEncryptor.invoke(null), record) as? ByteArray)
                ?.takeIf { it.size in 1..MAX_INPUT }
        }

        fun request(md5: String): Boolean {
            val method = load ?: return false
            val record = record(md5) ?: return false
            val type = method.declaringClass
            val receiver = if (Modifier.isStatic(method.modifiers)) null else {
                type.declaredFields.first { Modifier.isStatic(it.modifiers) && it.type == type }
                    .apply { isAccessible = true }.get(null)
            }
            val callback = method.parameterTypes[2]
            val listener = Proxy.newProxyInstance(callback.classLoader, arrayOf(callback)) { proxy, member, args ->
                when (member.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "ShadeReplyStickerCallback"
                    else -> null
                }
            }
            method.invoke(receiver, record, true, listener)
            return true
        }

        private fun record(md5: String) = getInfo.invoke(getStorage.invoke(getManager.invoke(null)), md5)

        private fun singleton(type: Class<*>): Method = type.declaredMethods.first {
            Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == type
        }.apply { isAccessible = true }
    }

    companion object {
        private const val MAX_INPUT = 8 * 1024 * 1024
        private val worker = Executors.newFixedThreadPool(2)
        private val previewLock = Any()
        private val ANIMATED_FORMATS = setOf("image/gif", "image/webp")
        private val md5Pattern = Regex("[a-fA-F0-9]{32}")
        private val stickerAttr = Regex("""\bmd5=["']([a-fA-F0-9]{32})["']""")
        private val stickerTag = Regex("""<md5>\s*([a-fA-F0-9]{32})\s*</md5>""")

        internal fun stickerId(text: String): String? =
            text.trim().takeIf(md5Pattern::matches)
                ?: (stickerAttr.find(text) ?: stickerTag.find(text))?.groupValues?.get(1)

        private fun mime(b: ByteArray): String? = when {
            b.size < 12 -> null
            b[0] == 0xff.toByte() && b[1] == 0xd8.toByte() -> "image/jpeg"
            b[0] == 0x89.toByte() && b.copyOfRange(1, 4).decodeToString() == "PNG" -> "image/png"
            b.copyOfRange(0, 3).decodeToString() == "GIF" -> "image/gif"
            b.copyOfRange(0, 4).decodeToString() == "RIFF" &&
                b.copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
            else -> null
        }
    }
}
