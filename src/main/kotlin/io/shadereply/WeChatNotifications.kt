package io.shadereply

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.UUID

internal object WeChatNotifications {
    const val PACKAGE = "com.tencent.mm"
    private const val TAG = "ShadeReplyWeChat"
    private const val CHANNEL = "message_channel_new_id"
    private const val RECEIVER = "com.tencent.mm.booter.NotifyReceiver"
    private const val CHAT = "com.tencent.mm.ui.chatting.ChattingUI"
    private const val ACTION = "io.shadereply.notification."
    private const val REPLY = ACTION + "REPLY"
    private const val READ = ACTION + "READ"
    private const val DISMISS = ACTION + "DISMISS"
    private const val KEY_TEXT = ACTION + "TEXT"
    private const val REVISION = ACTION + "REVISION"
    private const val CONVERSATION = ACTION + "CONVERSATION"
    private const val SHORTCUT_ICON_VERSION = ACTION + "ICON_VERSION"
    private const val HISTORY_LIMIT = 7
    private val messageCount = Regex("""^\[\d+条]\s*""")
    private val groupSender = Regex("""^(.{1,80}?)[：:]\s+([\s\S]+)$""")
    private val groupAccount = Regex("""^([a-zA-Z0-9_@.\-]{1,100}):\n""")
    private val main = Handler(Looper.getMainLooper())
    private val currentTalker = ThreadLocal<Incoming>()
    private val history = LinkedHashMap<String, History>()
    private val pendingMedia = LinkedHashMap<String, ArrayDeque<PendingMedia>>()
    private val avatars = LinkedHashMap<String, Icon>()
    private val stateLock = Any()
    private val self = Person.Builder().setName("我").setKey("shadereply.self").build()
    private var process = ""
    private var attached = false
    private var loading = false
    private var loaded = false
    private var app: Application? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var settings = WeChatConfig()
    @Volatile private var reply: WeChatMethods.Reply? = null
    @Volatile private var reading: WeChatMethods.Reading? = null
    @Volatile private var avatar: Method? = null
    @Volatile private var avatarLoader: Any? = null
    @Volatile private var media: WeChatMedia? = null

    private data class Incoming(val talker: String, val content: String, val type: Int, val message: Any?)
    private data class History(val revision: String, val messages: List<Notification.MessagingStyle.Message>)
    private data class PendingMedia(val instance: Any, val body: String, val type: Int, val captured: Long)

    @JvmStatic
    fun onModuleLoaded(processName: String) {
        process = processName
    }

