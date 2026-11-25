package io.shadereply

import android.app.Application
import android.app.AppComponentFactory
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
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
    private const val TRANSCRIBE = ACTION + "TRANSCRIBE"
    private const val KEY_TEXT = ACTION + "TEXT"
    private const val REVISION = NotificationDiagnostics.REVISION
    private const val CONVERSATION = ACTION + "CONVERSATION"
    private const val MESSAGE_ID = NotificationDiagnostics.MESSAGE_ID
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
    @Volatile private var app: Application? = null
    @Volatile private var notificationEntryReady = false
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var settings = WeChatConfig()
    @Volatile private var reply: WeChatMethods.Reply? = null
    @Volatile private var reading: WeChatMethods.Reading? = null
    @Volatile private var avatar: Method? = null
    @Volatile private var avatarLoader: Any? = null
    @Volatile private var media: WeChatMedia? = null
    @Volatile private var voice: WeChatVoice? = null

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
            hookNotificationPosts(module)
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
                if (NotificationDiagnostics.isEnabled()) {
                    val version = runCatching {
                        context.packageManager.getPackageInfo(PACKAGE, 0).longVersionCode
                    }.getOrNull()
                    traceNotification(module, "wechat_hook_scan_started", "version_code=${version ?: "unknown"}")
                }
                System.loadLibrary("dexkit")
                DexKitBridge.create(context.applicationInfo.sourceDir).use { dex ->
                    val deal = WeChatMethods.dealNotify(dex, loader)
                        ?: error("Could not identify the WeChat notification method")
                    avatar = runCatching { WeChatMethods.avatar(dex, loader) }.getOrNull()
                    // Push posts the same actions but Android delivers them to the main process.
                    reply = runCatching { WeChatMethods.reply(dex, loader) }.getOrNull()
                    reading = runCatching { WeChatMethods.reading(dex, loader) }.getOrNull()
                    hookBubbleCancellation(module)
                    hookNotificationEntry(module, deal)
                    notificationEntryReady = true
                    traceNotification(module, "wechat_hook_entry_installed", "")
                    media = runCatching {
                        WeChatMedia(dex, loader) { message, error -> module.log(Log.WARN, TAG, message, error) }
                            .also { hookMedia(module, it) }
                    }
                        .onFailure { module.log(Log.WARN, TAG, "Message preview hooks unavailable", it) }
                        .getOrNull()
                    if (process == PACKAGE) {
                        voice = runCatching {
                            val flags = android.content.pm.PackageManager.PackageInfoFlags.of(0)
                            if (context.packageManager.getPackageInfo(PACKAGE, flags).longVersionCode == 3180L)
                                WeChatVoice(loader) else null
                        }
                            .onFailure { module.log(Log.WARN, TAG, "Voice transcription unavailable", it) }.getOrNull()
                    }
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
                val type = media.typeOf(message)
                if (media.sent.getInt(message) == 0 && (media.supportsType(type) || type == 34 && settings.voiceText)) {
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

    private fun hookNotificationEntry(module: XposedModule, deal: Method) {
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
                traceNotification(module, "wechat_notify_entry", "context_captured=true")
            } else {
                traceNotification(module, "wechat_notify_entry",
                    if (settings.enabled) "context_captured=false reason=missing_conversation"
                    else "context_captured=false reason=disabled")
            }
            try {
                chain.proceed()
            } finally {
                if (parent == null) currentTalker.remove() else currentTalker.set(parent)
            }
        }
    }

    private fun hookNotificationPosts(module: XposedModule) {
        val notify = NotificationManager::class.java.getDeclaredMethod(
            "notify", String::class.java, Int::class.javaPrimitiveType, Notification::class.java)
        module.hook(notify).intercept { chain ->
            val incoming = currentTalker.get()
            val config = settings
            val context = app
            val notification = chain.getArg(2) as Notification
            if (notification.extras.containsKey(REVISION)) return@intercept chain.proceed()
            val skip = when {
                notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 -> "group_summary"
                notification.channelId != CHANNEL -> "other_channel"
                !config.enabled -> "disabled"
                context == null -> "application_not_attached"
                notification.extras.containsKey("wekite.notification.CHAT_INTENT") -> "wekite_already_enhanced"
                notification.extras.getString(Notification.EXTRA_TEMPLATE)?.endsWith("MessagingStyle") == true ->
                    "existing_messaging_style"
                incoming == null && !notificationEntryReady -> "entry_hook_not_ready"
                incoming == null -> "entry_context_missing"
                else -> null
            }
            if (skip != null) {
                traceNotification(module, "wechat_notify_skipped", "reason=$skip")
                return@intercept chain.proceed()
            }
            checkNotNull(incoming)
            checkNotNull(context)
            val tag = chain.getArg(0) as? String
            val id = chain.getArg(1) as Int
            val result = runCatching { enhance(module, context, config, notification, incoming, tag, id) }
                .onFailure { Log.w(TAG, "Notification enhancement skipped (${it.javaClass.simpleName})") }
                .getOrNull()
            if (result == null) {
                traceNotification(module, "wechat_notify_skipped", "reason=enhancement_unavailable")
                return@intercept chain.proceed()
            }
            val posted = chain.proceed(arrayOf(tag, id, result))
            if (NotificationDiagnostics.isEnabled()) {
                val replyAvailable = result.actions.orEmpty().any { action ->
                    action.remoteInputs.orEmpty().any { it.allowFreeFormInput }
                }
                traceNotification(module, "wechat_notify_enhanced", "type=${incoming.type} reply_action=$replyAvailable",
                    NotificationDiagnostics.revision(result))
            }
            if (incoming.type == 3 || incoming.type == 34 || incoming.type == 47 || incoming.type == 1048625) {
                runCatching { addMediaPreview(module, context, config, incoming, tag, id, result) }
                    .onFailure { NotificationDiagnostics.warn(module, NotificationDiagnostics.revision(result),
                        "media_failed", "error=${NotificationDiagnostics.errorType(it)}") }
            }
            posted
        }
    }

    private fun traceNotification(module: XposedModule, stage: String, detail: String, trace: String? = null) {
        if (NotificationDiagnostics.isEnabled()) {
            NotificationDiagnostics.record(module, trace, stage, "process=$process $detail")
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
        val messageId = messages(posted).lastOrNull()?.extras?.getString(MESSAGE_ID)
        fun diagnostic(stage: String, detail: String) = NotificationDiagnostics.record(
            module, messageId ?: NotificationDiagnostics.revision(posted), stage, "type=${incoming.type} $detail",
        )
        diagnostic("notification_posted", "source=wechat")
        val resolver = media
        val skip = when {
            messageId == null -> "message_id_missing"
            resolver == null -> "resolver_unavailable"
            incoming.type == 3 && config.imageMode == WeChatConfig.IMAGE_OFF -> "image_preview_disabled"
            incoming.type == 34 && !config.voiceText -> "voice_transcription_disabled"
            incoming.type != 34 && !resolver.supportsType(incoming.type) -> "lookup_unavailable"
            else -> null
        }
        if (skip != null) {
            diagnostic("media_skipped", "reason=$skip")
            return
        }
        requireNotNull(messageId)
        requireNotNull(resolver)
        fun attachment(type: String, uri: Uri) {
            diagnostic("media_uri_created", "format=$type")
            updatePostedMessage(module, context, incoming.talker, tag, id, messageId) {
                if (incoming.type == 3 && settings.imageMode == WeChatConfig.IMAGE_OFF) return@updatePostedMessage it
                it.extras.putBoolean(NotificationMedia.PREVIEW, true)
                it.setData(type, uri)
            }
        }
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
        if (incoming.type == 34) {
            if (message != null) {
                diagnostic("voice_requested", "source=wechat")
                val request = Intent(TRANSCRIBE).setComponent(ComponentName(PACKAGE, RECEIVER))
                    .putExtra("talker", incoming.talker).putExtra("tag", tag).putExtra("id", id)
                    .putExtra(MESSAGE_ID, messageId)
                    .putExtra("local_id", resolver.localId.getLong(message))
                    .putExtra("voice_path", resolver.imagePath.get(message) as? String)
                if (process == PACKAGE) main.post { requestTranscript(module, context, request) }
                else context.sendBroadcast(request)
            } else diagnostic("media_skipped", "reason=message_record_missing")
            return
        }
        if (message == null && (incoming.type == 3 || WeChatMedia.stickerId(incoming.content) == null)) {
            diagnostic("media_skipped", "reason=message_record_missing")
            return
        }
        diagnostic("media_queued", "record_available=${message != null}")
        resolver.resolve(context, incoming.type, message, incoming.content, config, ::diagnostic,
            warning = { stage, detail -> NotificationDiagnostics.warn(module, messageId, stage, detail) },
            callback = ::attachment)
    }

    private fun requestTranscript(module: XposedModule, context: Context, intent: Intent) {
        if (!settings.enabled || !settings.voiceText) return
        val talker = intent.getStringExtra("talker") ?: return
        val messageId = intent.getStringExtra(MESSAGE_ID) ?: return
        val tag = intent.getStringExtra("tag")
        val id = intent.getIntExtra("id", 0)
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = active(manager, tag, id)
        if (notification == null || notification.extras.getString(CONVERSATION) != conversationId(talker) ||
            messages(notification).none { it.extras.getString(MESSAGE_ID) == messageId }) {
            NotificationDiagnostics.record(module, messageId, "voice_skipped", "reason=notification_changed")
            return
        }
        val transcriber = voice
        if (transcriber == null) {
            NotificationDiagnostics.record(module, messageId, "voice_skipped", "reason=transcriber_unavailable")
            return
        }
        runCatching {
            transcriber.transcribe(talker, intent.getLongExtra("local_id", 0), intent.getStringExtra("voice_path")) { transcript ->
                updatePostedMessage(module, context, talker, tag, id, messageId) { original ->
                    if (!settings.voiceText) original else Notification.MessagingStyle.Message(
                        "[语音] $transcript", original.timestamp, original.senderPerson,
                    ).apply { extras.putAll(original.extras) }
                }
            }
        }.onFailure {
            NotificationDiagnostics.warn(module, messageId, "voice_failed", "error=${NotificationDiagnostics.errorType(it)}")
        }
    }

    private fun updatePostedMessage(
        module: XposedModule, context: Context, talker: String, tag: String?, id: Int,
        messageId: String, update: (Notification.MessagingStyle.Message) -> Notification.MessagingStyle.Message,
    ) {
        fun skipped(reason: String) = NotificationDiagnostics.record(module, messageId, "media_update_skipped", "reason=$reason")
        main.post {
            if (!settings.enabled) return@post skipped("module_disabled")
            runCatching {
                val manager = context.getSystemService(NotificationManager::class.java)
                val notification = active(manager, tag, id) ?: return@runCatching skipped("row_missing")
                if (notification.extras.getString(CONVERSATION) != conversationId(talker))
                    return@runCatching skipped("row_replaced")
                val revision = notification.extras.getString(REVISION) ?: return@runCatching skipped("revision_missing")
                val messages = messages(notification).toMutableList()
                val index = messages.indexOfFirst { it.extras.getString(MESSAGE_ID) == messageId }
                if (index < 0) return@runCatching skipped("message_missing")
                messages[index] = update(messages[index])
                val builder = Notification.Builder.recoverBuilder(context, notification.clone())
                    .setOnlyAlertOnce(true).setStyle(Notification.MessagingStyle(self).apply {
                        setGroupConversation(notification.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION))
                        conversationTitle = notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                        messages.forEach(::addMessage)
                    })
                if (current(manager, tag, id, revision) != null) {
                    manager.notify(tag, id, builder.build())
                    synchronized(stateLock) { history[talker] = History(revision, messages) }
                    NotificationDiagnostics.record(module, messageId, "media_update_posted",
                        "has_attachment=${messages[index].dataUri != null} index=$index count=${messages.size}")
                } else skipped("revision_changed")
            }.onFailure {
                NotificationDiagnostics.warn(module, messageId, "media_update_failed",
                    "error=${NotificationDiagnostics.errorType(it)}")
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
        message.extras.putString(MESSAGE_ID, revision)
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
        val signature = arrayOf(ClassLoader::class.java, String::class.java, Intent::class.java)
        val factory = runCatching {
            loader.loadClass("androidx.core.app.CoreComponentFactory")
                .getMethod("instantiateReceiver", *signature)
        }.getOrElse { AppComponentFactory::class.java.getMethod("instantiateReceiver", *signature) }
        module.hook(factory).intercept { chain ->
            val intent = chain.getArg(2) as? Intent
            if (chain.getArg(1) != RECEIVER || intent?.component != ComponentName(PACKAGE, RECEIVER)) {
                return@intercept chain.proceed()
            }
            when (intent.action) {
                // Keep the non-exported manifest component so Android can start WeChat for an action.
                REPLY, READ, DISMISS, TRANSCRIBE -> object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action == TRANSCRIBE) requestTranscript(module, context, intent)
                        else receiveAction(module, this, context, intent)
                    }
                }
                else -> chain.proceed()
            }
        }
    }

    private fun receiveAction(module: XposedModule, receiver: BroadcastReceiver, context: Context, intent: Intent) {
        val talker = intent.getStringExtra("talker")
        val revision = intent.getStringExtra(REVISION)
        val tag = intent.getStringExtra("tag")
        val id = intent.getIntExtra("id", 0)
        if (talker.isNullOrBlank() || revision.isNullOrBlank() ||
            !matchesActionRoute(intent, talker, tag, id, revision)) {
            module.log(Log.WARN, TAG, "WeChat notification action arrived without valid routing data", null)
            return
        }
        if (intent.action == DISMISS) {
            synchronized(stateLock) {
                if (history[talker]?.revision == revision) history.remove(talker)
            }
            runCatching { intent.getParcelableExtra("original", PendingIntent::class.java)?.send() }
            return
        }
        if (!settings.enabled) return
        val text = if (intent.action == REPLY) {
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()
                ?.takeIf(String::isNotBlank) ?: run {
                    NotificationDiagnostics.warn(module, revision, "reply_rejected", "reason=remote_input_missing")
                    return
                }
        } else null
        val manager = context.getSystemService(NotificationManager::class.java)
        if (text == null && current(manager, tag, id, revision) == null) return
        val pending = receiver.goAsync()
        val deadline = SystemClock.uptimeMillis() + 7_000
        if (text != null) NotificationDiagnostics.record(module, revision, "reply_received", "source=wechat")
        main.post {
            performAction(module, context, manager, tag, id, revision, talker, text, deadline, pending::finish)
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
                NotificationDiagnostics.record(module, revision, "reply_queue_result", "result=${result ?: "unavailable"}")
            }
            val latest = if (text == null) current(manager, tag, id, revision) else {
                // A new message can replace the row while the user is typing. The reply still
                // belongs to the original conversation, even if the row is no longer present.
                val conversation = conversationId(talker)
                active(manager, tag, id)?.takeIf { it.extras.getString(CONVERSATION) == conversation }
            }
            if (latest == null) {
                if (text != null) NotificationDiagnostics.record(module, revision, "reply_refresh_skipped", "reason=row_changed")
                return
            }
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
                if (afterRead != null && afterRead.extras.getString(REVISION) != latestRevision) {
                    NotificationDiagnostics.record(module, revision, "reply_refresh_skipped", "reason=revision_changed")
                    return
                }
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
                NotificationDiagnostics.record(module, revision, "reply_refresh_posted",
                    "count=${messages.size} attachments=${messages.count { it.dataUri != null }}")
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
                    item.getBundle("extras")?.let(extras::putAll)
                }
            }

    private fun actionIntent(
        context: Context, action: String, talker: String, tag: String?, id: Int, revision: String,
        original: PendingIntent? = null,
    ): PendingIntent {
        val isReply = action == REPLY
        val intent = Intent(action).setComponent(ComponentName(PACKAGE, RECEIVER))
            .setData(if (isReply) replyRoute(talker, tag, id) else revisionRoute(revision))
            .putExtra("talker", talker).putExtra("tag", tag).putExtra("id", id)
            .putExtra(REVISION, revision).putExtra("original", original)
        val flags = if (isReply) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private fun matchesActionRoute(intent: Intent, talker: String, tag: String?, id: Int, revision: String): Boolean =
        // Accept revision-scoped replies already posted by older builds.
        intent.data == revisionRoute(revision) ||
            (intent.action == REPLY && intent.data == replyRoute(talker, tag, id))

    private fun revisionRoute(revision: String): Uri = Uri.parse("shadereply://notification/$revision")

    // SystemUI matches the open editor to the updated reply action with Intent.filterEquals.
    private fun replyRoute(talker: String, tag: String?, id: Int): Uri = Uri.Builder()
        .scheme("shadereply").authority("reply")
        .appendPath(conversationId(talker)).appendPath(id.toString())
        .apply { tag?.let { appendPath(conversationId(it)) } }
        .build()

    private fun conversationId(talker: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(talker.toByteArray())
        return "shadereply." + digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
