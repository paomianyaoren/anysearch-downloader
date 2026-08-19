package com.anysearch.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.anysearch.android.core.AppState

/**
 * 应用根 v1.2.0（夸克式布局）：
 *  - 底部 4 Tab 导航（搜索 / 抓取 / 下载 / 设置）+ 顶部细状态条；
 *  - API Key 移入设置页（首页更精简，未配置时搜索页显示跳转提示条）。
 */
@Composable
fun AppRoot() {
    val status by AppState.status.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        AppState.events.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }

    var showBatch by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                NavigationBar {
                    val titles = listOf("搜索", "抓取", "下载", "设置")
                    titles.forEachIndexed { i, title ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(tabIcon(i), contentDescription = title) },
                            label = { Text(title) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (showBatch) {
                BatchScreen(onBack = { showBatch = false })
            } else {
                when (tab) {
                    0 -> SearchTab(onGoSettings = { tab = 3 })
                    1 -> ExtractTab()
                    2 -> DownloadTab(onOpenBatch = { showBatch = true })
                    else -> SettingsTab()
                }
            }
        }
    }
    // 全局解析交互弹窗（下载选项 / 人机验证）：单任务与批量共用
    ResolveDialogs()
}

@Composable
private fun tabIcon(index: Int): ImageVector = when (index) {
    0 -> Icons.Filled.Search
    1 -> Icons.AutoMirrored.Filled.List
    2 -> Icons.Filled.Download
    else -> Icons.Filled.Settings
}
