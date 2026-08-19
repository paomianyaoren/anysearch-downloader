package com.anysearch.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anysearch.android.core.AppState
import com.anysearch.android.core.Settings

/** 设置页（对应桌面版“设置”标签页）。m3 1.3.x 部分组件仍为实验性 API，统一在此 OptIn */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab() {
    val savedSettings by AppState.settings.collectAsState()
    val initial = savedSettings
    val apiKey by AppState.apiKey.collectAsState()
    var keyText by rememberSaveable { mutableStateOf(apiKey) }

    var maxDisplay by rememberSaveable { mutableStateOf(initial.maxDisplayLen.toString()) }
    var apiTimeout by rememberSaveable { mutableStateOf(initial.apiTimeout.toString()) }
    var downloadTimeout by rememberSaveable { mutableStateOf(initial.downloadTimeout.toString()) }
    var defaultThreads by rememberSaveable { mutableIntStateOf(initial.defaultThreads) }
    var useProxy by rememberSaveable { mutableStateOf(initial.useProxy) }
    var proxyUrl by rememberSaveable { mutableStateOf(initial.proxyUrl) }
    var customHeaders by rememberSaveable { mutableStateOf(initial.customHeaders) }
    var maxConnections by rememberSaveable { mutableIntStateOf(initial.maxTotalConnections) }
    var allowBreak by rememberSaveable { mutableStateOf(initial.allowBreakLimit) }
    var autoChunk by rememberSaveable { mutableStateOf(initial.autoChunk) }
    var chunkMb by rememberSaveable { mutableIntStateOf(initial.chunkSizeMb) }
    var showChunkHelp by remember { mutableStateOf(false) }

    // 镜像源
    val mirrorStrategyOptions = listOf("仅直连（不使用镜像）", "镜像优先（立即开始，后台测速择快切换）", "直连优先（过慢自动切换镜像）")
    val mirrorStrategyValues = listOf("direct", "mirror_first", "auto_fallback")
    var mirrorStrategyIdx by rememberSaveable {
        mutableIntStateOf(mirrorStrategyValues.indexOf(initial.mirrorStrategy).coerceAtLeast(0))
    }
    var mirrorSpeedKbps by rememberSaveable { mutableStateOf(initial.mirrorSpeedLimitKbps.toString()) }
    var mirrorProbe by rememberSaveable { mutableIntStateOf(initial.mirrorProbeConns.coerceIn(1, 8)) }
    var mirrorsText by rememberSaveable { mutableStateOf(initial.mirrors.joinToString("\n")) }

    // 链接解析（v1.2.1）
    var resolveLinks by rememberSaveable { mutableStateOf(initial.resolveLinks) }
    var resolveAutoBest by rememberSaveable { mutableStateOf(initial.resolveAutoBest) }

    // 断点缓存占用（进入页面/清理后刷新）
    var cacheBytes by remember { mutableLongStateOf(AppState.partsCacheBytes()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        // API Key（v1.2.0 从顶部移入设置页）
        Text("API Key", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("输入 AnySearch API Key（可留空匿名调用）") },
            )
            Spacer(Modifier.padding(horizontal = 4.dp))
            Button(
                onClick = {
                    AppState.saveKey(keyText.trim())
                    keyText = keyText.trim()
                    AppState.events.tryEmit("Key 已保存到应用私有目录")
                },
            ) { Text("保存") }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()   // 统一间距：区块间 12dp、区内 8dp
        Spacer(Modifier.height(8.dp))

        Text("显示与性能参数", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))

        NumberField("最大显示字符数（0 = 不限制）", maxDisplay) { maxDisplay = it }
        Spacer(Modifier.height(4.dp))
        NumberField("API 超时（秒，最小 5）", apiTimeout) { apiTimeout = it }
        Spacer(Modifier.height(4.dp))
        NumberField("下载超时（秒，最小 5）", downloadTimeout) { downloadTimeout = it }
        Spacer(Modifier.height(6.dp))
        // 标签与下拉框上下排列：窄屏/横屏下不会挤在一行
        Text("默认下载线程数：", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        IntDropdown(label = "1~16", value = defaultThreads, range = 1..16, onChange = { defaultThreads = it })

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()   // 统一间距：区块间 12dp、区内 8dp
        Spacer(Modifier.height(8.dp))
        Text("代理服务器", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = useProxy, onCheckedChange = { useProxy = it })
            Text("使用代理服务器", style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(
            value = proxyUrl,
            onValueChange = { proxyUrl = it },
            enabled = useProxy,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("代理地址") },
            placeholder = { Text("http://127.0.0.1:7890") },
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()   // 统一间距：区块间 12dp、区内 8dp
        Spacer(Modifier.height(8.dp))
        Text("自定义请求头", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = customHeaders,
            onValueChange = { customHeaders = it },
            modifier = Modifier.fillMaxWidth().height(110.dp),
            label = { Text("每行一个：名称: 值") },
        )

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val newSettings = validateAndBuild(
                    maxDisplay, apiTimeout, downloadTimeout, defaultThreads,
                    useProxy, proxyUrl, customHeaders, maxConnections, allowBreak,
                    autoChunk, chunkMb,
                    mirrorsText, mirrorStrategyIdx, mirrorSpeedKbps, mirrorProbe,
                    resolveLinks, resolveAutoBest,
                )
                if (newSettings == null) {
                    AppState.events.tryEmit("请输入有效数值。")
                } else {
                    AppState.saveSettings(newSettings)
                    AppState.events.tryEmit("设置已保存（应用私有目录 anysearch_settings.json），已立即生效")
                }
            },
        ) { Text("保存设置") }
        Spacer(Modifier.height(4.dp))
        HintLabel(
            "保存设置提示",
            "最大显示字符数设 0 表示不限制。\n" +
                "代理仅支持 HTTP/HTTPS 代理，例如 http://127.0.0.1:7890；SOCKS 代理暂不支持。\n" +
                "自定义请求头会同时用于 API 与下载（可填 Referer、Accept-Language 等）；\n" +
                "请只填写你有权使用的头，保存后会明文存到 settings 文件。",
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()   // 统一间距：区块间 12dp、区内 8dp
        Spacer(Modifier.height(8.dp))
        Text("下载连接限制", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text("总连接数上限（并行任务数 × 每任务线程数）：", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        IntDropdown(label = "1~128", value = maxConnections, range = 1..128, onChange = { maxConnections = it })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = allowBreak, onCheckedChange = { allowBreak = it })
            Text("允许突破连接数上限（危险：可能封 IP、服务器拒绝、文件损坏）", style = MaterialTheme.typography.bodySmall)
        }
        HintLabel(
            "建议组合",
            "大文件 1 任务 × 16 线程；多个文件 2 任务 × 8 线程；\n" +
                "带宽小或服务器易封 2 任务 × 4 线程。总连接数不要超过你的网络和服务器的承受能力。",
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()   // 统一间距：区块间 12dp、区内 8dp
        Spacer(Modifier.height(8.dp))
        Text("下载保存位置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        LocationPickerRow(Modifier.fillMaxWidth())

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()   // 统一间距：区块间 12dp、区内 8dp
        Spacer(Modifier.height(8.dp))
        Text("下载分块", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = autoChunk, onCheckedChange = { autoChunk = it })
            Text("默认自动分块", style = MaterialTheme.typography.bodyMedium)
            HintQuestionButton(onClick = { showChunkHelp = true })
        }
        Text("手动分块大小（MB，1~64）：", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        IntDropdown(label = "1~64", value = chunkMb, range = 1..64, onChange = { chunkMb = it })

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()   // 统一间距：区块间 12dp、区内 8dp
        Spacer(Modifier.height(8.dp))
        Text("下载断点缓存", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "断点缓存占用：${com.anysearch.android.util.Fmt.size(cacheBytes)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val freed = AppState.cleanupPartsCache()
                    cacheBytes = AppState.partsCacheBytes()
                    AppState.events.tryEmit("已清理断点缓存，释放 ${com.anysearch.android.util.Fmt.size(freed)}")
                },
            ) { Text("清理") }
        }
        HintLabel("断点缓存说明", "断点缓存用于断点续传；清理后未完成的下载将从头开始。")

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()   // 统一间距：区块间 12dp、区内 8dp
        Spacer(Modifier.height(8.dp))
        Text("镜像源", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text("镜像策略：", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        StringDropdown(
            label = "策略",
            options = mirrorStrategyOptions,
            selectedIndex = mirrorStrategyIdx,
            onSelect = { mirrorStrategyIdx = it },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        NumberField("低速切换阈值（KB/s，0=不切换）", mirrorSpeedKbps) { mirrorSpeedKbps = it }
        Spacer(Modifier.height(8.dp))
        Text("镜像测速并发数（1~8，默认 3）：", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        IntDropdown(label = "1~8", value = mirrorProbe, range = 1..8, onChange = { mirrorProbe = it })
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = mirrorsText,
            onValueChange = { mirrorsText = it },
            modifier = Modifier.fillMaxWidth().height(110.dp),
            label = { Text("镜像模板（每行一个，含 {url} 占位符）") },
        )
        HintLabel(
            "镜像说明",
            "示例（GitHub 加速，第三方服务请自行甄别）：\n" +
                "https://ghfast.top/{url}\n" +
                "https://gh-proxy.com/{url}\n" +
                "镜像同样支持断点续传；内容与直连不一致的源会被自动跳过。",
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()   // 统一间距：区块间 12dp、区内 8dp
        Spacer(Modifier.height(8.dp))
        Text("链接解析", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = resolveLinks, onCheckedChange = { resolveLinks = it })
            Text("下载到网页/被拒时自动解析真实直链", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = resolveAutoBest, onCheckedChange = { resolveAutoBest = it })
            Text("解析出多个下载选项时自动选择最佳（不弹窗）", style = MaterialTheme.typography.bodySmall)
        }
        HintLabel(
            "链接解析说明",
            "直链直接下载、不经过解析；只有下载目标返回网页（假直链）或被站点拒绝（403/429）时才自动解析：\n" +
                "跟随 HTTP/meta/JS 跳转、提取页面里的直链（多清晰度图片、zip/7z/apk 等），\n" +
                "并识别需要人机验证的下载页（弹出验证码输入框，提交后继续）。",
        )
        Spacer(Modifier.height(12.dp))
        HintText(
            "免责声明：本工具仅供学习与合法用途使用；请勿抓取/下载违反版权、隐私或法律法规的内容。",
        )
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()   // 统一间距：区块间 12dp、区内 8dp
        Spacer(Modifier.height(8.dp))
        val context = LocalContext.current
        val version = remember {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
            } catch (e: Exception) {
                "未知"
            }
        }
        Text(
            "AnySearch 工具包 v$version",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))

        // 自动分块规则：点“?”弹窗查看（手机屏幕小，不常驻占位）
        if (showChunkHelp) {
            AlertDialog(
                onDismissRequest = { showChunkHelp = false },
                title = { Text("自动分块规则") },
                text = {
                    Text(
                        "块大小 = 文件大小 ÷ (线程数 × 4)，限制在 1MB~16MB 之间；\n" +
                            "文件 ≤1MB 或服务器不支持 Range 时自动单线程下载。\n" +
                            "手动分块时使用下方设定的固定块大小，与自动分块互斥。",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showChunkHelp = false }) { Text("知道了") }
                },
            )
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

/** 校验并构建设置对象；非法输入返回 null（对应桌面版 _save_settings 的校验） */
private fun validateAndBuild(
    maxDisplay: String,
    apiTimeout: String,
    downloadTimeout: String,
    defaultThreads: Int,
    useProxy: Boolean,
    proxyUrl: String,
    customHeaders: String,
    maxConnections: Int,
    allowBreak: Boolean,
    autoChunk: Boolean,
    chunkMb: Int,
    mirrorsText: String,
    mirrorStrategyIdx: Int,
    mirrorSpeedKbps: String,
    mirrorProbe: Int,
    resolveLinks: Boolean,
    resolveAutoBest: Boolean,
): Settings? {
    val md = maxDisplay.toIntOrNull() ?: return null
    if (md < 0) return null
    val api = apiTimeout.toIntOrNull() ?: return null
    if (api < 5) return null
    val dl = downloadTimeout.toIntOrNull() ?: return null
    if (dl < 5) return null
    val limit = mirrorSpeedKbps.toIntOrNull() ?: return null
    if (limit < 0) return null
    val threads = defaultThreads.coerceIn(1, 16)
    val conn = maxConnections.coerceIn(1, 128)
    val cm = chunkMb.coerceIn(1, 64)
    val mirrors = mirrorsText.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && "{url}" in it }
        .toList()
    val strategyValues = listOf("direct", "mirror_first", "auto_fallback")
    val strategy = strategyValues.getOrElse(mirrorStrategyIdx.coerceIn(0, 2)) { "direct" }
    val current = AppState.settings.value
    return current.copy(
        maxDisplayLen = md,
        apiTimeout = api,
        downloadTimeout = dl,
        defaultThreads = threads,
        useProxy = useProxy,
        proxyUrl = proxyUrl.trim(),
        customHeaders = customHeaders.trim(),
        maxTotalConnections = conn,
        allowBreakLimit = allowBreak,
        autoChunk = autoChunk,
        chunkSizeMb = cm,
        mirrors = mirrors,
        mirrorStrategy = strategy,
        mirrorSpeedLimitKbps = limit,
        mirrorProbeConns = mirrorProbe.coerceIn(1, 8),
        resolveLinks = resolveLinks,
        resolveAutoBest = resolveAutoBest,
    )
}
