package io.shadereply

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TopAppBar
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.darkColorScheme
import io.github.suqi8.coui.kmp.theme.lightColorScheme
import kotlinx.coroutines.Dispatchers
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
        var request by rememberSaveable { mutableIntStateOf(0) }
        var checking by remember { mutableStateOf(request > 0) }
        var status by remember { mutableStateOf<String?>(null) }
        var available by remember { mutableStateOf<ShadeReplyRelease?>(null) }
        LaunchedEffect(request) {
            if (request == 0) return@LaunchedEffect
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
        Scaffold(topBar = { TopAppBar(title = "关于", navigationIcon = { BackButton() }, showDivider = false) }) { insets ->
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
                                    available = null
                                    request++
                                },
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            ArrowPreference(title = "项目主页", summary = "Xinhuaaa/shadereply",
                                onClick = { openLink(ShadeReplyReleases.PROJECT) })
                        }
                    }
                    available?.let { ReleaseCard(it) }
                }
            }
        }
    }

    @Composable
    private fun ReleaseCard(release: ShadeReplyRelease) {
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
                val download = release.apk
                if (download != null) {
                    ArrowPreference(title = "下载 APK", onClick = { openLink(download) })
                } else {
                    ArrowPreference(title = "查看发布页", onClick = { openLink(release.page) })
                }
            }
        }
    }

    @Composable
    private fun BackButton() {
        val color = COUITheme.colorScheme.onSurface
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button) { finish() }
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
