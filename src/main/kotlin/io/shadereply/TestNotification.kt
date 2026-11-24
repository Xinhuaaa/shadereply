package io.shadereply

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle

internal object TestNotification {
    private const val CHANNEL = "test_notification"
    private const val ID = 1
    const val REPLY = "io.shadereply.TEST_NOTIFICATION_REPLY"
    const val TEXT = "text"
    private val sender = Person.Builder().setName("ShadeReply 测试").setKey("sample").build()
    private val self = Person.Builder().setName("我").build()

    fun post(context: Context, reply: CharSequence? = null): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "测试通知", NotificationManager.IMPORTANCE_HIGH))
        if (manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return false
        val builder = if (reply == null) {
            val receiver = Intent(context, TestNotificationReceiver::class.java).setAction(REPLY)
            val inputIntent = PendingIntent.getBroadcast(context, ID, receiver, PendingIntent.FLAG_MUTABLE)
            val open = PendingIntent.getActivity(context, ID, Intent(context, GestureSettingsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.module_icon_foreground)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_send), "回复", inputIntent,
                ).addRemoteInput(RemoteInput.Builder(TEXT).setLabel("输入测试回复").build())
                    .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                    .setAuthenticationRequired(true).setAllowGeneratedReplies(false).build())
                .setStyle(Notification.MessagingStyle(self)
                    .addMessage("这是一条本机测试消息，可以长按或下拉试试。", System.currentTimeMillis(), sender))
        } else {
            val old = manager.activeNotifications.firstOrNull { it.id == ID && it.tag == null }?.notification
                ?: return false
            val style = Notification.MessagingStyle(self)
            old.extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
                .orEmpty().takeLast(6).forEach { message ->
                    val text = message.getCharSequence("text") ?: return@forEach
                    style.addMessage(text, message.getLong("time"), message.getParcelable("sender_person", Person::class.java))
                }
            style.addMessage(Notification.MessagingStyle.Message(reply, System.currentTimeMillis(), null as Person?))
            Notification.Builder.recoverBuilder(context, old).setStyle(style).setOnlyAlertOnce(true)
                .setRemoteInputHistory(null)
        }
        manager.notify(ID, builder.build())
        return true
    }
}

class TestNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TestNotification.REPLY) return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(TestNotification.TEXT)
        if (!reply.isNullOrBlank()) TestNotification.post(context, reply)
    }
}
