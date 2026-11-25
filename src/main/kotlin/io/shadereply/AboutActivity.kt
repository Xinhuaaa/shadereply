package io.shadereply

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TopAppBar
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.darkColorScheme
import io.github.suqi8.coui.kmp.theme.lightColorScheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val installedVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        setContent {
            COUITheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                AboutPage(installedVersion)
            }
        }
    }

    @Composable
    private fun AboutPage(installedVersion: String) {
        var license by rememberSaveable { mutableStateOf<String?>(null) }
        var showLicenses by rememberSaveable { mutableStateOf(false) }
        var request by rememberSaveable { mutableIntStateOf(1) }
        var checking by remember { mutableStateOf(true) }
        var status by remember { mutableStateOf<String?>(null) }
        var available by remember { mutableStateOf<ShadeReplyRelease?>(null) }
        val installer = remember { ShadeReplyInstaller(applicationContext) }
        LaunchedEffect(request) {
            if (available == null) {
                available = withContext(Dispatchers.IO) { runCatching { installer.trackedRelease(installedVersion) }.getOrNull() }
            }
            try {
                available = withContext(Dispatchers.IO) { ShadeReplyReleases.newerThan(installedVersion) }
                status = if (available == null) "已是最新版本" else "发现新版本"
            } catch (_: IOException) {
                status = "无法连接 GitHub，请稍后重试"
            } catch (_: JSONException) {
                status = "无法读取发布信息，请稍后重试"
            } finally {
                checking = false
            }
        }
        BackHandler(showLicenses) {
            if (license == null) showLicenses = false else license = null
        }
        if (showLicenses) {
            LicensesPage(license, onSelect = { license = it }, onBack = {
                if (license == null) showLicenses = false else license = null
            })
            return
        }
        Scaffold(topBar = { TopAppBar(title = "关于", navigationIcon = { BackButton { finish() } }, showDivider = false) }) { insets ->
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier.widthIn(max = 592.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(76.dp).clip(RoundedCornerShape(20.dp))) {
                            Image(painterResource(R.drawable.module_icon_background), null, Modifier.fillMaxSize())
                            Image(painterResource(R.drawable.module_icon_foreground), null, Modifier.fillMaxSize())
                        }
                        Text("通知快捷回复", style = COUITheme.textStyles.title3, fontWeight = FontWeight.SemiBold)
                        Text("v$installedVersion · ColorOS 17", style = COUITheme.textStyles.subtitle,
                            color = COUITheme.colorScheme.onSurfaceVariantSummary)
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column {
                            ArrowPreference(
                                title = "检查更新", summary = if (checking) "正在查询 GitHub Releases…" else status,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                enabled = !checking,
                                onClick = {
                                    checking = true
                                    status = null
                                    request++
                                },
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            ArrowPreference(title = "项目主页", summary = "Xinhuaaa/shadereply",
                                onClick = { openLink(ShadeReplyReleases.PROJECT) })
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            ArrowPreference(title = "开源许可", summary = "GPL-3.0 · 第三方组件",
                                onClick = { showLicenses = true })
                        }
                    }
                    available?.let { ReleaseCard(it, installer) }
                }
            }
        }
    }

    @Composable
    private fun ReleaseCard(release: ShadeReplyRelease, installer: ShadeReplyInstaller) {
        Card(Modifier.fillMaxWidth()) {
            Column {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val label = if (release.prerelease) "${release.tag} · 预发布" else release.tag
                    Text(label, style = COUITheme.textStyles.title4, fontWeight = FontWeight.SemiBold)
                    if (release.notes.isNotBlank()) {
                        Text(release.notes, style = COUITheme.textStyles.footnote1)
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                release.apk?.let {
                    UpdateAction(release, it, installer)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
                ArrowPreference(title = "查看发布页", onClick = { openLink(release.page) })
            }
        }
    }

    @Composable
    private fun UpdateAction(release: ShadeReplyRelease, asset: ShadeReplyApk, installer: ShadeReplyInstaller) {
        val scope = rememberCoroutineScope()
        var state by remember(asset) { mutableStateOf<ShadeReplyInstaller.State>(ShadeReplyInstaller.State.Idle) }
        var revision by remember(asset) { mutableIntStateOf(0) }
        var working by remember(asset) { mutableStateOf(true) }
        var feedback by remember(asset) { mutableStateOf<String?>(null) }

        LaunchedEffect(asset, revision) {
            do {
                state = withContext(Dispatchers.IO) {
                    runCatching { installer.status(asset) }.getOrDefault(ShadeReplyInstaller.State.Failed)
                }
                working = false
                if (state !is ShadeReplyInstaller.State.Pending) break
                delay(700)
            } while (true)
        }

        val pending = state as? ShadeReplyInstaller.State.Pending
        val ready = state == ShadeReplyInstaller.State.Ready
        val title = when {
            ready -> "安装更新"
            pending != null -> "正在下载"
            state == ShadeReplyInstaller.State.Failed -> "重试下载"
            else -> "下载更新"
        }
        val summary = feedback ?: if (pending != null) {
            pending.percent?.let { "$it%" } ?: "等待下载开始"
        } else if (ready) {
            "已下载，安装由 Android 确认"
        } else {
            android.text.format.Formatter.formatFileSize(this, asset.size)
        }
        ArrowPreference(
            title = title,
            summary = summary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            enabled = !working && pending == null,
            onClick = {
                if (ready && !packageManager.canRequestPackageInstalls()) {
                    val permission = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                    feedback = "允许 ShadeReply 安装应用后，返回此页点击「安装更新」"
                    runCatching { startActivity(permission) }
                        .onFailure { feedback = "请在系统设置允许 ShadeReply 安装应用" }
                } else {
                    working = true
                    feedback = null
                    scope.launch {
                        try {
                            if (ready) {
                                val intent = withContext(Dispatchers.IO) { installer.installationIntent(asset) }
                                startActivity(intent)
                            } else {
                                withContext(Dispatchers.IO) { installer.start(release, asset) }
                                state = ShadeReplyInstaller.State.Pending(null)
                            }
                        } catch (_: IOException) {
                            if (ready) {
                                withContext(Dispatchers.IO) { runCatching { installer.discard(asset) } }
                                feedback = "安装包校验失败，请重新下载"
                            } else {
                                feedback = "无法开始下载，请稍后重试"
                            }
                            state = ShadeReplyInstaller.State.Failed
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: RuntimeException) {
                            feedback = if (ready) "无法打开系统安装器" else "无法开始下载，请稍后重试"
                        } finally {
                            working = false
                            if (!ready) revision++
                        }
                    }
                }
            },
        )
        if (pending != null) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
                progress = pending.percent?.div(100f),
            )
        }
    }

    @Composable
    private fun LicensesPage(selected: String?, onSelect: (String) -> Unit, onBack: () -> Unit) {
        var contents by remember(selected) { mutableStateOf<String?>(null) }
        LaunchedEffect(selected) {
            if (selected != null) contents = withContext(Dispatchers.IO) {
                runCatching { assets.open("licenses/$selected").bufferedReader().use { it.readText() } }
                    .getOrDefault("无法读取许可文件")
            }
        }
        Scaffold(topBar = { TopAppBar(title = "开源许可", navigationIcon = { BackButton(onBack) }, showDivider = false) }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)) {
                if (selected == null) {
                    Card(Modifier.fillMaxWidth()) {
                        Column {
                            ArrowPreference(title = "ShadeReply 许可", summary = "GPL-3.0", onClick = { onSelect("LICENSE") })
                            ArrowPreference(title = "项目来源与第三方许可", onClick = { onSelect("NOTICE.md") })
                            ArrowPreference(title = "COUI17 上游版权声明", onClick = { onSelect("COUI-NOTICE.txt") })
                            ArrowPreference(title = "Apache License 2.0", onClick = { onSelect("Apache-2.0.txt") })
                            ArrowPreference(title = "GNU LGPL v3", onClick = { onSelect("LGPL-3.0.txt") })
                            ArrowPreference(title = "Mozilla Public License 2.0", onClick = { onSelect("MPL-2.0.txt") })
                        }
                    }
                } else {
                    SelectionContainer {
                        Text(contents ?: "正在读取…", style = COUITheme.textStyles.footnote1)
                    }
                }
            }
        }
    }

    @Composable
    private fun BackButton(onBack: () -> Unit) {
        val color = COUITheme.colorScheme.onSurface
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "返回" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(22.dp)) {
                val stroke = 2.dp.toPx()
                val tip = Offset(size.width * .28f, size.height * .5f)
                drawLine(start = tip, color = color, end = Offset(size.width * .62f, size.height * .16f),
                    strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(color, tip, Offset(size.width * .62f, size.height * .84f), stroke, StrokeCap.Round)
            }
        }
    }

    private fun openLink(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, "找不到可打开链接的应用", Toast.LENGTH_SHORT).show() }
    }
}
