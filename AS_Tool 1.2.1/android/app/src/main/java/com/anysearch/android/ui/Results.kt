package com.anysearch.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anysearch.android.core.ApiClient

/** 复制文本到系统剪贴板（安卓实现；PC 端为链接列表，两端实现可不同） */
fun copyTextToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * 夸克式结果卡片列表：每条 = 标题 + 链接 + 一键复制按钮；
 * 顶部提供“复制全部链接”（每行一条，不影响逐个复制）。
 */
@Composable
fun ResultCards(
    items: List<ApiClient.SearchResultItem>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "结果 ${items.size}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = items.isNotEmpty(),
                onClick = {
                    val links = items.map { it.url }
                    copyTextToClipboard(context, "links", links.joinToString("\n"))
                    com.anysearch.android.core.AppState.events.tryEmit("已复制 ${links.size} 条链接")
                },
            ) { Text("复制全部链接") }
        }
        Spacer(Modifier.height(4.dp))
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("（暂无结果）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.url }) { item -> ResultCard(item) }
            }
        }
    }
}

/** 单张结果卡片：标题 + 链接（最多两行）+ 复制按钮 */
@Composable
private fun ResultCard(item: ApiClient.SearchResultItem) {
    val context = LocalContext.current
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.padding(horizontal = 8.dp))
                TextButton(
                    onClick = { copyTextToClipboard(context, "url", item.url) },
                ) { Text("复制") }
            }
            Text(
                text = item.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
