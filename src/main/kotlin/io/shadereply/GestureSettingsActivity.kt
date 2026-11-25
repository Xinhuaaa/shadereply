package io.shadereply

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.libxposed.service.XposedService
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.TopAppBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.ListPreference
import io.github.suqi8.coui.kmp.preference.ListPreferenceEntry
import io.github.suqi8.coui.kmp.preference.SliderPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.darkColorScheme
import io.github.suqi8.coui.kmp.theme.lightColorScheme
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class GestureSettingsActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) postTestNotification() else toast("允许通知后才能发送测试通知")
    }
    private val serviceWorker = Executors.newSingleThreadExecutor()
    private val module get() = application as ModuleApplication
    private val launcher by lazy { ComponentName(this, "$packageName.Launcher") }
    private val version by lazy { packageManager.getPackageInfo(packageName, 0) }

    private var saved by mutableStateOf(GestureConfig.DEFAULT)
    private var settings by mutableStateOf(GestureConfig.DEFAULT)
    private var savedWeChat by mutableStateOf(WeChatConfig())
    private var weChat by mutableStateOf(WeChatConfig())
    private var activation by mutableStateOf(Activation(ModuleState.CHECKING))
    private var hiddenIcon by mutableStateOf(false)
    private var confirmRestart by mutableStateOf(false)
    private var restartTargets by mutableStateOf<ScopeRestart?>(null)
    private var bubblePreference by mutableStateOf(-1)
    private var applyingBubblePreference by mutableStateOf(false)
    private var restarting by mutableStateOf(false)
    private var statusRequest = 0

    private data class Activation(val state: ModuleState, val framework: String? = null)

    private enum class ModuleState(val label: String, val detail: String) {
        CHECKING("检查中", "正在读取模块状态"),
        INACTIVE("未激活", "请在 LSPosed 中启用模块，再重启作用域"),
        NO_SCOPE("待激活", "请在 LSPosed 中勾选需要启用的应用"),
        PENDING("待激活", "重启作用域后加载模块"),
        RUNNING("已激活", "模块正在作用域中运行"),
        STALE("已激活", "重启作用域后加载新版"),
        UNKNOWN("状态未知", "LSPosed 未提供模块运行状态"),
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        saved = GestureConfig.readLocal(this)
        settings = savedInstanceState?.let {
            GestureConfig(it.getBoolean("hold"), it.getInt("time"), it.getBoolean("pull"),
                it.getInt("banner"), it.getInt("shade"))
        } ?: saved
        savedWeChat = WeChatConfig.read(WeChatConfig.local(this))
        weChat = savedInstanceState?.let {
            WeChatConfig(it.getBoolean("wechat_enabled"), it.getBoolean("wechat_bubbles"),
                it.getBoolean("wechat_hide_read"), it.getBoolean("wechat_clear_history"),
                it.getInt("wechat_image_mode", WeChatConfig.IMAGE_THUMB), it.getBoolean("wechat_wait_sticker", true),
                it.getBoolean("wechat_voice_text"))
        } ?: savedWeChat
        if (savedInstanceState?.getBoolean("confirm_restart") == true) {
            restartTargets = ScopeRestart(savedInstanceState.getBoolean("restart_system_ui"),
                savedInstanceState.getBoolean("restart_wechat"))
            confirmRestart = true
        }
        hiddenIcon = packageManager.getComponentEnabledSetting(launcher) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        setContent {
            COUITheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                SettingsScreen()
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        Scaffold(
            topBar = { TopAppBar(title = "通知快捷回复", showDivider = false) },
            bottomBar = {
                if (settings != saved || weChat != savedWeChat) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        TextButton("保存设置", ::saveSettings, Modifier.widthIn(max = 560.dp).fillMaxWidth())
                    }
                }
            },
        ) { insets ->
            Column(
                Modifier.fillMaxSize().padding(insets),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = 592.dp).fillMaxWidth().weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    HomePage()
                }
            }
            RestartDialog()
        }
    }

    @Composable
    private fun HomePage() {
        ActivationCard()
        SettingsSection("回复手势") {
            HoldSettings()
            PullSettings()
        }
        SettingsSection("微信通知") { WeChatSettings() }
        SettingsSection("模块管理") { ModuleSettings() }
    }

    @Composable
    private fun SettingsSection(title: String, content: @Composable () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 5.dp).semantics { heading() },
                style = COUITheme.textStyles.subtitle,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }

    @Composable
    private fun ModuleSettings() {
        Group {
            ArrowPreference(title = "测试通知", onClick = ::postTestNotification)
            RowDivider()
            SwitchPreference(
                title = "隐藏桌面图标", checked = hiddenIcon,
                summary = "可从 LSPosed 的模块列表启动本应用",
                onCheckedChange = ::setLauncherHidden,
            )
            RowDivider()
            ArrowPreference(title = "恢复默认设置", onClick = {
                settings = GestureConfig.DEFAULT
                weChat = WeChatConfig()
            })
            RowDivider()
            ArrowPreference(
                title = if (restarting) "正在重启作用域…" else "重启作用域",
                enabled = !restarting, onClick = ::confirmScopeRestart,
            )
            RowDivider()
            ArrowPreference(title = "关于", statusText = "v${version.versionName}", onClick = {
                startActivity(Intent(this, AboutActivity::class.java))
            })
        }
    }

    private fun postTestNotification() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled() || !TestNotification.post(this)) {
            toast("请在系统设置中开启 ShadeReply 的测试通知")
        }
    }

    @Composable
    private fun RestartDialog() {
        val targets = restartTargets
        OverlayDialog(
            show = confirmRestart,
            title = "重启作用域",
            summary = targets?.let {
                "将重启${it.description()}。" +
                    (if (it.weChat) "微信会重新打开。" else "状态栏和通知栏会暂时隐藏。") +
                    "首次使用需授予 Root 权限。"
            },
            onDismissRequest = { confirmRestart = false },
        ) {
            DialogButtonBar(
                modifier = Modifier.padding(horizontal = 8.dp),
                negative = DialogButtonBarAction(text = "取消", onClick = { confirmRestart = false }),
                positive = DialogButtonBarAction(
                    text = "重启",
                    onClick = {
                        confirmRestart = false
                        if (targets != null) restartScope(targets)
                    },
                    recommended = true,
                ),
            )
        }
    }

    @Composable
    private fun ActivationCard() {
        val dark = isSystemInDarkTheme()
        val state = activation.state
        val running = state == ModuleState.RUNNING || state == ModuleState.STALE
        val pending = state == ModuleState.PENDING || state == ModuleState.NO_SCOPE
        val background = when {
            running -> if (dark) Color(0xFF195939) else Color(0xFF147648)
            pending -> if (dark) Color(0xFF4B361E) else Color(0xFFFFEDD5)
            else -> COUITheme.colorScheme.surfaceContainer
        }
        val foreground = when {
            running -> Color.White
            pending -> if (dark) Color(0xFFFFDBA9) else Color(0xFF6C3F13)
            else -> COUITheme.colorScheme.onSurfaceContainer
        }
        Card(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
            colors = CardDefaults.defaultColors(color = background, contentColor = foreground),
            insideMargin = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Canvas(Modifier.size(44.dp)) {
                    drawCircle(foreground.copy(alpha = 0.16f))
                    val stroke = 2.6.dp.toPx()
                    if (running) {
                        val corner = Offset(size.width * 0.45f, size.height * 0.59f)
                        drawLine(foreground, Offset(size.width * 0.33f, size.height * 0.48f), corner,
                            stroke, StrokeCap.Round)
                        drawLine(foreground, corner, Offset(size.width * 0.67f, size.height * 0.38f),
                            stroke, StrokeCap.Round)
                    } else {
                        drawLine(foreground, Offset(center.x, size.height * 0.33f),
                            Offset(center.x, size.height * 0.50f), stroke, StrokeCap.Round)
                        drawCircle(foreground, radius = 1.4.dp.toPx(),
                            center = Offset(center.x, size.height * 0.65f))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.label, color = foreground, style = COUITheme.textStyles.title2,
                        fontWeight = FontWeight.Bold)
                    val subtitle = if (running) activation.framework ?: state.detail else state.detail
                    Text(subtitle, color = foreground.copy(alpha = if (running) 0.92f else 0.85f),
                        style = COUITheme.textStyles.subtitle)
                    if (state == ModuleState.STALE && subtitle != state.detail) {
                        Text(state.detail, color = foreground.copy(alpha = 0.92f),
                            style = COUITheme.textStyles.subtitle)
                    }
                }
            }
        }
    }

    @Composable
    private fun HoldSettings() {
        Group {
            SwitchPreference(
                title = "长按回复", checked = settings.hold(),
                onCheckedChange = { update(hold = it) },
            )
            RowDivider()
            DistanceSlider("长按时长", "毫秒", settings.holdMs(), GestureConfig.HOLD_MIN,
                GestureConfig.HOLD_MAX, 10, settings.hold()) { update(time = it) }
        }
    }

    @Composable
    private fun PullSettings() {
        Group {
            SwitchPreference(
                title = "下拉回复", checked = settings.pull(),
                onCheckedChange = { update(pull = it) },
            )
            RowDivider()
            DistanceSlider("横幅下拉距离", "dp", settings.bannerDp(), GestureConfig.BANNER_MIN,
                GestureConfig.BANNER_MAX, 1, settings.pull()) { update(banner = it) }
            RowDivider()
            DistanceSlider("通知栏卡片下拉距离", "dp", settings.shadeDp(), GestureConfig.SHADE_MIN,
                GestureConfig.SHADE_MAX, 1, settings.pull()) { update(shade = it) }
        }
    }

    @Composable
    private fun WeChatSettings() {
        Group {
            SwitchPreference(
                title = "微信通知进化", checked = weChat.enabled,
                summary = "对话样式、合并消息记录、快捷回复和标为已读。首次启用需在 LSPosed 勾选微信并重启微信。",
                onCheckedChange = { weChat = weChat.copy(enabled = it) },
            )
            RowDivider()
            SwitchPreference(
                title = "原生聊天气泡", checked = weChat.bubbles, enabled = weChat.enabled,
                summary = "在通知上点气泡图标，把该对话加入气泡。系统气泡设置可选「仅所选对话」。",
                onCheckedChange = { weChat = weChat.copy(bubbles = it) },
            )
            RowDivider()
            ListPreference(
                title = "系统气泡设置", cancelButtonText = "取消",
                entries = listOf(
                    ListPreferenceEntry("不允许气泡"),
                    ListPreferenceEntry("仅所选对话"),
                    ListPreferenceEntry("所有支持的对话"),
                ),
                selectedIndex = bubbleModes.indexOf(bubblePreference),
                onSelectedIndexChange = { applyBubblePreference(bubbleModes[it]) },
                enabled = weChat.enabled && weChat.bubbles,
                onExpandedChange = { if (it) readBubblePreference() },
            )
            RowDivider()
            SwitchPreference(
                title = "隐藏「标为已读」", checked = weChat.hideRead, enabled = weChat.enabled,
                onCheckedChange = { weChat = weChat.copy(hideRead = it) },
            )
            RowDivider()
            SwitchPreference(
                title = "回复后清空收到的消息", checked = weChat.clearHistory, enabled = weChat.enabled,
                onCheckedChange = { weChat = weChat.copy(clearHistory = it) },
            )
            RowDivider()
            ListPreference(
                title = "图片预览", cancelButtonText = "取消",
                entries = listOf(ListPreferenceEntry("关闭"), ListPreferenceEntry("缩略图"),
                    ListPreferenceEntry("优先大图")),
                selectedIndex = weChat.imageMode,
                enabled = weChat.enabled,
                onSelectedIndexChange = { weChat = weChat.copy(imageMode = it) },
            )
            RowDivider()
            SwitchPreference(
                title = "等待贴纸预览", checked = weChat.waitSticker, enabled = weChat.enabled,
                summary = "稍等微信缓存贴纸，预览就绪后更新通知",
                onCheckedChange = { weChat = weChat.copy(waitSticker = it) },
            )
            RowDivider()
            SwitchPreference(
                title = "语音自动转文字", checked = weChat.voiceText, enabled = weChat.enabled,
                summary = "调用微信的语音转文字服务，完成后更新通知",
                onCheckedChange = { weChat = weChat.copy(voiceText = it) },
            )
        }
    }

    @Composable
    private fun RowDivider() {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    }

    @Composable
    private fun Group(content: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp),
            cornerRadius = CardDefaults.GroupCornerRadius, weight = CardDefaults.GroupWeight,
        ) { content() }
    }

    @Composable
    private fun DistanceSlider(
        title: String, unit: String, value: Int, minimum: Int, maximum: Int,
        step: Int, enabled: Boolean, change: (Int) -> Unit,
    ) {
        SliderPreference(
            title = title, value = value.toFloat(),
            onValueChange = { change(minimum + ((it - minimum) / step).roundToInt() * step) },
            valueText = "$value $unit",
            valueRange = minimum.toFloat()..maximum.toFloat(),
            steps = (maximum - minimum) / step - 1,
            enabled = enabled,
        )
    }

    private fun update(
        hold: Boolean = settings.hold(), time: Int = settings.holdMs(),
        pull: Boolean = settings.pull(), banner: Int = settings.bannerDp(), shade: Int = settings.shadeDp(),
    ) {
        settings = GestureConfig(hold, time, pull, banner, shade)
    }

    override fun onResume() {
        super.onResume()
        module.watchConnection(::refreshStatus)
        refreshStatus()
    }

    override fun onPause() {
        module.watchConnection(null)
        super.onPause()
    }

    override fun onDestroy() {
        serviceWorker.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("wechat_enabled", weChat.enabled)
        outState.putBoolean("wechat_bubbles", weChat.bubbles)
        outState.putBoolean("wechat_hide_read", weChat.hideRead)
        outState.putBoolean("wechat_clear_history", weChat.clearHistory)
        outState.putInt("wechat_image_mode", weChat.imageMode)
        outState.putBoolean("wechat_wait_sticker", weChat.waitSticker)
        outState.putBoolean("wechat_voice_text", weChat.voiceText)
        outState.putBoolean("hold", settings.hold())
        outState.putInt("time", settings.holdMs())
        outState.putBoolean("pull", settings.pull())
        outState.putInt("banner", settings.bannerDp())
        outState.putInt("shade", settings.shadeDp())
        outState.putBoolean("confirm_restart", confirmRestart)
        outState.putBoolean("restart_system_ui", restartTargets?.systemUi == true)
        outState.putBoolean("restart_wechat", restartTargets?.weChat == true)
        super.onSaveInstanceState(outState)
    }

    private fun refreshStatus() {
        if (isDestroyed) return
        val request = ++statusRequest
        val service = module.service()
        if (service == null) {
            window.decorView.postDelayed({
                if (!isDestroyed && statusRequest == request) {
                    activation = Activation(ModuleState.INACTIVE)
                }
            }, 900)
            return
        }
        serviceWorker.execute {
            val result = checkActivation(service)
            runOnUiThread {
                if (!isDestroyed && statusRequest == request) {
                    activation = result
                    Log.i("QuickReplySettings", "Module state ${result.state.name}")
                }
            }
        }
    }

    private fun checkActivation(service: XposedService): Activation {
        val state = try {
            val scope = service.scope
            val running = service.runningTargets.filter { target ->
                target.processName in scope ||
                    (WeChatNotifications.PACKAGE in scope && target.processName == "${WeChatNotifications.PACKAGE}:push")
            }
            when {
                scope.isEmpty() -> ModuleState.NO_SCOPE
                running.isEmpty() -> ModuleState.PENDING
                running.any { it.loadedVersionCode != version.longVersionCode } -> ModuleState.STALE
                else -> ModuleState.RUNNING
            }
        } catch (_: RuntimeException) {
            ModuleState.UNKNOWN
        }
        val framework = if (state == ModuleState.RUNNING || state == ModuleState.STALE) {
            runCatching { "${service.frameworkName} ${service.frameworkVersion} · API ${service.apiVersion}" }
                .getOrNull()
        } else null
        return Activation(state, framework)
    }

    private fun confirmScopeRestart() {
        val service = module.service()
        if (service == null) {
            toast("请先在 LSPosed 中启用模块")
            return
        }
        serviceWorker.execute {
            val targets = runCatching {
                val scope = service.scope
                ScopeRestart(ModuleApplication.SYSTEM_UI in scope, WeChatNotifications.PACKAGE in scope)
            }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when {
                    targets == null -> toast("无法读取 LSPosed 作用域")
                    !targets.hasTargets -> toast("请先在 LSPosed 中勾选作用域")
                    else -> {
                        restartTargets = targets
                        confirmRestart = true
                    }
                }
            }
        }
    }

    private fun restartScope(targets: ScopeRestart) {
        if (restarting) return
        restarting = true
        Thread({
            val result = targets.run(android.os.Process.myUid() / 100_000)
            Log.i("QuickReplySettings", result)
            runOnUiThread {
                if (!isDestroyed) {
                    restarting = false
                    toast(result)
                    refreshStatus()
                    window.decorView.postDelayed(::refreshStatus, 1200)
                }
            }
        }, "QuickReplyRestart").start()
    }

    // The system uses 0 for none, 1 for all and 2 for selected conversations.
    private val bubbleModes = listOf(0, 2, 1)

    private fun readBubblePreference() {
        if (applyingBubblePreference) return
        serviceWorker.execute {
            val uid = runCatching {
                packageManager.getPackageUid(WeChatNotifications.PACKAGE, PackageManager.PackageInfoFlags.of(0))
            }.getOrNull()
            val preference = uid?.let(WeChatBubbleSettings::read) ?: -1
            runOnUiThread {
                if (!isDestroyed && !applyingBubblePreference) bubblePreference = preference
            }
        }
    }

    private fun applyBubblePreference(value: Int) {
        if (applyingBubblePreference) return
        applyingBubblePreference = true
        serviceWorker.execute {
            val saved = WeChatBubbleSettings.apply(android.os.Process.myUid() / 100_000, value)
            runOnUiThread {
                if (!isDestroyed) {
                    applyingBubblePreference = false
                    if (saved) bubblePreference = value else toast("无法保存系统气泡设置，请检查 Root 授权")
                }
            }
        }
    }

    private fun setLauncherHidden(hidden: Boolean) {
        packageManager.setComponentEnabledSetting(
            launcher,
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP,
        )
        hiddenIcon = hidden
    }

    private fun saveSettings() {
        val gestureSaved = settings == saved || settings.save(this)
        val changedWeChat = weChat != savedWeChat
        val weChatSaved = !changedWeChat || weChat.save(WeChatConfig.local(this))
        if (gestureSaved) saved = settings
        if (weChatSaved) savedWeChat = weChat
        when {
            !gestureSaved || !weChatSaved -> toast("部分设置保存失败，请重试")
            changedWeChat && !module.syncWeChatSettings() -> toast("设置已保存，连接 LSPosed 后同步")
            else -> toast("设置已保存")
        }
    }

    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
}
