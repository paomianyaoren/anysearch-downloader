package com.anysearch.android.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anysearch.android.core.AppState

/** 整数下拉选择（条数 / 线程数等）。菜单类 API 在 material3 1.3.x 仍为实验性，故显式 OptIn */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntDropdown(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value.toString(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        // material3 1.3.x 已移除 ExposedDropdownMenu：改用普通 DropdownMenu（经 menuAnchor 锚定）
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (n in range) {
                DropdownMenuItem(
                    text = { Text(n.toString()) },
                    onClick = {
                        onChange(n)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 字符串下拉选择（如镜像策略）。菜单类 API 在 material3 1.3.x 仍为实验性，故显式 OptIn */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StringDropdown(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = options.getOrElse(selectedIndex) { "" },
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        onSelect(i)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 长文本输出区：按 maxLen 截断显示（保留全文供保存），可滚动、可选中复制 */
@Composable
fun OutputText(text: String, maxLen: Int, modifier: Modifier = Modifier) {
    val shown = if (maxLen > 0 && text.length > maxLen) {
        "[内容过长，以下仅显示前 $maxLen 字符；完整内容可用“保存”功能保存到文件。]\n\n" + text.take(maxLen)
    } else {
        text
    }
    Column(modifier.verticalScroll(rememberScrollState())) {
        SelectionContainer {
            Text(
                text = shown.ifEmpty { "（暂无内容）" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 通过系统“另存为”对话框保存文本；结果经 onFinished 回传（如发给全局提示） */
@Composable
fun rememberTextSaver(onFinished: (String) -> Unit): (fileName: String, text: String) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val text = pending
        pending = ""
        if (uri == null) {
            onFinished("已取消保存")
        } else {
            val result = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("无法打开输出流")
            }
            onFinished(
                if (result.isSuccess) "已保存" else "保存失败：${result.exceptionOrNull()?.message}",
            )
        }
    }
    return remember(launcher) { { fileName, text -> pending = text; launcher.launch(fileName) } }
}

/**
 * 下载保存位置选择（系统下载目录 / 应用专属目录 / SAF 自定义文件夹）。
 * 只改动 downloadLocation 一项，其余设置保持 AppState 中最新已保存的值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationPickerRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            AppState.saveSettings(AppState.settings.value.copy(downloadLocation = "saf:$uri"))
            AppState.events.tryEmit("下载位置已设为所选文件夹")
        }
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("保存位置：", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = AppState.currentTargetLabel(),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { menu = true }) { Text("选择…") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("系统公共下载目录（Download）") },
                    onClick = {
                        AppState.saveSettings(AppState.settings.value.copy(downloadLocation = "public"))
                        menu = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("应用专属目录（无需权限）") },
                    onClick = {
                        AppState.saveSettings(AppState.settings.value.copy(downloadLocation = "app"))
                        menu = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("选择自定义文件夹…") },
                    onClick = {
                        menu = false
                        safLauncher.launch(null)
                    },
                )
            }
        }
    }
}

/** 灰色小字提示 */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** 圆圈问号按钮（夸克式 [?]）：所有超长提示统一收纳到它背后；48dp 可点区域，26dp 视觉圆 */
@Composable
fun HintQuestionButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(modifier = Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                Text(
                    "?",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** 带 [?] 的标签：点击问号弹出完整提示对话框（超长说明不占版面） */
@Composable
fun HintLabel(title: String, hint: String, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(2.dp))
        HintQuestionButton(onClick = { show = true })
    }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(title) },
            text = { Text(hint, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { show = false }) { Text("知道了") } },
        )
    }
}
