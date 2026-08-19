package com.anysearch.android.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 链接解析失败（网络错误 / 被拒绝 / 超跳转上限等） */
class ResolveError(message: String) : Exception(message)

/** 一个可下载候选（对应桌面版 LinkOption）；referer 为该候选所属页面地址（抗防盗链） */
data class LinkOption(
    val url: String,
    val label: String = "",
    val kind: String = "file",
    val width: Int? = null,
    val height: Int? = null,
    val note: String = "",
    val source: String = "",
    val referer: String? = null,
)

/** 识别到的人机验证（对应桌面版 CaptchaChallenge） */
data class CaptchaChallenge(
    val action: String,
    val method: String = "POST",
    val fields: List<Field> = emptyList(),
    val captchaUrl: String = "",
    val captchaBytes: ByteArray? = null,
    val captchaType: String = "",
    val pageUrl: String = "",
    val hint: String = "该页面需要人机验证，请输入图片中的验证码后继续",
) {
    data class Field(val name: String, val value: String = "", val needsInput: Boolean = false)
}

/** 解析结果（对应桌面版 ResolveResult） */
data class ResolveResult(
    val finalUrl: String,
    val options: List<LinkOption> = emptyList(),
    val title: String = "",
    val hops: List<String> = emptyList(),
    val note: String = "",
    val isPage: Boolean = false,
    val captcha: CaptchaChallenge? = null,
)

/**
 * 链接解析器 v1.2.1（对应桌面版 anysearch_link_resolver.py）。
 *
 * 直链优先：下载失败（返回网页 / 被拒绝）时才由 AppState 调用本解析器。
 * 能力：HTTP 302 / meta 刷新 / JS 跳转（含三元按 true/false 求值）；
 * JSON-LD / og:image / srcset / 下载按钮 / 内联 JS 变量 / 参数嵌套直链提取；
 * 图片尺寸 token 枚举 + HEAD 验证；验证码表单识别与交互提交（带会话 Cookie）；
 * Cloudflare 挑战页明确提示；瞬时网络错误自动重试 1 次（4xx 风控不重试）。
 */
class LinkResolver(private val downloader: Downloader) {

