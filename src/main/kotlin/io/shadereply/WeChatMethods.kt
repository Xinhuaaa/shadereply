package io.shadereply

import android.content.Context
import android.graphics.Bitmap
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// WeChat compatibility signatures adapted from SymonChu/WeKite, GPL-3.0.
internal object WeChatMethods {
    private const val NOTIFY_MARKER =
        "jacks dealNotify, talker:%s, msgtype:%d, tipsFlag:%d, isRevokeMesasge:%B content:%s"

    fun dealNotify(dex: DexKitBridge, loader: ClassLoader): Method? = dex.findMethod {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            paramCount = 6
            usingEqStrings(NOTIFY_MARKER)
        }
    }.singleOrNull()?.resolve(loader)

    fun avatar(dex: DexKitBridge, loader: ClassLoader): Method? = dex.findMethod {
        matcher {
            paramTypes(Context::class.java, String::class.java, String::class.java)
            returnType(Bitmap::class.java)
            usingEqStrings("MicroMsg.NotificationAvatar", "wcf://avatar/")
        }
    }.singleOrNull()?.resolve(loader)

    fun reply(dex: DexKitBridge, loader: ClassLoader): Reply? {
        val sceneClass = classWithMethod(dex, null, "MicroMsg.NetSceneSendMsg", "markMsgFailed for id:%d")
            ?.getInstance(loader) ?: return null
        val sceneCtor = sceneClass.declaredConstructors
            .filter { ctor ->
                val types = ctor.parameterTypes
                types.size >= 5 && types[0] == String::class.java && types[1] == String::class.java &&
                    types[2] == Int::class.javaPrimitiveType && types[3] == Int::class.javaPrimitiveType
            }
            .minByOrNull { it.parameterCount }?.apply { isAccessible = true } ?: return null
        val queue = classWithMethod(dex, "com.tencent.mm.modelbase",
            "worker thread has not been se", "MicroMsg.NetSceneQueue") ?: return null
        val sceneBase = dex.findClass {
            matcher { usingEqStrings("scene security verification not passed, type=") }
        }.singleOrNull() ?: return null
        val owner = dex.findClass {
            matcher {
                addMethod {
                    paramCount = 4
                    usingStrings("MicroMsg.Mvvm.NetSceneObserverOwner")
                }
            }
        }.singleOrNull()
        val queueTypes = listOfNotNull(owner?.name, queue.name).distinct()
        val getters = queueTypes.flatMap { queueType ->
            dex.findMethod {
                matcher {
                    paramCount = 0
                    returnType = queueType
                }
            }.filter { Modifier.isStatic(it.modifiers) }.mapNotNull { it.resolve(loader) }
        }
        val post = dex.findMethod {
            searchPackages("com.tencent.mm.modelbase")
            matcher {
                declaredClass = queue.name
                paramTypes(sceneBase.name)
                returnType = "boolean"
                usingNumbers(0)
            }
        }.singleOrNull()?.resolve(loader) ?: return null
        if (getters.isEmpty()) return null
        return Reply(sceneCtor, getters, post)
    }

    fun reading(dex: DexKitBridge, loader: ClassLoader): Reading? {
        val conversations = dex.findClass {
            searchPackages("com.tencent.mm.storage")
            matcher { usingEqStrings("rconversation", "PRAGMA table_info( rconversation)") }
        }.singleOrNull() ?: return null
        val update = dex.findMethod {
            matcher {
                declaredClass = conversations.name
                paramTypes(String::class.java)
                usingStrings("updateUnreadByTalker %s")
            }
        }.singleOrNull()?.resolve(loader) ?: return null
        val service = dex.findClass {
            searchPackages("com.tencent.mm.plugin.messenger.foundation")
            matcher {
                addMethod { returnType { usingEqStrings("PRAGMA table_info( contact_ext )") } }
                addMethod { returnType { usingEqStrings("MicroMsg.MsgInfoStorage", "deleted dirty msg ,count is %d") } }
                addMethod { returnType { usingEqStrings("PRAGMA table_info( rconversation)") } }
            }
        }.singleOrNull()?.getInstance(loader) ?: return null
        val storage = (service.declaredMethods.asSequence() + service.methods.asSequence())
            .firstOrNull { it.parameterCount == 0 && it.returnType == update.declaringClass }
            ?.apply { isAccessible = true } ?: return null
        val getService = serviceGetter(dex, loader) ?: return null
        return Reading(update, service, storage, getService)
    }

    fun serviceGetter(dex: DexKitBridge, loader: ClassLoader): Method? = dex.findMethod {
            matcher {
                paramTypes(Class::class.java)
                usingEqStrings("calling getService(...)")
            }
        }.filter { Modifier.isStatic(it.modifiers) }.singleOrNull()?.resolve(loader)

    fun classWithMethod(dex: DexKitBridge, packageName: String?, vararg anchors: String): ClassData? =
        dex.findClass {
            if (packageName != null) searchPackages(packageName)
            matcher { addMethod { usingStrings(*anchors) } }
        }.singleOrNull()

    fun MethodData.resolve(loader: ClassLoader): Method? =
        runCatching { getMethodInstance(loader).apply { isAccessible = true } }.getOrNull()

    class Reply(private val ctor: Constructor<*>, private val getters: List<Method>, private val post: Method) {
        fun send(talker: String, text: String): ActionResult {
            val queue = getters.firstNotNullOfOrNull { getter ->
                runCatching { getter.invoke(null) }.getOrNull()?.takeIf(post.declaringClass::isInstance)
            } ?: return ActionResult.NOT_READY
            val args = ctor.parameterTypes.mapIndexed { index, type ->
                when (index) {
                    0 -> talker
                    1 -> text
                    2 -> 1
                    3 -> 0
                    else -> when (type) {
                        Long::class.javaPrimitiveType -> 0L
                        Int::class.javaPrimitiveType -> 0
                        Short::class.javaPrimitiveType -> 0.toShort()
                        Byte::class.javaPrimitiveType -> 0.toByte()
                        Boolean::class.javaPrimitiveType -> false
                        Char::class.javaPrimitiveType -> 0.toChar()
                        Float::class.javaPrimitiveType -> 0f
                        Double::class.javaPrimitiveType -> 0.0
                        else -> null
                    }
                }
            }.toTypedArray<Any?>()
            return if (post.invoke(queue, ctor.newInstance(*args)) == true) ActionResult.DONE
                else ActionResult.FAILED
        }
    }

    class Reading(
        val update: Method,
        private val service: Class<*>,
        private val storage: Method,
        private val getService: Method,
    ) {
        fun mark(talker: String): ActionResult {
            val store = runCatching { storage.invoke(getService.invoke(null, service)) }.getOrNull()
                ?: return ActionResult.NOT_READY
            update.invoke(store, talker)
            return ActionResult.DONE
        }
    }

    enum class ActionResult { DONE, NOT_READY, FAILED }
}
