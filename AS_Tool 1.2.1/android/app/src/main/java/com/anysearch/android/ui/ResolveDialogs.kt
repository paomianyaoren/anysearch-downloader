package com.anysearch.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anysearch.android.core.AppState
import com.anysearch.android.core.CaptchaChallenge
import com.anysearch.android.core.LinkOption
import com.anysearch.android.core.ResolveResult

/** 选项描述（对应桌面版 _option_text） */
fun optionLabel(o: LinkOption): String {
    val name = o.label.ifBlank { o.url.substringBefore('?').substringAfterLast('/') }
    val note = if (o.note.isNotBlank()) "（${o.note}）" else ""
    return when (o.kind) {
        "image" -> {
            val size = when {
                o.width != null && o.height != null -> "${o.width}×${o.height}"
                o.width != null -> "${o.width}px"
                else -> "图片"
            }
            "图片 $size · ${name.take(50)}$note"
        }
        "video" -> "视频 · ${name.take(50)}$note"
        "audio" -> "音频 · ${name.take(50)}$note"
        else -> "文件 · ${name.take(50)}$note"
    }
}

/**
 * 全局解析交互弹窗：下载选项选择 + 人机验证（单任务与批量共用）。
 * 挂载在 AppRoot，由 AppState.pendingOptions / pendingCaptcha 驱动。
 */
@Composable
fun ResolveDialogs() {
    val pendingOptions by AppState.pendingOptions.collectAsState()
    pendingOptions?.let { rr ->
        OptionsDialog(rr)
    }
    val pendingCaptcha by AppState.pendingCaptcha.collectAsState()
    pendingCaptcha?.let { ch ->
        CaptchaDialog(ch)
    }
}

@Composable
private fun OptionsDialog(rr: ResolveResult) {
    val selected = remember(rr) { mutableStateMapOf<Int, Boolean>() }
    AlertDialog(
        onDismissRequest = { AppState.chooseOptions(null) },
        title = { Text("下载选项 · ${rr.title.ifBlank { "选择" }}") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                rr.options.forEachIndexed { i, o ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected[i] = !(selected[i] ?: false)
                            }
                            .padding(vertical = 2.dp),
                    ) {
                        Checkbox(
                            checked = selected[i] ?: false,
                            onCheckedChange = { selected[i] = it },
                        )
                        Column {
                            Text(optionLabel(o), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                o.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AppState.chooseOptions(
                    rr.options.filterIndexed { i, _ -> selected[i] == true }.map { it.url },
                )
            }) { Text("下载选中") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { AppState.chooseOptions(rr.options.map { it.url }) }) {
                    Text("全部下载")
                }
                TextButton(onClick = { AppState.chooseOptions(null) }) { Text("取消") }
            }
        },
    )
}

@Composable
private fun CaptchaDialog(ch: CaptchaChallenge) {
    val answers = remember(ch) { mutableStateMapOf<String, String>() }
    val bitmap = remember(ch) {
        ch.captchaBytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }
    AlertDialog(
        onDismissRequest = { AppState.answerCaptcha(null) },
        title = { Text("人机验证") },
        text = {
            Column {
                Text(ch.hint, style = MaterialTheme.typography.bodySmall)
                if (bitmap != null) {
                    Image(
                        bitmap.asImageBitmap(),
                        contentDescription = "验证码",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .padding(top = 8.dp),
                    )
                } else if (ch.captchaUrl.isNotBlank()) {
                    Text(
                        "验证码图片地址：${ch.captchaUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ch.fields.filter { it.needsInput }.forEach { f ->
                    var v by remember(ch) { mutableStateOf("") }
                    OutlinedTextField(
                        value = v,
                        onValueChange = { v = it; answers[f.name] = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(f.name) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { AppState.answerCaptcha(answers.toMap()) }) { Text("提交") }
        },
        dismissButton = {
            TextButton(onClick = { AppState.answerCaptcha(null) }) { Text("取消") }
        },
    )
}
