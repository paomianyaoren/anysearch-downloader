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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.anysearch.android.core.ApiClient
import com.anysearch.android.core.AppState

/**
 * 搜索页 v1.2.0（夸克式）：
 *  - 顶部大圆角搜索框占满宽（关键词优先）；
 *  - 条数改为紧凑胶囊下拉；按钮点击前收起输入法（修复“粘贴后点按钮无响应”）；
 *  - 结果以卡片列表展示（标题 + 链接 + 复制），可切换查看原文。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchTab(onGoSettings: () -> Unit) {
    val focusManager = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var max by rememberSaveable { mutableIntStateOf(5) }
    var showRaw by rememberSaveable { mutableStateOf(false) }
    val text by AppState.searchText.collectAsState()
    val settings by AppState.settings.collectAsState()
    val apiKey by AppState.apiKey.collectAsState()
    val saver = rememberTextSaver { msg -> AppState.events.tryEmit(msg) }

    val items = remember(text) { ApiClient.parseSearchResults(text) }

    fun doSearch() {
        focusManager.clearFocus()
        if (query.isBlank()) {
            AppState.events.tryEmit("请输入搜索关键词。")
        } else {
            showRaw = false
            AppState.search(query.trim(), max)
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // 大搜索框（夸克式圆角大输入）
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(26.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = { Text("搜索关键词") },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Clear, contentDescription = "清空")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch() }),
        )

        // 未配置 Key 提示条（精简：可点跳转设置）
        if (apiKey.isBlank()) {
            Spacer(Modifier.height(8.dp))
            Surface(
                onClick = onGoSettings,
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    "未配置 API Key（匿名调用限流更严），点此前往设置",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 紧凑条数胶囊
            StringDropdown(
                label = "条数",
                options = (1..10).map { "$it 条" },
                selectedIndex = max - 1,
                onSelect = { max = it + 1 },
                modifier = Modifier.width(116.dp),
            )
            Button(onClick = { doSearch() }) { Text("搜索") }
            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    if (text.isBlank()) {
                        AppState.events.tryEmit("当前没有可保存的搜索结果。")
                    } else {
                        saver("搜索结果.txt", text)
                    }
                },
            ) { Text("保存") }
            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    AppState.searchText.value = ""
                },
            ) { Text("清空") }
        }

        Spacer(Modifier.height(12.dp))

        if (items.isNotEmpty() && !showRaw) {
            ResultCards(items, Modifier.weight(1f).fillMaxWidth())
            TextButton(
                onClick = { showRaw = true },
            ) { Text("查看原文") }
        } else {
            if (items.isNotEmpty()) {
                TextButton(
                    onClick = { showRaw = false },
                ) { Text("返回卡片") }
            }
            OutputText(text, settings.maxDisplayLen, Modifier.weight(1f).fillMaxWidth())
        }
    }
}
