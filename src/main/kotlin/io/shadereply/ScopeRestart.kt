package io.shadereply

internal data class ScopeRestart(val systemUi: Boolean, val weChat: Boolean) {
    val hasTargets get() = systemUi || weChat

    fun description(): String = buildList {
        if (systemUi) add("系统界面")
        if (weChat) add("微信")
    }.joinToString("、")

    fun run(userId: Int): String {
        val results = mutableListOf<String>()
        if (systemUi) {
            when (SystemUiRestart.run()) {
                0 -> results += "系统界面已重启"
                4 -> results += "系统界面正在恢复"
                77 -> return "请在 Root 管理器中允许通知快捷回复"
                124 -> return "等待 Root 授权超时"
                else -> results += "系统界面重启失败"
            }
        }
        if (weChat) {
            // stop-app lets Android start WeChat again without setting the force-stopped flag.
            val restart = RootCommand.run(
                "am stop-app --user $userId com.tencent.mm >/dev/null && " +
                    "am start --user $userId -a android.intent.action.MAIN " +
                    "-c android.intent.category.LAUNCHER -n com.tencent.mm/.ui.LauncherUI >/dev/null",
            )
            results += when (restart.status()) {
                0 -> "微信已重新打开"
                77 -> "请在 Root 管理器中允许通知快捷回复"
                124 -> "等待微信启动超时"
                else -> "微信重启失败"
            }
        }
        return results.joinToString("，")
    }
}
