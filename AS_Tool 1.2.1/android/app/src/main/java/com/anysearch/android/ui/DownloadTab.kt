package com.anysearch.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.anysearch.android.core.AppState
import com.anysearch.android.util.Fmt
import java.util.Locale

/** 文件下载页（对应桌面版“文件下载”标签页 + 批量下载入口） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadTab(onOpenBatch: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val settings by AppState.settings.collectAsState()
    var url by rememberSaveable { mutableStateOf("") }
    var threads by rememberSaveable { mutableIntStateOf(settings.defaultThreads) }
    var autoChunk by rememberSaveable { mutableStateOf(settings.autoChunk) }
    val single by AppState.singleDownload.collectAsState()
    val log by AppState.downloadLog.collectAsState()

    val chunkBytes = settings.chunkSizeMb.coerceIn(1, 64).toLong() * 1024 * 1024

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            AppState.startSingleDownload(
                url.trim(),
                AppState.currentTarget(),
                threads,
                settings.downloadTimeout,
                autoChunk,
                chunkBytes,
            )
        } else {
            AppState.events.tryEmit("未授予存储权限，无法写入公共下载目录；可改用“应用专属目录”")
        }
    }

    // Android 13+：通知权限（拒绝不影响下载，仅通知不可见）
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 结果不影响下载 */ }

    fun startDownload() {
        // 先收起输入法/释放焦点：修复“粘贴长链接后点按钮第一次无响应”
        focusManager.clearFocus()
        val u = url.trim()
        if (u.isEmpty()) {
            AppState.events.tryEmit("请输入 URL（支持文件直链、网页、短链）。")
            return
        }
        // Android 13+：请求通知权限（不影响下载本身）
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Android 9 及以下写公共下载目录需要运行时权限
        if (Build.VERSION.SDK_INT < 29 && settings.downloadLocation == "public" &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        AppState.startSingleDownload(u, AppState.currentTarget(), threads, settings.downloadTimeout, autoChunk, chunkBytes)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // 直链 / 网页 / 短链 URL
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("链接（直链 / 网页 / 短链）") },
            placeholder = { Text("https://example.com/file.zip 或网页链接") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { startDownload() }),
        )
        Spacer(Modifier.height(6.dp))

        // 保存位置 + 线程数
        LocationPickerRow(Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("线程数：", style = MaterialTheme.typography.bodyMedium)
            IntDropdown(label = "1~16", value = threads, range = 1..16, onChange = { threads = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = autoChunk, onCheckedChange = { autoChunk = it })
            Text(
                "自动分块（取消勾选则用手动分块大小 ${settings.chunkSizeMb}MB）",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(6.dp))

        // 操作按钮 + 进度
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(onClick = { startDownload() }, enabled = !single.running) {
                Text(if (single.paused) "继续下载" else "开始下载")
            }
            OutlinedButton(
                onClick = { AppState.cancelSingleDownload() },
                enabled = single.running,
            ) { Text("暂停") }
            OutlinedButton(
                onClick = { AppState.abortSingleDownload() },
                enabled = single.running || single.paused,
            ) { Text("取消") }
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = {
                // single 是委托属性（by collectAsState），不能智能转换，先把 total 提成局部变量
                val total = single.total
                if (total != null && total > 0) (single.pct / 100f).coerceIn(0f, 1f) else 0f
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(2.dp))
        Text(progressLabel(single), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))

        // 日志输出
        OutputText(log, settings.maxDisplayLen, Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(8.dp))

        // 批量下载入口
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(onClick = onOpenBatch) { Text("打开批量下载窗口") }
            HintLabel(
                "批量下载建议",
                "并行任务数 × 每任务线程数 = 总连接数，默认上限 16。\n" +
                    "大文件：1×16；多文件：2×8；带宽小/服务器易封：2×4。",
            )
        }
    }
}

/** 进度文字：百分比 / 已下总量 / 速度 / 剩余时间（对应桌面版下载进度标签） */
private fun progressLabel(s: AppState.SingleDownload): String {
    return when {
        !s.running && s.message.isNotBlank() -> s.message
        s.total != null && s.total > 0 -> {
            val remain = if (s.speed > 0) (s.total - s.done) / s.speed else 0.0
            String.format(
                Locale.US,
                "%.1f%%  %s/%s  %s/s  剩余%s",
                s.pct,
                Fmt.size(s.done),
                Fmt.size(s.total),
                Fmt.size(s.speed.toLong()),
                Fmt.time(remain.toLong()),
            )
        }
        s.running -> "已下载 ${Fmt.size(s.done)}"
        else -> "0%"
    }
}