    companion object {
        const val MAX_HOPS = 8
        const val MAX_PAGE_BYTES = 2 * 1024 * 1024
        const val MAX_OPTIONS = 40
        const val MAX_VARIANTS = 16
        const val MAX_NESTED = 20

        val IMAGE_SIZE_TOKENS = intArrayOf(340, 480, 640, 960, 1280, 1920, 2048, 2560, 3840, 4096)
        val IMAGE_W_PARAMS = intArrayOf(320, 480, 640, 800, 1024, 1200, 1260, 1600, 1920, 2560, 3840, 4096)

        val FILE_EXT = setOf(
            "apk", "xapk", "apks", "zip", "7z", "rar", "exe", "msi", "dmg", "pkg", "deb",
            "rpm", "iso", "img", "tgz", "gz", "bz2", "xz", "zst", "jar", "bin", "crx",
            "torrent", "unitypackage",
            "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "ts",
            "mp3", "wav", "flac", "ogg", "m4a", "aac", "opus",
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "tif", "tiff", "avif", "heic",
            "pdf", "epub", "mobi", "azw3", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "csv", "txt", "md", "rtf", "chm", "djvu",
        )

        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "tif", "tiff", "avif", "heic")
        val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "ts")
        val AUDIO_EXT = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "opus")

        val CAPTCHA_KEYWORDS = listOf(
            "captcha", "verify", "verif", "code", "yzm", "vcode", "seccode", "rand",
            "kaptcha", "authimg", "security", "challenge",
        )

        private val HTML_MARKERS = listOf("<!doctype html", "<html", "<head")

        fun looksHtml(contentType: String, head: ByteArray): Boolean {
            val ct = contentType.lowercase(Locale.ROOT)
            if ("text/html" in ct) return true
            val low = String(head, 0, minOf(head.size, 1024), Charsets.ISO_8859_1)
                .trimStart().lowercase(Locale.ROOT)
            return low.startsWith("<!doctype html") || low.startsWith("<html") || "<head" in low
        }
    }

    /** 会话 Cookie（解析流程内共享，验证码提交依赖它） */
    private class MemoryCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = store.getOrPut(url.host) { mutableListOf() }
            synchronized(list) {
                cookies.forEach { c ->
                    list.removeAll { it.name == c.name }
                    list.add(c)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store[url.host]?.toList() ?: emptyList()
    }

    private fun sessionClient(): OkHttpClient = downloader.client.newBuilder()
        .cookieJar(MemoryCookieJar())
        .build()

    private fun headers(referer: String? = null): okhttp3.Headers {
        val b = okhttp3.Headers.Builder()
            .add("User-Agent", Downloader.BROWSER_UA)
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .add("Accept-Encoding", "identity")
            .add("Upgrade-Insecure-Requests", "1")
            .add("Sec-Fetch-Dest", "document")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "none")
        for ((k, v) in downloader.customHeaders) b.add(k, v)
        if (referer != null) b.add("Referer", referer)
        return b.build()
    }

    private data class Fetched(
        val code: Int,
        val finalUrl: String,
        val contentType: String,
        val body: ByteArray,
    )

    /** 抓取一页（限长 MAX_PAGE_BYTES；首块 8KB 判定非网页即停止，避免重复传输文件体） */
    private suspend fun fetch(
        client: OkHttpClient,
        url: String,
        timeoutSeconds: Long,
        cancel: AtomicBoolean,
        referer: String? = null,
        method: String = "GET",
        body: ByteArray? = null,
    ): Fetched {
        if (cancel.get()) throw DownloadCancelled()
        val rb = Request.Builder().url(url).headers(headers(referer))
        if (method != "GET") {
            rb.method(method, body?.toRequestBody())
        }
        val call = client.newCall(rb.build())
        return try {
            val resp = withTimeoutOrNull(timeoutSeconds.coerceAtLeast(5) * 1000L) { call.execute() }
                ?: run { call.cancel(); throw ResolveError("抓取超时：$url") }
            resp.use {
                if (cancel.get()) throw DownloadCancelled()
                val ct = resp.header("Content-Type") ?: ""
                if (resp.code >= 400) {
                    throw ResolveError("访问被拒绝（HTTP ${resp.code}）：${resp.request.url}")
                }
                val input = resp.body?.byteStream()
                if (input == null) {
                    Fetched(resp.code, resp.request.url.toString(), ct, ByteArray(0))
                } else {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(65536)
                    var total = 0
                    var first = true
                    while (total < MAX_PAGE_BYTES) {
                        if (cancel.get()) throw DownloadCancelled()
                        val want = if (first) 8192 else 65536
                        val n = input.read(buf, 0, minOf(want, MAX_PAGE_BYTES - total))
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        first = false
                        if (total <= 8192) continue
                        if (!looksHtml(ct, out.toByteArray().copyOfRange(0, minOf(total, 8192)))) {
                            break // 非网页：直链，不再读整个文件
                        }
                    }
                    Fetched(resp.code, resp.request.url.toString(), ct, out.toByteArray())
                }
            }
        } catch (e: ResolveError) {
            throw e
        } catch (e: DownloadCancelled) {
            throw e
        } catch (e: Exception) {
            if (cancel.get()) throw DownloadCancelled()
            throw ResolveError("无法访问 $url：${e.message}")
        }
    }

    /** 带瞬态重试的抓取：网络错误/5xx 自动重试 retries 次；4xx 不重试 */
    private suspend fun fetchHop(
        client: OkHttpClient,
        url: String,
        timeoutSeconds: Long,
        cancel: AtomicBoolean,
        referer: String?,
        retries: Int,
    ): Fetched {
        for (attempt in 0..retries) {
            if (cancel.get()) throw DownloadCancelled()
            try {
                val f = fetch(client, url, timeoutSeconds, cancel, referer)
                if (f.code in 500..599 && attempt < retries) {
                    delayInterruptible(1500, cancel)
                    continue
                }
                return f
            } catch (e: ResolveError) {
                // 含 HTTP 状态码的（4xx）不重试；其余视为瞬态网络错误
                if (Regex("HTTP \\d+").containsMatchIn(e.message ?: "")) throw e
                if (attempt >= retries) throw e
                delayInterruptible(1500, cancel)
            }
        }
        throw ResolveError("抓取失败：$url")
    }

    private suspend fun delayInterruptible(ms: Long, cancel: AtomicBoolean) {
        var waited = 0L
        while (waited < ms) {
            if (cancel.get()) throw DownloadCancelled()
            delay(100)
            waited += 100
        }
    }

    // ==================== 页面分析 ====================

    private fun htmlTitle(html: String): String =
        Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.getOrNull(1)?.trim()?.take(120) ?: ""

    private fun decodeHtml(s: String): String = runCatching {
        android.text.Html.fromHtml(s, 0).toString()
    }.getOrDefault(s)

    private fun urlJoin(base: String, u: String): String {
        if (u.startsWith("//")) return "https:$u"
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        return runCatching {
            base.toHttpUrlOrNull()?.resolve(u)?.toString() ?: u
        }.getOrDefault(u)
    }

    private data class KindSize(val kind: String?, val ext: String, val width: Int?)

    private fun kindSize(url: String): KindSize {
        val path = runCatching { url.toHttpUrlOrNull()?.encodedPath }.getOrNull()
            ?: url.substringBefore('?')
        val lowerPath = path.lowercase(Locale.ROOT)
        val ext = lowerPath.substringAfterLast('.', "").ifBlank { "" }
        var width: Int? = null
        for (tok in IMAGE_SIZE_TOKENS) {
            if (Regex("[_\\-]$tok(\\..*)?$").containsMatchIn(lowerPath) ||
                Regex("[_\\-]$tok\\b").containsMatchIn(lowerPath)
            ) {
                width = tok
                break
            }
        }
        if (width == null) {
            val q = runCatching { url.toHttpUrlOrNull()?.query }.getOrNull() ?: ""
            val m = Regex("(^|&)w=(\\d{2,5})(&|$)").find(q.lowercase(Locale.ROOT))
            if (m != null) width = m.groupValues[2].toIntOrNull()
        }
        val kind = when {
            ext in IMAGE_EXT -> "image"
            ext in VIDEO_EXT -> "video"
            ext in AUDIO_EXT -> "audio"
            ext in FILE_EXT -> "file"
            else -> null
        }
        return KindSize(kind, ext, width)
    }

    private fun stemOf(url: String): String {
        var name = url.substringBefore('?').substringAfterLast('/')
        name = Regex("__?\\d{2,5}(?=\\.\\w+$)").replace(name, "")
        name = Regex("@\\dx(?=\\.\\w+$)").replace(name, "")
        return name.substringBeforeLast('.')
    }

    private fun validMediaUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val path = runCatching { url.toHttpUrlOrNull()?.encodedPath }.getOrNull()
            ?: return false
        if (Regex("\\.(css|js|json|woff2?|ttf|eot|ico)(\\?|$)").containsMatchIn(path.lowercase(Locale.ROOT))) {
            return false
        }
        return true
    }

    private fun parseJsVars(html: String): List<Triple<String, String, Int>> {
        val flags = HashMap<String, Boolean>()
        Regex("var\\s+(\\w+)\\s*=\\s*(true|false)\\s*;", RegexOption.IGNORE_CASE).findAll(html).forEach {
            flags[it.groupValues[1].lowercase(Locale.ROOT)] = it.groupValues[2].equals("true", true)
        }
        val found = HashMap<String, Pair<Int, String>>()
        val valueRe = "(?:[^;\"'\\n]|\"[^\"]*\"|'[^']*')*(?:\"[^\"]*\"|'[^']*')?"
        Regex("var\\s+(\\w+)\\s*=\\s*($valueRe)\\s*;", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val name = m.groupValues[1]
            val valStr = m.groupValues[2].trim()
            val tern = Regex(
                "^(\\w+)\\s*\\?\\s*(?:\"([^\"]*)\"|'([^']*)')\\s*:\\s*(?:\"([^\"]*)\"|'([^']*)')$",
            ).find(valStr)
            val url: String = if (tern != null) {
                val cond = flags[tern.groupValues[1].lowercase(Locale.ROOT)]
                val a = tern.groupValues[2].ifBlank { tern.groupValues[3] }
                val b = tern.groupValues[4].ifBlank { tern.groupValues[5] }
                if (cond == false) b else a
            } else {
                Regex("[\"'](https?://[^\"']*)[\"']").find(valStr)?.groupValues?.getOrNull(1) ?: ""
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@forEach
            val low = name.lowercase(Locale.ROOT)
            val score = listOf("download", "down", "url", "link", "href", "file", "src")
                .indexOfFirst { it in low }
                .let { if (it >= 0) 7 - it else 0 }
            if (score > (found[url]?.first ?: 0)) found[url] = score to name
        }
        Regex("(?:window\\.)?location(?:\\.href)?\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m ->
                val url = m.groupValues[2].trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    if (7 > (found[url]?.first ?: 0)) found[url] = 7 to "location"
                }
            }
        return found.entries.sortedByDescending { it.value.first }
            .map { Triple(it.key, it.value.second, it.value.first) }
    }

    private fun findMetaRefresh(html: String, base: String): String {
        val tags = Regex("<meta\\b[^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html).map { it.value }.toList()
        for (tag in tags) {
            if (!Regex("http-equiv\\s*=\\s*[\"']?refresh[\"']?", RegexOption.IGNORE_CASE)
                    .containsMatchIn(tag)
            ) continue
            val cm = Regex(
                """content\s*=\s*["']?\s*[\d.]+\s*;\s*url\s*=\s*["']?([^"'>\s]+)""",
                RegexOption.IGNORE_CASE,
            ).find(tag)
            if (cm != null) return urlJoin(base, decodeHtml(cm.groupValues[1]))
        }
        return ""
    }

    /** 通用：从参数里嵌套的直链提取（url=/image-url=/target=/redirect= 等，编码/协议相对均可） */
    private fun extractNestedUrls(html: String, pageId: String): List<String> {
        val paramRe = Regex(
            """(?:download[_\-]?url|image[_\-]?url|redirect|target|href|src|link|url)\s*=\s*["']?([^"'\s>]+)["']?""",
            RegexOption.IGNORE_CASE,
        )
        val found = HashMap<String, Int>()
        paramRe.findAll(html).forEach { m ->
            var v = decodeHtml(m.groupValues[1])
            v = runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
            val positions = Regex("https?://").findAll(v).map { it.range.first }.toList()
            if (positions.size < 2) return@forEach
            var u = v.substring(positions.last())
            u = Regex("[&\"'<>\\s]").split(u).first()
            u = u.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
            if (!u.startsWith("http://") && !u.startsWith("https://")) return@forEach
            val ctx = html.substring(
                (m.range.first - 600).coerceAtLeast(0),
                (m.range.last + 600).coerceAtMost(html.length),
            )
            val hitId = pageId.isNotEmpty() && pageId in ctx
            val score = if (hitId) 2 else 0
            if (score > (found[u] ?: -1)) found[u] = score
        }
        var items = found.entries.sortedByDescending { it.value }
        if (pageId.isNotEmpty() && items.any { it.value > 0 }) {
            items = items.filter { it.value > 0 }
        }
        return items.take(MAX_NESTED).map { it.key }
    }

    private fun extractOptions(html: String, base: String, title: String): List<LinkOption> {
        val seen = LinkedHashMap<String, LinkOption>()
        var mainStem = ""

        fun add(urlRaw: String, label: String, kind: String, width: Int?, height: Int?,
                note: String, source: String) {
            var url = decodeHtml(urlRaw.trim())
            if (url.startsWith("//")) url = "https:$url"
            if (!validMediaUrl(url)) return
            if (seen.containsKey(url)) return
            seen[url] = LinkOption(url, label, kind, width, height, note, source, base)
        }

        fun toInt(v: Any?): Int? = when (v) {
            is Int -> v
            is Double -> v.toInt()
            is String -> v.trim().toIntOrNull()
            is JSONObject -> toInt(v.opt("value"))
            else -> null
        }

        fun walkJson(obj: Any?, isRep: Boolean) {
            when (obj) {
                is JSONObject -> {
                    val content = obj.optString("contentUrl").ifBlank { obj.optString("embedUrl") }
                    val w = toInt(obj.opt("width"))
                    val h = toInt(obj.opt("height"))
                    if (content.isNotBlank()) {
                        val ks = kindSize(content)
                        val rep = obj.optBoolean("representativeOfPage") || isRep
                        if (ks.kind == "image" && rep && mainStem.isBlank()) {
                            val stem = stemOf(content)
                            if (stem.isNotBlank()) mainStem = stem
                        }
                        val note = if (w != null && h != null && (ks.width ?: 0) < maxOf(w, h)) {
                            "页面标注原图 ${w}×${h}（原图需站点签发/登录，工具已列出全部公开档位）"
                        } else ""
                        add(
                            content, obj.optString("name"), ks.kind ?: "file",
                            ks.width ?: w, h, note,
                            "jsonld" + if (rep) "·主图" else "",
                        )
                    }
                    val repNext = isRep || obj.optBoolean("representativeOfPage")
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        walkJson(obj.opt(keys.next()), repNext)
                    }
                }
                is JSONArray -> for (i in 0 until obj.length()) walkJson(obj.opt(i), isRep)
                else -> {}
            }
        }

        val jsonldRegex = Regex(
            "<script[^>]+application/ld\\+json[^>]*>(.*?)</script>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        jsonldRegex.findAll(html).forEach { m ->
            runCatching { walkJson(JSONObject(m.groupValues[1]), false) }
        }

        // 2) og:image / og:video / og:audio
        Regex(
            """<meta[^>]+(?:property|name)=["'](og:image|og:video|og:audio|twitter:image)["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).findAll(html).forEach { m ->
            val prop = m.groupValues[1].lowercase(Locale.ROOT)
            val u = urlJoin(base, m.groupValues[2])
            val ks = kindSize(u)
            val kind = when {
                "image" in prop -> "image"
                "video" in prop -> "video"
                else -> "audio"
            }
            val stem = stemOf(u)
            if (stem.isNotBlank() && mainStem.isBlank()) mainStem = stem
            add(u, title, kind, ks.width, null, "", "og")
        }

        // 3) srcset / img src
        val imgTags = Regex("<img\\b[^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html).map { it.value }.toList()
        for (tag in imgTags) {
            val sm = Regex("""(?:srcset|SrcSet)=["']([^"']+)["']""").find(tag)
            if (sm != null) {
                for (entry in sm.groupValues[1].split(",")) {
                    val parts = entry.trim().split(Regex("\\s+"))
                    if (parts.isEmpty()) continue
                    val u = urlJoin(base, parts[0])
                    var w: Int? = null
                    if (parts.size == 2) {
                        val dm = Regex("^(\\d{2,5})w$").find(parts[1])
                        if (dm != null) w = dm.groupValues[1].toIntOrNull()
                    }
                    val ks = kindSize(u)
                    if (ks.kind != "image") continue
                    if (mainStem.isNotBlank() && stemOf(u) != mainStem) continue
                    add(u, "", "image", w ?: ks.width, null, "", "srcset")
                }
            }
            val srcm = Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag)
            if (srcm != null) {
                val u = urlJoin(base, srcm.groupValues[1])
                val ks = kindSize(u)
                if (ks.kind == "image" && (mainStem.isBlank() || stemOf(u) == mainStem)) {
                    add(u, "", "image", ks.width, null, "", "srcset")
                }
            }
        }

        // 4) <a href>：download 属性 / 扩展名白名单 / 锚文本含“下载”
        val aTags = Regex(
            """<a\b[^>]*href=["']([^"']+)["']([^>]*)>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(html).toList()
        for (m in aTags) {
            val u = urlJoin(base, m.groupValues[1])
            if (!validMediaUrl(u)) continue
            val attrs = m.groupValues[2]
            val text = Regex("<[^>]+>").replace(m.groupValues[3], "").trim()
            val ks = kindSize(u)
            if (ks.kind == null && ks.ext !in FILE_EXT) {
                val lowText = text.lowercase(Locale.ROOT)
                if ("download" !in attrs.lowercase(Locale.ROOT) &&
                    "下载" !in lowText && "download" !in lowText
                ) {
                    continue
                }
            }
            val note = if ("download" in attrs.lowercase(Locale.ROOT)) "页面下载按钮" else ""
            add(u, text, ks.kind ?: "file", ks.width, null, note, "a")
        }

        // 5) 内联 JS 变量
        for ((u, name, _) in parseJsVars(html)) {
            val ks = kindSize(u)
            if (ks.kind == null && ks.ext !in FILE_EXT) continue
            add(u, "", ks.kind ?: "file", ks.width, null, "", "js:$name")
        }

        // 6) 参数嵌套直链（通用）
        val basePath = runCatching { base.toHttpUrlOrNull()?.encodedPath }.getOrNull() ?: ""
        val pageId = Regex("-(\\d+)/?$").find(basePath)?.groupValues?.getOrNull(1) ?: ""
        for (u in extractNestedUrls(html, pageId)) {
            val ks = kindSize(u)
            if (ks.kind == null && ks.ext !in FILE_EXT) continue
            add(u, "", ks.kind ?: "file", ks.width, null, "", "nested")
        }

        val prio = mapOf("jsonld" to 0, "og" to 0, "srcset" to 1, "a" to 3, "js" to 3, "nested" to 4)
        val opts = seen.values.sortedWith(
            compareBy<LinkOption> { if (it.kind == "image") 0 else 1 }
                .thenBy { prio[it.source.substringBefore('·')] ?: 4 }
                .thenByDescending { it.width ?: 0 },
        )
        return opts.take(MAX_OPTIONS)
    }

    // ==================== 尺寸枚举 + HEAD 验证 ====================

    private fun enumerateSizes(url: String): List<String> {
        val variants = LinkedHashSet<String>()
        val httpUrl = runCatching { url.toHttpUrlOrNull() }.getOrNull() ?: return emptyList()
        val path = httpUrl.encodedPath
        val m = Regex("^(.*?)(__?\\d{2,5})(\\.\\w+)$").find(path)
        if (m != null) {
            val base = m.groupValues[1]
            val ext = m.groupValues[3]
            for (tok in IMAGE_SIZE_TOKENS) {
                variants.add(httpUrl.newBuilder().encodedPath("${base}_$tok$ext").build().toString())
            }
            // 原图候选：去掉尺寸后缀（需登录/签发的会被 HEAD 过滤）
            variants.add(httpUrl.newBuilder().encodedPath(base + ext).build().toString())
        }
        val query = httpUrl.query ?: ""
        if (Regex("(^|&)w=\\d+").containsMatchIn(query)) {
            val qs = LinkedHashMap<String, String>()
            query.split("&").forEach {
                val i = it.indexOf('=')
                if (i > 0) qs[it.substring(0, i)] = it.substring(i + 1) else qs[it] = ""
            }
            for (w in IMAGE_W_PARAMS) {
                qs["w"] = w.toString()
                variants.add(httpUrl.newBuilder().query(qs.entries.joinToString("&") { "${it.key}=${it.value}" }).build().toString())
            }
            val q3 = qs.filterKeys { it !in setOf("w", "h", "fit", "crop", "dpr", "auto") }.toMutableMap()
            if ("cs" !in q3) q3["cs"] = "srgb"
            variants.add(httpUrl.newBuilder().query(q3.entries.joinToString("&") { "${it.key}=${it.value}" }).build().toString())
        }
        val m2 = Regex("^(.*?)(@\\dx)(\\.\\w+)$").find(path)
        if (m2 != null) {
            variants.add(httpUrl.newBuilder().encodedPath(m2.groupValues[1] + m2.groupValues[3]).build().toString())
        }
        return variants.filter { it != url }
    }

    private suspend fun headOk(
        client: OkHttpClient,
        url: String,
        timeoutSeconds: Long,
        cancel: AtomicBoolean,
        referer: String?,
    ): Boolean {
        if (cancel.get()) throw DownloadCancelled()
        for (attempt in 0..1) {
            try {
                val req = if (attempt == 0) {
                    Request.Builder().url(url).headers(headers(referer)).head().build()
                } else {
                    Request.Builder().url(url).headers(headers(referer)).header("Range", "bytes=0-0").build()
                }
                val call = client.newCall(req)
                val resp = withTimeoutOrNull(timeoutSeconds.coerceAtLeast(3) * 1000L) { call.execute() }
                    ?: run { call.cancel(); return false }
                resp.use {
                    if (cancel.get()) throw DownloadCancelled()
                    return it.code in 200..299
                }
            } catch (e: DownloadCancelled) {
                throw e
            } catch (e: Exception) {
                if (cancel.get()) throw DownloadCancelled()
                // HEAD 被拒可能只是不支持该方法，改用 Range GET 试一次；再失败视为不存在
            }
        }
        return false
    }

    private suspend fun verifyOptions(
        client: OkHttpClient,
        options: List<LinkOption>,
        timeoutSeconds: Long,
        cancel: AtomicBoolean,
        headConns: Int,
        referer: String?,
    ): List<LinkOption> {
        val result = options.toMutableList()
        val targets = options.filter { it.kind == "image" }.take(2).toMutableList()
        options.filter { it.kind == "image" && it.source.startsWith("nested") && it !in targets }
            .take(1).forEach { targets.add(it) }
        val candidates = targets.flatMap { o -> enumerateSizes(o.url).take(MAX_VARIANTS).map { it to o } }
        if (candidates.isEmpty()) return result
        val sem = Semaphore(headConns.coerceIn(1, 8))
        val ok = ConcurrentHashMap.newKeySet<String>()
        coroutineScope {
            for ((v, _) in candidates) {
                launch(Dispatchers.IO) {
                    sem.acquire()
                    try {
                        if (headOk(client, v, timeoutSeconds, cancel, referer)) ok.add(v)
                    } catch (e: DownloadCancelled) {
                        throw e
                    } catch (e: Exception) {
                        // 单个候选失败不影响整体
                    } finally {
                        sem.release()
                    }
                }
            }
        }
        val seen = result.map { it.url }.toHashSet()
        for (v in ok) {
            if (v in seen) continue
            seen.add(v)
            val baseOpt = candidates.firstOrNull { it.first == v }?.second
            val ks = kindSize(v)
            result.add(
                LinkOption(
                    v, "", "image", ks.width ?: baseOpt?.width, baseOpt?.height,
                    baseOpt?.note ?: "", (baseOpt?.source ?: "") + "·枚举", referer,
                ),
            )
        }
        return result
    }

    // ==================== 验证码 ====================

    private fun findCaptcha(html: String, base: String): CaptchaChallenge? {
        val forms = Regex(
            "<form\\b([^>]*)>(.*?)</form>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(html).toList()
        for (m in forms) {
            val attrs = m.groupValues[1]
            val inner = m.groupValues[2]
            val actionM = Regex("""action=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(attrs)
            val action = if (actionM != null) urlJoin(base, decodeHtml(actionM.groupValues[1])) else base
            val methodM = Regex("""method=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(attrs)
            val method = methodM?.groupValues?.getOrNull(1)?.uppercase(Locale.ROOT) ?: "POST"
            val fields = ArrayList<CaptchaChallenge.Field>()
            val needs = ArrayList<String>()
            val inputs = Regex(
                "<input\\b([^>]*?)/?>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).findAll(inner).toList()
            for (im in inputs) {
                val a = im.groupValues[1]
                val nm = Regex("""name=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(a)
                    ?: continue
                val ty = Regex("""type=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(a)
                val va = Regex("""value=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(a)
                val name = decodeHtml(nm.groupValues[1])
                val itype = (ty?.groupValues?.getOrNull(1) ?: "text").lowercase(Locale.ROOT)
                val value = va?.groupValues?.getOrNull(1)?.let { decodeHtml(it) } ?: ""
                if (itype !in setOf("hidden", "text", "password", "number", "captcha")) continue
                val isNeed = itype in setOf("password", "captcha", "number") ||
                    CAPTCHA_KEYWORDS.any { it in name.lowercase(Locale.ROOT) }
                fields.add(CaptchaChallenge.Field(name, value, isNeed))
                if (isNeed) needs.add(name)
            }
            if (needs.isEmpty()) continue
            var capUrl = ""
            val imgs = Regex("<img\\b[^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .findAll(inner).map { it.value }.toList()
            for (tag in imgs) {
                val sm = Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag) ?: continue
                val src = urlJoin(base, decodeHtml(sm.groupValues[1]))
                if (CAPTCHA_KEYWORDS.any { it in src.lowercase(Locale.ROOT) }) {
                    capUrl = src
                    break
                }
            }
            if (capUrl.isBlank() && imgs.size == 1) {
                val sm = Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(imgs[0])
                if (sm != null) capUrl = urlJoin(base, decodeHtml(sm.groupValues[1]))
            }
            if (!action.startsWith("http://") && !action.startsWith("https://")) continue
            return CaptchaChallenge(action, method, fields, capUrl, pageUrl = base)
        }
        return null
    }

    private suspend fun fetchCaptchaImage(
        client: OkHttpClient,
        ch: CaptchaChallenge,
        timeoutSeconds: Long,
        cancel: AtomicBoolean,
    ): CaptchaChallenge {
        if (ch.captchaUrl.isBlank()) return ch
        return try {
            val f = fetch(client, ch.captchaUrl, timeoutSeconds, cancel, ch.pageUrl)
            ch.copy(captchaBytes = f.body, captchaType = f.contentType)
        } catch (e: Exception) {
            ch // 图片拿不到时由 UI 显示链接
        }
    }

    private suspend fun submitChallenge(
        client: OkHttpClient,
        ch: CaptchaChallenge,
        answers: Map<String, String>,
        timeoutSeconds: Long,
        cancel: AtomicBoolean,
    ): Fetched {
        val pairs = ch.fields.map { f ->
            if (f.needsInput) f.name to (answers[f.name] ?: "") else f.name to f.value
        }
        val body = pairs.joinToString("&") {
            "${java.net.URLEncoder.encode(it.first, "UTF-8")}=${java.net.URLEncoder.encode(it.second, "UTF-8")}"
        }
        if (ch.method == "GET") {
            val sep = if ('?' in ch.action) "&" else "?"
            return fetch(client, ch.action + sep + body, timeoutSeconds, cancel, ch.pageUrl)
        }
        return fetch(client, ch.action, timeoutSeconds, cancel, ch.pageUrl, "POST", body.toByteArray())
    }

    // ==================== 主入口 ====================

    /**
     * 解析一个 URL（对应桌面版 resolve）。
     *
     * interactive：遇到人机验证时的回调（suspend），返回 {字段名: 输入}；返回 null 表示放弃。
     */
    suspend fun resolve(
        url: String,
        timeoutSeconds: Long = 15,
        cancel: AtomicBoolean = AtomicBoolean(false),
        interactive: (suspend (CaptchaChallenge) -> Map<String, String>?)? = null,
        progress: ((String, String) -> Unit)? = null,
    ): ResolveResult = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw ResolveError("只支持 http/https 链接：$url")
        }
        val client = sessionClient()
        var current = url
        var referer: String? = null
        val hops = ArrayList<String>()
        for (hop in 0 until MAX_HOPS) {
            if (cancel.get()) throw DownloadCancelled()
            progress?.invoke("fetch", "正在获取 $current")
            val f = fetchHop(client, current, timeoutSeconds, cancel, referer, retries = 1)
            hops.add(f.finalUrl)
            referer = f.finalUrl
            if (!looksHtml(f.contentType, f.body)) {
                val ks = kindSize(f.finalUrl)
                val label = f.finalUrl.substringBefore('?').substringAfterLast('/')
                return@withContext ResolveResult(
                    f.finalUrl,
                    listOf(LinkOption(f.finalUrl, label, ks.kind ?: "file", ks.width)),
                    label, hops,
                )
            }
            val html = String(f.body, Charsets.UTF_8)
            val title = htmlTitle(html)
            progress?.invoke("analyze", "正在分析页面 $title")

            // Cloudflare / 纯 JS 挑战页
            val low = html.take(4000).lowercase(Locale.ROOT)
            if ("just a moment" in low || "cf-browser-verification" in html.lowercase(Locale.ROOT) ||
                "id=\"challenge-form\"" in low || "cf-chl-" in low
            ) {
                return@withContext ResolveResult(
                    f.finalUrl,
                    note = "该站点开启了 Cloudflare 等 JS 人机验证，本工具无法自动通过。\n" +
                        "建议：稍后重试；若持续出现此提示，请停止使用本工具下载该站点，\n" +
                        "改用浏览器打开页面并手动获取真实直链。",
                    isPage = true,
                )
            }

            // 人机验证
            var captcha = findCaptcha(html, f.finalUrl)
            if (captcha != null) {
                progress?.invoke("captcha", "检测到人机验证")
                captcha = fetchCaptchaImage(client, captcha, timeoutSeconds, cancel)
                if (interactive == null) {
                    return@withContext ResolveResult(
                        f.finalUrl, title = title, hops = hops, isPage = true,
                        note = "需要人机验证", captcha = captcha,
                    )
                }
                val answers = interactive(captcha) ?: throw DownloadCancelled()
                progress?.invoke("captcha", "正在提交验证码…")
                val f2 = submitChallenge(client, captcha, answers, timeoutSeconds, cancel)
                hops.add(f2.finalUrl)
                referer = f2.finalUrl
                if (!looksHtml(f2.contentType, f2.body)) {
                    val ks = kindSize(f2.finalUrl)
                    val label = f2.finalUrl.substringBefore('?').substringAfterLast('/')
                    return@withContext ResolveResult(
                        f2.finalUrl,
                        listOf(LinkOption(f2.finalUrl, label, ks.kind ?: "file", ks.width)),
                        title, hops,
                    )
                }
                current = f2.finalUrl
                continue
            }

            // 提取候选
            var options = extractOptions(html, f.finalUrl, title)
            options = runCatching {
                verifyOptions(client, options, timeoutSeconds, cancel, 4, f.finalUrl)
            }.getOrElse {
                if (it is DownloadCancelled) throw it else options
            }
            if (options.isNotEmpty()) {
                progress?.invoke("done", "找到 ${options.size} 个下载选项")
                return@withContext ResolveResult(f.finalUrl, options, title, hops, isPage = true)
            }

            // 无选项 → 跟随 meta / JS 跳转
            var redirect = findMetaRefresh(html, f.finalUrl)
            if (redirect.isBlank()) {
                redirect = parseJsVars(html).firstOrNull()?.first ?: ""
            }
            if (redirect.isNotBlank() && redirect !in hops) {
                current = redirect
                continue
            }
            return@withContext ResolveResult(
                f.finalUrl, title = title, hops = hops, isPage = true,
                note = "页面中未找到可下载的链接。",
            )
        }
        throw ResolveError("跳转次数超过 $MAX_HOPS 次，已停止（可能存在跳转环）")
    }
}
