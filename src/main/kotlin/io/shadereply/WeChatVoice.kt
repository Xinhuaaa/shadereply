package io.shadereply

import android.os.Handler
import android.os.Looper

internal class WeChatVoice(loader: ClassLoader) {
    private val eventType = loader.loadClass("com.tencent.mm.autogen.events.ExtTranslateVoiceEvent")
    private val constructor = eventType.getDeclaredConstructor()
    private val publish = eventType.getMethod("e")
    private val request = eventType.getField("g")
    private val result = eventType.getField("h")
    private val fileName = request.type.getField("a")
    private val messageId = request.type.getField("b")
    private val operation = request.type.getField("c")
    private val callback = request.type.getField("e")
    private val scene = request.type.getField("f")
    private val conversation = request.type.getField("g")
    private val text = result.type.getField("a")
    private val main = Handler(Looper.getMainLooper())
    private var activeEvent: Any? = null

    fun transcribe(talker: String, localId: Long, path: String?, onText: (String) -> Unit) {
        if (localId <= 0 || path.isNullOrBlank() || activeEvent != null) return
        val event = constructor.newInstance()
        val args = request.get(event)
        fileName.set(args, path)
        messageId.set(args, localId.toString())
        conversation.set(args, talker)
        operation.setInt(args, 0)
        scene.setInt(args, if (talker.endsWith("@chatroom")) 7 else 2)
        callback.set(args, Runnable {
            main.post {
                if (activeEvent !== event) return@post
                activeEvent = null
                val transcript = runCatching { text.get(result.get(event)) as? String }
                    .getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@post
                onText(transcript)
            }
        })
        activeEvent = event
        try {
            if (publish.invoke(event) != true) activeEvent = null
        } finally {
            main.postDelayed({ if (activeEvent === event) activeEvent = null }, 30_000)
        }
    }
}
