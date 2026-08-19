package com.anysearch.android.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * AnySearch API 客户端（对应桌面版 anysearch_asset_search.py）。
 *
 * 协议：POST https://api.anysearch.com/mcp
 *      JSON-RPC 2.0，method="tools/call"，Authorization: Bearer <KEY>
 *
 * 免责声明：本工具仅供学习与合法用途使用。使用 AnySearch API 须遵守其服务条款；
 * 请勿将 API Key 硬编码进代码或公开泄露；请勿抓取/下载违反版权、隐私或法律法规的内容。
 */
object ApiClient {

    const val ENDPOINT = "https://api.anysearch.com/mcp"

    @Volatile
    var client: OkHttpClient = buildClient(null)

    /** 自定义请求头（由 AppState 在设置保存时同步），API 请求会带上 */
    @Volatile
    var customHeaders: Map<String, String> = emptyMap()

    private fun buildClient(proxy: Proxy?): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        if (proxy != null) b.proxy(proxy)
        return b.build()
    }

    /** 应用 HTTP/HTTPS 代理（对应桌面版 apply_proxy）；解析失败则直连 */
    fun applyProxy(useProxy: Boolean, proxyUrl: String) {
        client = buildClient(if (useProxy && proxyUrl.isNotBlank()) parseProxy(proxyUrl) else null)
    }

    /** "127.0.0.1:7890" / "http://127.0.0.1:7890" -> java.net.Proxy；非法输入返回 null */
    fun parseProxy(raw: String): Proxy? {
        var u = raw.trim()
        if (u.isEmpty()) return null
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        return try {
            val uri = URI(u)
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 80
            Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
        } catch (e: Exception) {
            null
        }
    }

    private fun buildBody(tool: String, arguments: Map<String, Any>): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", tool)
                put("arguments", JSONObject(arguments))
            })
        }.toString()
    }

    /**
     * 调用 AnySearch 工具。返回 (解析后的 JSONObject 或 null, 原始响应文本)。
     * 与桌面版一致：响应不是 JSON 时返回 (null, raw)。
     */
    suspend fun call(
        apiKey: String,
        tool: String,
        arguments: Map<String, Any>,
        timeoutSeconds: Int,
    ): Pair<JSONObject?, String> = withContext(Dispatchers.IO) {
        val body = buildBody(tool, arguments)
        val rb = Request.Builder()
            .url(ENDPOINT)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("X-Anysearch-Client", "mcp/1.0.0")
            .header("User-Agent", "anysearch-android-client/0.1")
        for ((k, v) in customHeaders) rb.header(k, v)
        if (apiKey.isNotBlank()) rb.header("Authorization", "Bearer $apiKey")
        val request = rb.build()

        val resp = withTimeout(timeoutSeconds.coerceAtLeast(5) * 1000L) {
            client.newCall(request).execute()
        }
        resp.use {
            val raw = it.body?.string() ?: ""
            val obj = try {
                JSONObject(raw)
            } catch (e: Exception) {
                null
            }
            obj to raw
        }
    }

    /** MCP 工具结果常见结构：result.content[0].text；没有则 null（对应桌面版 extract_result_text） */
    fun extractResultText(obj: JSONObject?): String? {
        if (obj == null) return null
        val result = obj.optJSONObject("result") ?: return null
        val content = result.optJSONArray("content") ?: return null
        if (content.length() == 0) return null
        val first = content.optJSONObject(0) ?: return null
        val text = first.optString("text", "")
        return text.ifEmpty { null }
    }

    /**
     * 从文本/Markdown 中提取所有 http(s) URL（去重、保持顺序）。
     * 注意：桌面版 anysearch_asset_search.py 里此函数只有调用、没有定义（其“提取链接”实际会崩溃），
     * 安卓版按 README 描述的语义实现，并与修复后的桌面版 extract_links 保持一致：
     * 只提取静态文本/Markdown 里的链接，JS 动态渲染的链接拿不到；
     * 排除集中包含中英文常见标点，防止把标点/中文文字吞进链接。
     */
    fun extractLinks(text: String): List<String> {
        val re = Regex("(?i)https?://[^\\s<>()\\[\\]\"'`，。；：！？、（）【】《》「」『』]+")
        val out = LinkedHashSet<String>()
        for (m in re.findAll(text)) {
            var u = m.value.trimEnd(
                '.', ',', ';', ':', '!', '?', ')', ']', '}', '）', '】', '》',
                '，', '。', '；', '！', '？', '、', '"', '\'',
            )
            if (u.isNotBlank()) out.add(u)
        }
        return out.toList()
    }

    /** 搜索结果卡片条目：标题 + 链接 */
    data class SearchResultItem(val title: String, val url: String)

    /**
     * 把搜索结果 Markdown 解析为 (标题, 链接) 卡片列表（用于夸克式结果卡片）。
     * 标题来自 "### 1. 标题" 一类行，链接来自 **URL**: 或正文中的 http(s) 地址；
     * 解析不到标题时以 URL 作为标题。
     */
    fun parseSearchResults(text: String): List<SearchResultItem> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<SearchResultItem>()
        var curTitle = ""
        val titleRe = Regex("^#{1,4}\\s*(?:\\d+[.、]\\s*)?(.+?)\\s*$")
        val urlRe = Regex("(?i)https?://[^\\s<>()\\[\\]\"'，。；：！？、（）【】《》「」『』]+")
        for (line in text.lineSequence()) {
            val t = titleRe.find(line.trim())?.groupValues?.get(1)?.trim()
            if (!t.isNullOrBlank() && !t.contains("http")) {
                curTitle = t
                continue
            }
            val m = urlRe.find(line)
            if (m != null) {
                val u = m.value.trimEnd(
                    '.', ',', ';', ':', '!', '?', ')', ']', '}', '）', '】', '》',
                    '，', '。', '；', '！', '？', '、', '"', '\'',
                )
                if (u.isNotBlank() && seen.add(u)) {
                    out.add(SearchResultItem(curTitle.ifBlank { u }, u))
                    curTitle = ""
                }
            }
        }
        return out
    }
}