    @JvmStatic
    fun onPackageReady(module: XposedModule, loader: ClassLoader) {
        if (attached || (process != PACKAGE && process != "$PACKAGE:push")) return
        runCatching {
            val prefs = module.getRemotePreferences(WeChatConfig.GROUP)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                main.post { updateSettings(module, prefs, loader) }
            }
            preferenceListener = listener
            prefs.registerOnSharedPreferenceChangeListener(listener)
            settings = WeChatConfig.read(prefs)
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            module.hook(attach).intercept { chain ->
                val result = chain.proceed()
                val candidate = chain.thisObject as Application
                if (candidate.packageName == PACKAGE && app == null) {
                    app = candidate
                    updateSettings(module, prefs, candidate.classLoader)
                }
                result
            }
            if (process == PACKAGE) hookActions(module, loader)
            attached = true
            module.log(Log.INFO, TAG, "WeChat notification settings loaded ($process), enabled=${settings.enabled}", null)
        }.onFailure { module.log(Log.WARN, TAG, "Could not attach to WeChat ($process)", it) }
    }

    private fun updateSettings(module: XposedModule, prefs: SharedPreferences, loader: ClassLoader) {
        settings = WeChatConfig.read(prefs)
        if (!settings.enabled) {
            synchronized(stateLock) {
                history.clear()
                pendingMedia.clear()
            }
            return
        }
        val context = app ?: return
        if (loading || loaded) return
        loading = true
        Thread({
            val installed = runCatching {
                System.loadLibrary("dexkit")
                DexKitBridge.create(context.applicationInfo.sourceDir).use { dex ->
                    val deal = WeChatMethods.dealNotify(dex, loader)
                        ?: error("Could not identify the WeChat notification method")
                    avatar = runCatching { WeChatMethods.avatar(dex, loader) }.getOrNull()
                    // Push posts the same actions but Android delivers them to the main process.
                    reply = runCatching { WeChatMethods.reply(dex, loader) }.getOrNull()
                    reading = runCatching { WeChatMethods.reading(dex, loader) }.getOrNull()
                    hookBubbleCancellation(module)
                    hookNotifications(module, deal)
                    media = runCatching {
                        WeChatMedia(dex, loader) { message, error -> module.log(Log.WARN, TAG, message, error) }
                            .also { hookMedia(module, it) }
                    }
                        .onFailure { module.log(Log.WARN, TAG, "Message preview hooks unavailable", it) }
                        .getOrNull()
                    if (process == PACKAGE) reading?.let { read ->
                        module.hook(read.update).intercept { chain ->
                            val result = chain.proceed()
                            if (result != false) synchronized(stateLock) { history.remove(chain.getArg(0)) }
                            result
                        }
                    }
                    module.log(Log.INFO, TAG,
                        "WeChat hooks ready ($process), reply=${reply != null}, read=${reading != null}, avatar=${avatar != null}", null)
                }
            }.onFailure { module.log(Log.WARN, TAG, "Could not load WeChat notification features ($process)", it) }.isSuccess
            main.post {
                loading = false
                loaded = installed
            }
        }, "ShadeReplyWeChatSetup").start()
    }

    private fun hookMedia(module: XposedModule, media: WeChatMedia) {
        module.hook(media.insert).intercept { chain ->
            val result = chain.proceed()
            if (settings.enabled && result is Long && result >= 0) runCatching {
                val message = chain.getArg(0) ?: return@runCatching
                if (media.sent.getInt(message) == 0 && media.supports(message)) {
                    val talker = media.talker.get(message) as? String ?: return@runCatching
                    val body = media.content.get(message) as? String ?: return@runCatching
                    synchronized(stateLock) {
                        val now = SystemClock.elapsedRealtime()
                        val queue = pendingMedia.getOrPut(talker) { ArrayDeque() }
                        queue.removeAll { now - it.captured > 8_000 }
                        queue.addLast(PendingMedia(message, mediaBody(body), media.typeOf(message), now))
                        if (queue.size > 6) queue.removeFirst()
                        if (pendingMedia.size > 32) pendingMedia.remove(pendingMedia.keys.first())
                    }
                }
            }
            result
        }
    }

    private fun hookNotifications(module: XposedModule, deal: Method) {
        val messageField = deal.parameterTypes.first().declaredFields.firstOrNull {
            it.type.name.startsWith("com.tencent.mm.storage.") &&
                generateSequence(it.type) { type -> type.superclass }.any { type ->
                    type.declaredFields.any { member -> member.name == "field_msgSvrId" }
                }
        }?.apply { isAccessible = true }
        module.hook(deal).intercept { chain ->
            val parent = currentTalker.get()
            val talker = chain.getArg(1) as? String
            if (settings.enabled && !talker.isNullOrBlank()) {
                val message = runCatching { messageField?.get(chain.getArg(0)) }.getOrNull()
                currentTalker.set(Incoming(talker, chain.getArg(2) as? String ?: "", chain.getArg(3) as? Int ?: 0, message))
            }
            try {
                chain.proceed()
            } finally {
                if (parent == null) currentTalker.remove() else currentTalker.set(parent)
            }
        }
        val notify = NotificationManager::class.java.getDeclaredMethod(
            "notify", String::class.java, Int::class.javaPrimitiveType, Notification::class.java)
        module.hook(notify).intercept { chain ->
            val incoming = currentTalker.get()
            val config = settings
            val context = app
            val notification = chain.getArg(2) as Notification
            if (!config.enabled || incoming == null || context == null ||
                notification.channelId != CHANNEL || notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 ||
                notification.extras.containsKey(REVISION) ||
                notification.extras.containsKey("wekite.notification.CHAT_INTENT") ||
                notification.extras.getString(Notification.EXTRA_TEMPLATE)?.endsWith("MessagingStyle") == true
            ) return@intercept chain.proceed()
            val tag = chain.getArg(0) as? String
            val id = chain.getArg(1) as Int
            val result = runCatching { enhance(module, context, config, notification, incoming, tag, id) }
                .onFailure { Log.w(TAG, "Notification enhancement skipped (${it.javaClass.simpleName})") }
                .getOrNull() ?: return@intercept chain.proceed()
            val posted = chain.proceed(arrayOf(tag, id, result))
            runCatching { addMediaPreview(module, context, config, incoming, tag, id, result) }
            posted
        }
    }

    private fun hookBubbleCancellation(module: XposedModule) {
        val cancel = NotificationManager::class.java.getDeclaredMethod(
            "cancelAsUser", String::class.java, Int::class.javaPrimitiveType, UserHandle::class.java)
        module.hook(cancel).intercept { chain ->
            // WeChat clears the notification as soon as ChattingUI opens. SystemUI owns
            // dismissal once it has turned one of our conversation notifications into a bubble.
            if (settings.enabled && settings.bubbles && chain.getArg(2) == android.os.Process.myUserHandle()) {
                val manager = chain.thisObject as NotificationManager
                val notification = runCatching { active(manager, chain.getArg(0) as? String, chain.getArg(1) as Int) }
                    .getOrNull()
                if (notification != null && notification.flags and Notification.FLAG_BUBBLE != 0 &&
                    notification.extras.containsKey(REVISION)
                ) {
                    module.log(Log.INFO, TAG, "Kept an active conversation bubble during WeChat notification cleanup", null)
                    return@intercept null
                }
            }
            chain.proceed()
        }
    }

    private fun addMediaPreview(
        module: XposedModule, context: Context, config: WeChatConfig,
        incoming: Incoming, tag: String?, id: Int, posted: Notification,
    ) {
        val resolver = media ?: return
        if (!resolver.supportsType(incoming.type)) return
        val body = mediaBody(incoming.content)
        val message = incoming.message?.takeIf {
            resolver.talker.get(it) == incoming.talker && resolver.typeOf(it) == incoming.type &&
                resolver.sent.getInt(it) == 0
        } ?: synchronized(stateLock) {
            val queue = pendingMedia[incoming.talker] ?: return@synchronized null
            val now = SystemClock.elapsedRealtime()
            queue.removeAll { now - it.captured > 8_000 }
            val matches = queue.filter { it.type == incoming.type }
            val selected = matches.firstOrNull { it.body == body }
                ?: matches.filter { now - it.captured <= 2_500 }.singleOrNull()
            if (selected != null) queue.remove(selected)
            if (queue.isEmpty()) pendingMedia.remove(incoming.talker)
            selected?.instance
        }
        if (message == null && (incoming.type == 3 || WeChatMedia.stickerId(incoming.content) == null)) return
        val revision = posted.extras.getString(REVISION) ?: return
        resolver.resolve(context, incoming.type, message, incoming.content, config) { type, uri ->
            main.post {
                if (!settings.enabled) return@post
                runCatching {
                    val manager = context.getSystemService(NotificationManager::class.java)
                    val notification = current(manager, tag, id, revision) ?: return@runCatching
                    val messages = messages(notification)
                    val latest = messages.lastOrNull() ?: return@runCatching
                    if (latest.senderPerson?.key == self.key) return@runCatching
                    latest.setData(type, uri)
                    val builder = Notification.Builder.recoverBuilder(context, notification.clone())
                        .setOnlyAlertOnce(true).setStyle(Notification.MessagingStyle(self).apply {
                            setGroupConversation(notification.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION))
                            conversationTitle = notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                            messages.forEach(::addMessage)
                        })
                    synchronized(stateLock) { history[incoming.talker] = History(revision, messages) }
                    if (current(manager, tag, id, revision) != null) manager.notify(tag, id, builder.build())
                }.onFailure { module.log(Log.WARN, TAG, "Preview update skipped", it) }
            }
        }
    }

    private fun mediaBody(body: String) = groupAccount.replaceFirst(body.trim(), "").trim()

    private fun enhance(
        module: XposedModule, context: Context, config: WeChatConfig, source: Notification,
        incoming: Incoming, tag: String?, id: Int,
    ): Notification? {
        val title = source.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            ?.takeIf(String::isNotBlank) ?: return null
        val text = source.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?.takeIf(String::isNotBlank) ?: return null
        // Preserve preview privacy whenever WeChat posted a generic alert.
        if (title in setOf("微信", "WeChat", "Weixin") || source.contentIntent == null) return null
        val talker = incoming.talker
        val group = talker.endsWith("@chatroom")
        val body = messageCount.replaceFirst(text, "")
        val sender = if (group) groupSender.matchEntire(body) else null
        val senderId = if (group) groupAccount.find(incoming.content)?.groupValues?.get(1) else talker
        val icon = loadAvatar(context, talker) ?: source.getLargeIcon()
        val participant = Person.Builder().setName(sender?.groupValues?.get(1) ?: title)
            .apply {
                senderId?.let(::setKey)
                (if (group) senderId?.let { loadAvatar(context, it) } else icon)?.let(::setIcon)
            }.build()
        val now = System.currentTimeMillis()
        val whenPosted = source.`when`.takeIf { it in 1..now + 60_000 } ?: now
        val message = Notification.MessagingStyle.Message(sender?.groupValues?.get(2) ?: body, whenPosted, participant)
        val conversation = conversationId(talker)
        val revision = UUID.randomUUID().toString()
        val manager = context.getSystemService(NotificationManager::class.java)
        val previous = manager.activeNotifications.firstOrNull { it.tag == tag && it.id == id }?.notification
        val previousForChat = previous?.takeIf { it.extras.getString(CONVERSATION) == conversation }
        val entries = synchronized(stateLock) {
            // Read the active notification too when Android switches between WeChat main and :push.
            val stored = history[talker]?.takeIf { it.revision == previousForChat?.extras?.getString(REVISION) }
            val old = stored?.messages ?: previousForChat?.let(::messages).orEmpty()
            val newest = old.lastOrNull()
            val duplicate = newest?.timestamp == message.timestamp && newest.text == message.text &&
                newest.senderPerson?.key == message.senderPerson?.key
            val messages = if (duplicate) old else (old + message).takeLast(HISTORY_LIMIT)
            history[talker] = History(revision, messages)
            if (history.size > 64) history.remove(history.keys.first())
            messages
        }
        return Notification.Builder.recoverBuilder(context, source.clone()).apply {
            setStyle(Notification.MessagingStyle(self).apply {
                setGroupConversation(group)
                if (group) setConversationTitle(title)
                entries.forEach(::addMessage)
            })
            icon?.let(::setLargeIcon)
            extras.putString(CONVERSATION, conversation)
            extras.putString(REVISION, revision)
            setDeleteIntent(actionIntent(context, DISMISS, talker, tag, id, revision, source.deleteIntent))
            val actions = source.actions.orEmpty().toMutableList()
            if (reply != null && actions.none { action -> action.remoteInputs.orEmpty().any { it.allowFreeFormInput } }) {
                actions += Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_send), "回复",
                    actionIntent(context, REPLY, talker, tag, id, revision))
                    .addRemoteInput(RemoteInput.Builder(KEY_TEXT).setLabel("输入回复内容").build())
                    .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                    .setAllowGeneratedReplies(false)
                    .setAuthenticationRequired(true)
                    .build()
            }
            if (reading != null && !config.hideRead) {
                actions += Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_view), "标为已读",
                    actionIntent(context, READ, talker, tag, id, revision))
                    .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .setAuthenticationRequired(true)
                    .build()
            }
            setActions(*actions.toTypedArray())
            addConversationShortcut(module, context, conversation, talker, title, group, participant, icon, config.bubbles)
        }.build()
    }

    private fun Notification.Builder.addConversationShortcut(
        module: XposedModule, context: Context, id: String, talker: String, title: String, group: Boolean,
        participant: Person, avatar: Icon?, bubbles: Boolean,
    ) {
        runCatching {
            val manager = context.getSystemService(ShortcutManager::class.java)
            val activity = ComponentName(PACKAGE, "$PACKAGE.ui.LauncherUI")
            val chat = Intent(Intent.ACTION_VIEW)
                .setClassName(PACKAGE, CHAT)
                .setData(Uri.parse("shadereply://conversation/$id"))
                .putExtra("Chat_User", talker)
            val label = title.take(80)
            val existing = manager.getShortcuts(ShortcutManager.FLAG_MATCH_DYNAMIC or ShortcutManager.FLAG_MATCH_CACHED)
                .firstOrNull { it.id == id }
            // Refresh existing shortcuts in place so Android keeps the user's per-chat setting.
            val iconVersion = if (avatar == null) 1 else 2
            if (existing?.isEnabled != true || existing.shortLabel?.toString() != label ||
                (existing.extras?.getInt(SHORTCUT_ICON_VERSION) ?: 0) < iconVersion
            ) {
                val icon = runCatching { adaptiveShortcutIcon(context, avatar) }
                    .onFailure { module.log(Log.WARN, TAG, "Adaptive conversation avatar unavailable", it) }
                    .getOrElse { Icon.createWithResource(context, context.applicationInfo.icon) }
                val person = if (group) Person.Builder().setName(title).setKey(id).setIcon(icon).build() else participant
                val shortcut = ShortcutInfo.Builder(context, id)
                    .setShortLabel(label).setIcon(icon).setActivity(activity)
                    .setIntent(chat).setPerson(person).setLongLived(true)
                    .setExtras(PersistableBundle().apply { putInt(SHORTCUT_ICON_VERSION, iconVersion) }).build()
                if (existing?.isEnabled == true) manager.updateShortcuts(listOf(shortcut))
                else manager.pushDynamicShortcut(shortcut)
            }
            setShortcutId(id)
            if (bubbles) {
                setBubbleMetadata(Notification.BubbleMetadata.Builder(id)
                    .setDesiredHeight(560)
                    .setAutoExpandBubble(false)
                    .setSuppressNotification(false)
                    .build())
            }
        }.onFailure { module.log(Log.WARN, TAG, "Conversation shortcut unavailable", it) }
    }

    private fun adaptiveShortcutIcon(context: Context, avatar: Icon?): Icon {
        val appIcon = Icon.createWithResource(context, context.applicationInfo.icon)
        val icon = avatar ?: appIcon
        val loaded = icon.loadDrawable(context) ?: return appIcon
        val drawable = if (loaded is BitmapDrawable && loaded.bitmap.config == Bitmap.Config.HARDWARE) {
            BitmapDrawable(context.resources, loaded.bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return appIcon)
        } else loaded
        if (drawable is AdaptiveIconDrawable) return icon
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val size = 192
        val scale = size.toFloat() / minOf(width, height)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate((size - width * scale) / 2f, (size - height * scale) / 2f)
        canvas.scale(scale, scale)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return Icon.createWithAdaptiveBitmap(bitmap)
    }

    private fun loadAvatar(context: Context, id: String): Icon? = synchronized(avatars) {
        avatars[id]?.let { return it }
        val method = avatar ?: return null
        runCatching {
            val loader = avatarLoader ?: method.declaringClass.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }.newInstance(context).also { avatarLoader = it }
            val bitmap = method.invoke(loader, context, id, "") as? Bitmap ?: return null
            Icon.createWithBitmap(bitmap).also {
                avatars[id] = it
                if (avatars.size > 64) avatars.remove(avatars.keys.first())
            }
        }.getOrNull()
    }

    private fun hookActions(module: XposedModule, loader: ClassLoader) {
        val receiver = loader.loadClass(RECEIVER).getDeclaredMethod("onReceive", Context::class.java, Intent::class.java)
        module.hook(receiver).intercept { chain ->
            val intent = chain.getArg(1) as Intent
            if (intent.action !in setOf(REPLY, READ, DISMISS)) return@intercept chain.proceed()
            val context = chain.getArg(0) as Context
            val talker = intent.getStringExtra("talker") ?: return@intercept null
            val revision = intent.getStringExtra(REVISION) ?: return@intercept null
            val tag = intent.getStringExtra("tag")
            val id = intent.getIntExtra("id", 0)
            if (intent.action == DISMISS) {
                synchronized(stateLock) {
                    if (history[talker]?.revision == revision) history.remove(talker)
                }
                runCatching { intent.getParcelableExtra("original", PendingIntent::class.java)?.send() }
                return@intercept null
            }
            if (!settings.enabled) return@intercept null
            val text = if (intent.action == REPLY) {
                RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()
                    ?.takeIf(String::isNotBlank) ?: run {
                        module.log(Log.WARN, TAG, "WeChat reply arrived without remote input", null)
                        return@intercept null
                    }
            } else null
            if (intent.data?.lastPathSegment != revision) return@intercept null
            val manager = context.getSystemService(NotificationManager::class.java)
            if (text == null && current(manager, tag, id, revision) == null) return@intercept null
            val pending = (chain.thisObject as android.content.BroadcastReceiver).goAsync()
            val deadline = SystemClock.uptimeMillis() + 5_000
            if (text != null) module.log(Log.INFO, TAG, "Received WeChat notification reply", null)
            performAction(module, context, manager, tag, id, revision, talker, text, deadline, pending::finish)
            null
        }
    }

    private fun performAction(
        module: XposedModule, context: Context, manager: NotificationManager, tag: String?, id: Int,
        revision: String, talker: String, text: String?, deadline: Long, finish: () -> Unit,
    ) {
        if (!settings.enabled || (text == null && current(manager, tag, id, revision) == null)) {
            finish()
            return
        }
        val result = runCatching {
            if (text == null) reading?.mark(talker) else reply?.send(talker, text)
        }.onFailure { module.log(Log.WARN, TAG, "WeChat notification action failed", it) }
            .getOrDefault(WeChatMethods.ActionResult.FAILED)
        if ((result == null || result == WeChatMethods.ActionResult.NOT_READY) && SystemClock.uptimeMillis() < deadline) {
            main.postDelayed({
                performAction(module, context, manager, tag, id, revision, talker, text, deadline, finish)
            }, 250)
            return
        }
        try {
            if (text != null) {
                module.log(Log.INFO, TAG, "WeChat notification reply queue result: $result", null)
            }
            val latest = if (text == null) current(manager, tag, id, revision) else {
                // A new message can replace the row while the user is typing. The reply still
                // belongs to the original conversation, even if the row is no longer present.
                val conversation = conversationId(talker)
                active(manager, tag, id)?.takeIf { it.extras.getString(CONVERSATION) == conversation }
            } ?: return
            val latestRevision = latest.extras.getString(REVISION) ?: return
            if (result != WeChatMethods.ActionResult.DONE) {
                manager.notify(tag, id, Notification.Builder.recoverBuilder(context, latest.clone())
                    .setOnlyAlertOnce(true).build())
            } else if (text == null) {
                manager.cancel(tag, id)
            } else {
                val prior = synchronized(stateLock) {
                    history[talker]?.takeIf { it.revision == latestRevision }?.messages
                } ?: messages(latest)
                val messages = appendReply(prior, text, settings.clearHistory)
                runCatching { reading?.mark(talker) }
                val afterRead = active(manager, tag, id)
                if (afterRead != null && afterRead.extras.getString(REVISION) != latestRevision) return
                val builder = Notification.Builder.recoverBuilder(context, latest.clone())
                    .setOnlyAlertOnce(true).setAutoCancel(true).setStyle(Notification.MessagingStyle(self).apply {
                        setGroupConversation(latest.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION))
                        conversationTitle = latest.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                        messages.forEach(::addMessage)
                    })
                builder.setRemoteInputHistory(null)
                if (afterRead == null && latest.group == null && latest.bubbleMetadata == null) {
                    builder.setGroup("shadereply.replied").setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                }
                synchronized(stateLock) { history[talker] = History(latestRevision, messages) }
                manager.notify(tag, id, builder.build())
            }
        } finally {
            finish()
        }
    }

    private fun current(manager: NotificationManager, tag: String?, id: Int, revision: String): Notification? =
        active(manager, tag, id)?.takeIf { it.extras.getString(REVISION) == revision }

    private fun active(manager: NotificationManager, tag: String?, id: Int): Notification? =
        manager.activeNotifications.firstOrNull { it.tag == tag && it.id == id }
            ?.notification

    private fun appendReply(
        previous: List<Notification.MessagingStyle.Message>, text: String, clearReceived: Boolean,
    ): List<Notification.MessagingStyle.Message> {
        val kept = if (clearReceived) previous.filter { it.senderPerson?.key == self.key } else previous
        return (kept + Notification.MessagingStyle.Message(text, System.currentTimeMillis(), self))
            .takeLast(HISTORY_LIMIT)
    }

    private fun messages(notification: Notification): List<Notification.MessagingStyle.Message> =
        notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
            .orEmpty().mapNotNull { item ->
                val text = item.getCharSequence("text") ?: return@mapNotNull null
                Notification.MessagingStyle.Message(
                    text, item.getLong("time"), item.getParcelable("sender_person", Person::class.java),
                ).apply {
                    val mime = item.getString("type")
                    val uri = item.getParcelable("uri", Uri::class.java)
                    if (mime != null && uri != null) setData(mime, uri)
                }
            }

    private fun actionIntent(
        context: Context, action: String, talker: String, tag: String?, id: Int, revision: String,
        original: PendingIntent? = null,
    ): PendingIntent {
        val intent = Intent(action).setComponent(ComponentName(PACKAGE, RECEIVER))
            .setData(Uri.parse("shadereply://notification/$revision"))
            .putExtra("talker", talker).putExtra("tag", tag).putExtra("id", id)
            .putExtra(REVISION, revision).putExtra("original", original)
        val flags = if (action == REPLY) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private fun conversationId(talker: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(talker.toByteArray())
        return "shadereply." + digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
