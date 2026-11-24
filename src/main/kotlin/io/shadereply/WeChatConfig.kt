package io.shadereply

import android.content.Context
import android.content.SharedPreferences

internal data class WeChatConfig(
    val enabled: Boolean = false,
    val bubbles: Boolean = false,
    val hideRead: Boolean = false,
    val clearHistory: Boolean = false,
    val imageMode: Int = IMAGE_THUMB,
    val waitSticker: Boolean = true,
    val voiceText: Boolean = false,
) {
    fun save(prefs: SharedPreferences): Boolean = prefs.edit()
        .putBoolean("enabled", enabled)
        .putBoolean("bubbles", bubbles)
        .putBoolean("hide_read", hideRead)
        .putBoolean("clear_history", clearHistory)
        .putInt("image_mode", imageMode)
        .putBoolean("wait_sticker", waitSticker)
        .putBoolean("voice_text", voiceText)
        .commit()

    companion object {
        const val GROUP = "wechat_notifications"
        const val IMAGE_OFF = 0
        const val IMAGE_THUMB = 1
        const val IMAGE_LARGE = 2

        fun local(context: Context): SharedPreferences =
            context.getSharedPreferences(GROUP, Context.MODE_PRIVATE)

        fun read(prefs: SharedPreferences) = WeChatConfig(
            prefs.getBoolean("enabled", false),
            prefs.getBoolean("bubbles", false),
            prefs.getBoolean("hide_read", false),
            prefs.getBoolean("clear_history", false),
            prefs.getInt("image_mode", IMAGE_THUMB).coerceIn(IMAGE_OFF, IMAGE_LARGE),
            prefs.getBoolean("wait_sticker", true),
            prefs.getBoolean("voice_text", false),
        )
    }
}
