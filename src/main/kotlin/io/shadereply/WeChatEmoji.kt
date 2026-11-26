package io.shadereply

internal object WeChatEmoji {
    // Canonical mappings adapted from SymonChu/WeKite at 19bffe4c (GPL-3.0).
    // The same face can have a localized label, an English name, or an older /: code.
    private val replacements = buildMap {
        fun emoji(glyph: String, vararg aliases: String) {
            for (alias in aliases) put(if (alias.startsWith("/:")) alias else "[$alias]", glyph)
        }

        emoji("🙂", "微笑", "Smile", "/::)")
        emoji("😕", "撇嘴", "/::~")
        emoji("😍", "色", "/::B")
        emoji("😳", "发呆", "發呆", "/::|")
        emoji("😶", "无语", "無語", "Duh")
        emoji("😎", "得意", "酷", "/:8-)")
        emoji("😭", "流泪", "流淚", "/::<")
        emoji("😊", "害羞", "/::$")
        emoji("😊", "愉快", "/:,@-D")
        emoji("🤐", "闭嘴", "閉嘴", "/::X")
        emoji("😴", "睡", "/::Z")
        emoji("😫", "大哭", "/::'(")
        emoji("😅", "尴尬", "尷尬", "/::-|")
        emoji("😓", "汗", "Sweats", "流汗", "/::>", "擦汗", "/:wipe")
        emoji("😡", "发怒", "發怒", "/::@")
        emoji("😜", "调皮", "調皮", "/::P")
        emoji("😁", "呲牙", "龇牙", "齜牙", "/::D")
        emoji("😱", "惊讶", "驚訝", "/::O")
        emoji("🙁", "难过", "難過", "/::(")
        emoji("😨", "囧", "Blush")
        emoji("😳", "脸红", "臉紅", "Flushed")
        emoji("😫", "抓狂", "/::Q")
        emoji("🤮", "吐", "/::T")
        emoji("🤭", "偷笑", "/:,@P")
        emoji("🙄", "白眼", "/::d", "翻白眼", "Boring")
        emoji("😏", "傲慢", "/:,@o")
        emoji("😤", "怄火", "慪火", "/:oh")
        emoji("😏", "坏笑", "壞笑", "/:B-)", "奸笑", "Smirk")
        emoji("😏", "机智", "機智", "Smart")
        emoji("🤤", "饿", "餓", "/::g")
        emoji("🥱", "困", "/:|-)")
        emoji("🥱", "哈欠", "/::-O")
        emoji("😨", "惊恐", "驚恐", "/::!", "恐惧", "恐懼", "Terror")
        emoji("😱", "吓", "嚇", "/:@x")
        emoji("😃", "憨笑", "/::,@")
        emoji("☕", "悠闲", "悠閒", "/:,-D")
        emoji("💪", "奋斗", "奮鬥", "/:,@f", "加油", "GoForIt", "加油加油", "加油！", "KeepFighting")
        emoji("🤬", "咒骂", "咒罵", "/::-S")
        emoji("❓", "疑问", "疑問", "/:?")
        emoji("🤔", "Emm", "一言難盡", "一言难尽")
        emoji("🤫", "嘘", "噓", "/:,@x")
        emoji("😵", "晕", "暈", "/:,@@")
        emoji("☹️", "衰", "/:,@!")
        emoji("😞", "失望", "LetDown", "Let Down")
        emoji("💀", "骷髅", "骷髏", "/:!!!")
        emoji("🔨", "敲打", "/:xx")
        emoji("👋", "再见", "再見", "Bye", "/:bye")
        emoji("👃", "抠鼻", "摳鼻", "/:dig")
        emoji("👏", "鼓掌", "/:handclap")
        emoji("😖", "糗大了", "/:&-(")
        emoji("😒", "左哼哼", "/:<@", "右哼哼", "/:@>")
        emoji("🙄", "鄙视", "鄙視", "/:>-|")
        emoji("🥺", "委屈", "/:P-(")
        emoji("🥺", "可怜", "可憐", "/:8*")
        emoji("😭", "快哭了", "/::'|")
        emoji("😈", "阴险", "陰險", "/:X-)")
        emoji("😘", "亲亲", "親親", "/::*")
        emoji("😄", "笑脸", "笑臉", "Happy")
        emoji("😷", "生病", "Sick")
        emoji("😂", "破涕为笑", "破涕為笑", "Lol")
        emoji("🕺", "嘿哈", "吼嘿", "Hey")
        emoji("🤦", "捂脸", "捂臉", "掩面", "Facepalm")
        emoji("😟", "皱眉", "皺眉", "Concerned")
        emoji("✌️", "耶", "歐耶", "Yeah!", "胜利", "勝利", "/:v")
        emoji("🍉", "吃瓜", "吃西瓜", "西瓜", "Onlooker", "/:<W>")
        emoji("😱", "天啊", "OMG")
        emoji("🤝", "社会社会", "社會社會", "失敬失敬", "Respect")
        emoji("🐶", "旺柴", "Doge", "小狗", "Pup")
        emoji("👌", "好的", "NoProb", "OK", "/:ok")
        emoji("🖐️", "打脸", "打臉", "MyBad")
        emoji("🤩", "哇", "Wow")
        emoji("🤙", "666", "Awesome")
        emoji("🫣", "让我看看", "讓我看看", "LetMeSee")
        emoji("😮‍💨", "叹气", "嘆氣", "嘆息", "Sigh")
        emoji("😭", "苦涩", "苦澀", "難受", "Hurt")
        emoji("🫠", "裂开", "裂開", "崩潰", "Broken")
        emoji("👄", "嘴唇", "/:showlove")
        emoji("❤️", "爱心", "愛心", "/:heart")
        emoji("💔", "心碎", "/:break")
        emoji("🤗", "拥抱", "擁抱", "/:hug")
        emoji("👍", "强", "強", "/:strong")
        emoji("👎", "弱", "/:weak")
        emoji("🤝", "握手", "/:share")
        emoji("🙏", "抱拳", "Salute", "合十", "Worship")
        emoji("☝️", "勾引", "/:jj")
        emoji("👊", "拳头", "拳頭", "/:@@")
        emoji("🍺", "啤酒", "/:beer")
        emoji("☕", "咖啡", "/:coffee")
        emoji("🍵", "茶", "Tea")
        emoji("🎂", "蛋糕", "/:cake")
        emoji("🌹", "玫瑰", "/:rose")
        emoji("🥀", "凋谢", "凋謝", "/:fade")
        emoji("🔪", "菜刀", "/:pd")
        emoji("💣", "炸弹", "炸彈", "/:bome")
        emoji("💩", "便便", "/:shit")
        emoji("🌙", "月亮", "/:moon")
        emoji("☀️", "太阳", "太陽", "/:sun")
        emoji("🎉", "庆祝", "慶祝", "Party")
        emoji("🎁", "礼物", "禮物", "gift", "Gift")
        emoji("🧧", "红包", "紅包", "Packet")
        emoji("🀄", "發", "Rich")
        emoji("🧧", "福", "Blessing")
        emoji("🎆", "烟花", "煙花", "Fireworks")
        emoji("🧨", "爆竹", "Firecracker")
        emoji("🕯️", "蜡烛", "蠟燭", "Candle")
        emoji("🐥", "鸡", "雞", "小雞", "Chick")
        emoji("🐷", "猪头", "豬頭", "/:pig")
        emoji("💃", "跳跳", "/:jump")
        emoji("🫨", "发抖", "發抖", "/:shake")
        emoji("🌀", "转圈", "轉圈", "/:circle")
        emoji("⚡", "闪电", "閃電", "/:li")
    }

    private val tokens = Regex(
        replacements.keys.asSequence().filter { it.startsWith("/:") }.sortedByDescending(String::length)
            .joinToString("|", prefix = """\b[A-Za-z][A-Za-z0-9+.-]*://\S+|\[[^\[\]\r\n]+]|""") { code ->
                Regex.escape(code) + if (code.last().isLetterOrDigit()) "(?![A-Za-z0-9_])" else ""
            },
    )

    private val emojiPlaceholders = setOf("[表情]", "[Emoji]", "[Emoticon]", "\uFFFC")

    fun forNotification(text: String, readOriginalText: () -> String = { "" }): String {
        // WeChat can shorten a face-only message to a generic preview.
        // Require every part of the original to be a known face or whitespace.
        if (text in emojiPlaceholders) {
            val originalText = readOriginalText().trim()
            if (originalText.isNotEmpty()) {
                val rest = tokens.replace(originalText) { if (it.value in replacements) "" else it.value }
                if (rest.isBlank()) return replaceTokens(originalText)
            }
        }
        return replaceTokens(text)
    }

    private fun replaceTokens(text: String): String {
        if ('[' !in text && "/:" !in text) return text
        return tokens.replace(text) { replacements[it.value] ?: it.value }
    }
}
