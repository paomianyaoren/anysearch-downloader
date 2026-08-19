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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.anysearch.android.core.AppState

/** 批量下载界面（对应桌面版批量下载独立窗口）。m3 1.3.x 部分组件仍为实验性 API，统一在此 OptIn */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BatchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val settings by AppState.settings.collectAsState()
    var linksText by rememberSaveable { mutableStateOf("") }
    var parallel by rememberSaveable { mutableIntStateOf(2) }
    var threads by rememberSaveable { mutableIntStateOf(settings.defaultThreads) }
    val tasks by AppState.batchTasks.collectAsState()
    val running by AppState.batchRunning.collectAsState()

    // 连接数超限弹窗状态
    var showLimitWarning by remember { mutableStateOf(false) }
    var showLimitConfirm by remember { mutableStateOf(false) }
    var pendingUrls by remember { mutableStateOf<List<String>>(emptyList()) }

    fun parseUrls(): List<String> {
        val urls = mutableListOf<String>()
        for (line in linksText.lineSequence()) {
            val l = line.trim()
            if (l.isNotEmpty() && !l.startsWith("#")) urls.add(l)
        }
        return urls
    }

    fun doStart(urls: List<String>, p: Int, t: Int) {
        AppState.startBatch(
            urls, p, t, settings.downloadTimeout,
            autoChunk = settings.autoChunk,
            chunkSizeBytes = settings.chunkSizeMb.coerceIn(1, 64).toLong() * 1024 * 1024,
        )
    }

    fun tryStart() {
        val urls = parseUrls()
        if (urls.isEmpty()) {
            AppState.events.tryEmit("请先粘贴至少一个直链 URL（每行一个）。")
            return
        }
        val p = parallel.coerceIn(1, 5)
        val t = threads.coerceIn(1, 16)
        val totalConn = p * t
        val limit = settings.maxTotalConnections
        if (totalConn > limit) {
            pendingUrls = urls
            if (settings.allowBreakLimit) showLimitConfirm = true else showLimitWarning = true
            return
        }
        doStart(urls, p, t)
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            tryStart()
        } else {
            AppState.events.tryEmit("未授予存储权限，无法写入公共下载目录；可改用“应用专属目录”")
        }
    }

    // Android 13+：通知权限（拒绝不影响下载，仅通知不可见）
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 结果不影响下载 */ }

    fun tryStartWithPermission() {
        // 先收起输入法/释放焦点：修复“粘贴长链接后点按钮第一次无响应”
        focusManager.clearFocus()
        // Android 13+：请求通知权限（不影响下载本身）
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < 29 && settings.downloadLocation == "public" &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            tryStart()
        }
    }

    val totalConn = parallel.coerceIn(1, 5) * threads.coerceIn(1, 16)
    val limit = settings.maxTotalConnections
    val overLimit = totalConn > limit

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // 标题栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("批量下载", style = MaterialTheme.typography.titleMedium)
        }

        // 链接输入
        OutlinedTextField(
            value = linksText,
            onValueChange = { linksText = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            label = { Text("每行一个直链 URL（# 开头的行为注释）") },
            placeholder = { Text("https://example.com/a.zip\nhttps://example.com/b.mp4") },
        )
        Spacer(Modifier.height(8.dp))

        // 控制行（FlowRow：窄屏/横屏自动换行，避免挤在一处）
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("并行任务数：", style = MaterialTheme.typography.bodySmall)
            IntDropdown(label = "1~5", value = parallel, range = 1..5, onChange = { parallel = it })
            Text("每任务线程数：", style = MaterialTheme.typography.bodySmall)
            IntDropdown(label = "1~16", value = threads, range = 1..16, onChange = { threads = it })
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(onClick = { tryStartWithPermission() }, enabled = !running) { Text("开始批量下载") }
            OutlinedButton(onClick = { AppState.stopBatch() }, enabled = running) { Text("暂停全部") }
            Text(
                text = if (overLimit) "总连接数 $totalConn（超过上限 $limit）" else "总连接数 $totalConn",
                style = MaterialTheme.typography.bodySmall,
                color = if (overLimit) MaterialTheme.colorScheme.error else Color(0xFF008800),
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))

        // 任务列表
        LazyColumn(Modifier.weight(1f)) {
            items(tasks, key = { it.iid }) { task -> BatchTaskRow(task) }
        }
    }

    // ---- 连接数超限弹窗 ----
    if (showLimitWarning) {
        AlertDialog(
            onDismissRequest = { showLimitWarning = false },
            title = { Text("连接数超限") },
            text = {
                Text(
                    "总连接数 $totalConn 超过上限 $limit。\n" +
                        "请在设置中调低并行任务数或每任务线程数，或到“设置”里勾选“允许突破连接数上限”。",
                )
            },
            confirmButton = {
                TextButton(onClick = { showLimitWarning = false }) { Text("确定") }
            },
        )
    }
    if (showLimitConfirm) {
        AlertDialog(
            onDismissRequest = { showLimitConfirm = false },
            title = { Text("警告") },
            text = {
                Text(
                    "你已允许突破连接数上限：当前将同时建立 $totalConn 个连接，\n" +
                        "可能导致服务器拒绝服务、IP 被封或文件损坏。\n确认继续？",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLimitConfirm = false
                        doStart(pendingUrls, parallel.coerceIn(1, 5), threads.coerceIn(1, 16))
                    },
                ) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = { showLimitConfirm = false }) { Text("取消") }
            },
        )
    }
}

/** 单行任务：链接 + 状态 + 百分比 + 速率 + 暂停/继续 与 取消 两个按钮（与单项下载同逻辑） */
@Composable
private fun BatchTaskRow(task: AppState.BatchTask) {
    val pausable = task.status in setOf("等待", "排队中", "下载中")
    val deletable = task.status in setOf("等待", "排队中", "下载中", "已暂停", "失败")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Text(
            text = task.url,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = task.status,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(44.dp),
        )
        Text(
            text = task.pct,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(56.dp),
        )
        Text(
            text = task.speed,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp),
        )
        when (task.status) {
            "已暂停" -> TextButton(
                onClick = { AppState.retryBatchTask(task.iid) },
            ) { Text("继续") }
            "失败" -> TextButton(
                onClick = { AppState.retryBatchTask(task.iid) },
            ) { Text("重试") }
            else -> TextButton(
                onClick = { AppState.cancelBatchTask(task.iid) },
                enabled = pausable,
            ) { Text("暂停") }
        }
        TextButton(
            onClick = { AppState.deleteBatchTask(task.iid) },
            enabled = deletable,
        ) { Text("取消") }
    }
}
