package com.anysearch.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.anysearch.android.core.ApiClient
import com.anysearch.android.core.AppState

/**
 * 网页抓取页 v1.2.0（夸克式）：
 *  - 圆角 URL 输入 + 按钮行（点击前收起输入法）；
 *  - 结果卡片列表（标题 + 链接 + 复制），可切换查看原文。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtractTab() {
    val focusManager = LocalFocusManager.current
    var url by rememberSaveable { mutableStateOf("") }
    var showRaw by rememberSaveable { mutableStateOf(false) }
    val text by AppState.extractText.collectAsState()
    val settings by AppState.settings.collectAsState()
    val saver = rememberTextSaver { msg -> AppState.events.tryEmit(msg) }

    val items = remember(text) { ApiClient.parseSearchResults(text) }

    fun requireUrl(): String {
        focusManager.clearFocus()
        val u = url.trim()
        if (u.isEmpty()) {
            AppState.events.tryEmit("请输入网页 URL。")
        }
        return u
    }

    fun startExtract(linksOnly: Boolean) {
        val u = requireUrl()
        if (u.isEmpty()) return
        showRaw = false
        if (linksOnly) AppState.extractLinks(u) else AppState.extract(u)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(26.dp),
            placeholder = { Text("https://example.com/page") },
            trailingIcon = {
                if (url.isNotEmpty()) {
                    IconButton(onClick = { url = "" }) {
                        Icon(Icons.Filled.Clear, contentDescription = "清空")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { startExtract(false) }),
        )

        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(onClick = { startExtract(false) }) { Text("开始抓取") }
            OutlinedButton(onClick = { startExtract(true) }) { Text("提取链接") }
            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    if (text.isBlank()) {
                        AppState.events.tryEmit("当前没有可保存的抓取内容。")
                    } else {
                        saver("抓取结果.md", text)
                    }
                },
            ) { Text("保存") }
            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    AppState.extractText.value = ""
                },
            ) { Text("清空") }
        }

        Spacer(Modifier.height(12.dp))

        if (items.isNotEmpty() && !showRaw) {
            ResultCards(items, Modifier.weight(1f).fillMaxWidth())
            TextButton(onClick = { showRaw = true }) { Text("查看原文") }
        } else {
            if (items.isNotEmpty()) {
                TextButton(onClick = { showRaw = false }) { Text("返回卡片") }
            }
            OutputText(text, settings.maxDisplayLen, Modifier.weight(1f).fillMaxWidth())
        }
    }
}
