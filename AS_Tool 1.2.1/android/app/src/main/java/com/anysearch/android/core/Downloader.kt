package com.anysearch.android.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 下载失败（含假直链/HTML、大小校验失败等） */
open class DownloadError(message: String) : Exception(message)

/** 目标 URL 返回的是网页（text/html）而不是文件：AppState 以此触发链接解析 */
class HtmlPageError(message: String) : DownloadError(message)

/** 用户主动取消下载（断点已保留，再次下载自动续传） */
class DownloadCancelled : Exception("下载已取消")

/** 下载已暂停：分块重试耗尽，断点已保存，可再次调用续传 */
class DownloadPaused(message: String) : Exception(message)

enum class DownloadMode { MULTITHREAD, SINGLE, RESUME, SOURCE_SWITCH, SOURCE_FAIL }

/** 下载完成结果：用户可见的最终位置 + 文件大小 */
class DownloadResult(val displayName: String, val size: Long)

/** 测速结果（对应桌面版 measure_speed） */
data class SpeedResult(
    val speed: Double,
    val total: Long?,
    val supportsRange: Boolean,
    val finalUrl: String,
    val contentType: String,
)

/**
 * AnySearch 下载器 v1.2.0（对应桌面版 anysearch_downloader.py）。
 *
 * 引擎特性：
 *  - 自动分块：块 = 文件大小 ÷ (线程数×4)，夹在 [1MB, 16MB]；手动分块 1MB~64MB
 *  - 分块内断点重试：失败从该块已写字节处续传（Range bytes=(start+written)-(end)），
 *    退避 1s→2s→4s→8s，重试耗尽 → 整体暂停并抛 DownloadPaused（断点保留）
 *  - 跨会话断点续传：<filesDir>/anysearch_parts/ 下保存 .part 与 .meta，
 *    再次下载同一 URL 时校验 ETag/Last-Modified/大小一致则只补未完成块
 *  - 手动取消同样保留断点；discardFor(url) 放弃断点；cleanupParts() 清理缓存
 *  - 动态任务队列（谁完成谁继续领下一块）；下载完成校验文件大小
 *
 * 不支持：需要登录/Cookie/验证码/客户端签名的网盘链接（百度网盘、夸克网盘、阿里云盘等），
 * 以及返回 HTML 网页的网盘分享页。只支持无需鉴权、浏览器直接访问即可下载的真实文件直链。
 */
class Downloader(private val context: Context) {

    companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"

        /** 小于 1MB 直接用单线程，分块反而慢 */
        const val MIN_MULTITHREAD_SIZE = 1L * 1024 * 1024

        /** 自动分块的下限（1MB） */
        const val MIN_CHUNK_SIZE = 1L * 1024 * 1024

        /** 自动分块上限（16MB） */
        const val MAX_AUTO_CHUNK_SIZE = 16L * 1024 * 1024

        /** 手动分块上限（64MB） */
        const val MAX_MANUAL_CHUNK_SIZE = 64L * 1024 * 1024

        /** 自动分块时保证每线程至少有 4 块可领 */
        const val CHUNKS_PER_THREAD = 4L

        /** 单次读网络数据的超时（秒）。取消通过 Call.cancel() 立即生效 */
        const val READ_TIMEOUT_SECONDS = 60L

        /** .meta 断点文件的最小落盘间隔（毫秒） */
        const val META_SAVE_INTERVAL_MS = 1000L

        /** 无后缀文件名按 Content-Type 补扩展名（对应桌面版 _CONTENT_TYPE_EXT） */
        val CONTENT_TYPE_EXT = mapOf(
            "video/mp4" to ".mp4",
            "video/webm" to ".webm",
            "video/quicktime" to ".mov",
            "image/jpeg" to ".jpg",
            "image/png" to ".png",
            "image/gif" to ".gif",
            "image/webp" to ".webp",
            "application/zip" to ".zip",
            "application/x-zip-compressed" to ".zip",
            "application/pdf" to ".pdf",
            "application/json" to ".json",
            "text/plain" to ".txt",
            "text/markdown" to ".md",
            "application/octet-stream" to ".bin",
        )

        private val HTML_MARKERS = listOf("<!doctype html", "<html", "<head")

        private fun md5(s: String): String {
            return try {
                val d = MessageDigest.getInstance("MD5")
                d.update(s.toByteArray(Charsets.UTF_8))
                d.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            } catch (e: Exception) {
                Math.abs(s.hashCode()).toString(16)
            }
        }
    }

    @Volatile
    var client: OkHttpClient = buildClient(null)

    /** 自定义请求头（由 AppState 在设置保存时同步），下载请求会带上 */
    @Volatile
    var customHeaders: Map<String, String> = emptyMap()

    /** 当前所有活动下载请求；取消/暂停时立即 cancel 以中断阻塞中的 read */
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()

    /** 断点缓存目录（跨会话保留） */
    private val partsDir: File
        get() = File(context.filesDir, "anysearch_parts").apply { mkdirs() }

    private fun buildClient(proxy: Proxy?): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        if (proxy != null) b.proxy(proxy)
        return b.build()
    }

    /** 应用 HTTP/HTTPS 代理（对应桌面版 apply_proxy） */
    fun applyProxy(useProxy: Boolean, proxyUrl: String) {
        client = buildClient(if (useProxy && proxyUrl.isNotBlank()) ApiClient.parseProxy(proxyUrl) else null)
    }

    /** 立即中断所有正在进行的下载请求（对应桌面版 cancel_active_downloads） */
    fun cancelActive() {
        activeCalls.toList().forEach { call ->
            try {
                call.cancel()
            } catch (e: Exception) {
                // 忽略
            }
        }
    }

    // ==================== .part / .meta 断点持久化 ====================

    private fun partPaths(url: String, fileName: String): Pair<File, File> {
        val base = File(partsDir, "dl_${md5(url).take(8)}_$fileName")
        return base to File(partsDir, base.name + ".meta")
    }

    private fun loadMeta(metaFile: File): JSONObject? {
        return try {
            if (metaFile.exists()) JSONObject(metaFile.readText()) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun metaValid(meta: JSONObject?, url: String, total: Long?, etag: String, lastModified: String): Boolean {
        if (meta == null) return false
        if (meta.optString("url", "") != url) return false
        val mt = if (meta.has("total") && !meta.isNull("total")) meta.optLong("total") else null
        if (mt != null && total != null && mt != total) return false
        val me = meta.optString("etag", "")
        if (etag.isNotEmpty() && me.isNotEmpty() && me != etag) return false
        val mlm = meta.optString("last_modified", "")
        if (lastModified.isNotEmpty() && mlm.isNotEmpty() && mlm != lastModified) return false
        return true
    }

    private fun saveMeta(
        metaFile: File,
        url: String,
        total: Long,
        etag: String,
        lastModified: String,
        fileName: String,
        chunkSize: Long,
        threads: Int,
        segments: List<Pair<Long, Long>>,
        done: BooleanArray,
        blockWritten: LongArray,
    ) {
        try {
            val obj = JSONObject().apply {
                put("version", 1)
                put("url", url)
                put("total", total)
                put("etag", etag)
                put("last_modified", lastModified)
                put("filename", fileName)
                put("chunk_size", chunkSize)
                put("num_threads", threads)
                val segArr = JSONArray()
                segments.forEach { segArr.put(JSONArray().apply { put(it.first); put(it.second) }) }
                put("segments", segArr)
                val doneArr = JSONArray()
                done.forEach { doneArr.put(it) }
                put("done", doneArr)
                val wrArr = JSONArray()
                blockWritten.forEach { wrArr.put(it) }
                put("block_written", wrArr)
            }
            metaFile.writeText(obj.toString())
        } catch (e: Exception) {
            // 忽略落盘失败
        }
    }

    /** 放弃指定 URL 的下载断点（不联网，按 meta 中的 url 匹配） */
    fun discardFor(url: String) {
        try {
            partsDir.listFiles()?.filter { it.name.endsWith(".meta") }?.forEach { metaFile ->
                val meta = loadMeta(metaFile) ?: return@forEach
                if (meta.optString("url", "") == url) {
                    metaFile.delete()
                    File(partsDir, metaFile.name.removeSuffix(".meta")).delete()
                }
            }
        } catch (e: Exception) {
            // 忽略
        }
    }

    /** 断点缓存占用字节数（设置页展示用） */
    fun partsCacheBytes(): Long {
        var total = 0L
        try {
            partsDir.listFiles()?.forEach { total += it.length() }
        } catch (e: Exception) {
            // 忽略
        }
        return total
    }

    /** 清理全部断点缓存，返回释放的字节数 */
    fun cleanupParts(): Long {
        val freed = partsCacheBytes()
        try {
            partsDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            // 忽略
        }
        return freed
    }

    // ==================== 探测与文件名 ====================

    private fun headers(range: String? = null, referer: String? = null): okhttp3.Headers {
        val b = okhttp3.Headers.Builder()
            .add("User-Agent", BROWSER_UA)
            .add("Accept", "*/*")
        for ((k, v) in customHeaders) b.add(k, v)
        if (range != null) b.add("Range", range)
        if (referer != null) b.add("Referer", referer)
        return b.build()
    }

    private data class ProbeResult(
        val total: Long?,
        val supportsRange: Boolean,
        val finalUrl: String,
        val contentType: String,
        val contentDisposition: String?,
        val etag: String,
        val lastModified: String,
    )

    /** 探测文件大小与 Range 支持，同时判断是否为 HTML 假直链（对应桌面版 _probe） */
    private suspend fun probe(
        url: String,
        timeoutSeconds: Long,
        cancel: AtomicBoolean,
        referer: String? = null,
    ): ProbeResult {
        val call = client.newCall(
            Request.Builder().url(url).headers(headers("bytes=0-2047", referer)).build()
        )
        activeCalls.add(call)
        try {
            val resp = withTimeoutOrNull(timeoutSeconds.coerceAtLeast(5) * 1000L) {
                call.execute()
            } ?: run {
                call.cancel()
                throw DownloadError("探测目标 URL 超时。")
            }
            resp.use {
                if (cancel.get()) throw DownloadCancelled()
                if (resp.code !in 200..299) {
                    throw DownloadError("服务器返回异常状态码：${resp.code}")
                }
                val contentType = (resp.header("Content-Type") ?: "").lowercase(Locale.ROOT)
                val body = resp.body ?: throw DownloadError("响应无内容")
                val input = body.byteStream()
                val data = ByteArray(2048)
                var read = 0
                while (read < data.size) {
                    val n = input.read(data, read, data.size - read)
                    if (n < 0) break
                    read += n
                }
                val lower = String(data, 0, minOf(read, 512), Charsets.ISO_8859_1).lowercase(Locale.ROOT)
                if ("text/html" in contentType || HTML_MARKERS.any { it in lower }) {
                    throw HtmlPageError(
                        "目标 URL 返回的是网页（text/html）而不是文件，可能不是直链。\n" +
                            "请使用真实的文件直链（例如 CDN 链接），或先打开该页面获取真实下载地址。"
                    )
                }
                var total: Long? = null
                val cr = resp.header("Content-Range") ?: ""
                if (cr.isNotEmpty() && '/' in cr) {
                    total = cr.substringAfterLast('/').trim().toLongOrNull()
                }
                // 206 且 Content-Range 无总大小时，绝不能把 Content-Length（分片长度）当总大小
                if (total == null && resp.code != 206) {
                    total = resp.header("Content-Length")?.toLongOrNull()
                }
                val partialOk = resp.code == 206
                val acceptsRanges = (resp.header("Accept-Ranges") ?: "").equals("bytes", ignoreCase = true)
                return ProbeResult(
                    total,
                    partialOk || acceptsRanges,
                    resp.request.url.toString(),
                    contentType,
                    resp.header("Content-Disposition"),
                    resp.header("ETag") ?: "",
                    resp.header("Last-Modified") ?: "",
                )
            }
        } finally {
            activeCalls.remove(call)
        }
    }

    /** 按最终跳转后的 URL 取文件名；无后缀时按 Content-Type 补扩展名（对应桌面版 _guess_filename_from_final） */
    private fun guessFilename(url: String, contentType: String, cd: String?): String {
        // Content-Disposition: attachment; filename="xxx" / filename*=UTF-8''...（RFC 6266/5987）
        if (!cd.isNullOrBlank()) {
            try {
                val ext = Regex(
                    "filename\\*\\s*=\\s*(?:UTF-8''|utf-8'')\\s*\"?([^\";]+)\"?",
                    RegexOption.IGNORE_CASE,
                ).find(cd)
                if (ext != null) {
                    var name = ext.groupValues[1].trim().trim('"')
                    if (name.isNotBlank()) {
                        name = runCatching { android.net.Uri.decode(name) }.getOrDefault(name)
                        return sanitizeName(name)
                    }
                }
                val plain = Regex(
                    "filename\\s*=\\s*\"?([^\";]+)\"?",
                    RegexOption.IGNORE_CASE,
                ).find(cd)
                val name = plain?.groupValues?.getOrNull(1)?.trim()?.trim('"')
                if (!name.isNullOrBlank()) return sanitizeName(name)
            } catch (e: Exception) {
                // 忽略，继续用 URL 取名
            }
        }
        val path = try {
            URI(url).path ?: url.substringBefore('?')
        } catch (e: Exception) {
            url.substringBefore('?')
        }
        var name = path.substringAfterLast('/').ifBlank { "download" }
        if (name.isEmpty()) name = "download"
        name = sanitizeName(name)
        if ('.' in name) return name
        val ext = CONTENT_TYPE_EXT[contentType.substringBefore(';').trim().lowercase(Locale.ROOT)] ?: ""
        return name + ext
    }

    /** 去掉文件名中的非法字符（安卓存储要求；桌面版无此限制） */
    private fun sanitizeName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifEmpty { "download" }
    }

    /** 计算分块大小（与桌面版 _compute_chunk_size 一致） */
    private fun computeChunkSize(total: Long, threads: Int, autoChunk: Boolean, chunkSizeBytes: Long): Long {
        if (autoChunk) {
            var chunk = total / (threads * CHUNKS_PER_THREAD)
            chunk = chunk.coerceIn(MIN_CHUNK_SIZE, MAX_AUTO_CHUNK_SIZE)
            val aligned = chunk - (chunk % MIN_CHUNK_SIZE)
            return if (aligned >= MIN_CHUNK_SIZE) aligned else MIN_CHUNK_SIZE
        }
        return chunkSizeBytes.coerceIn(MIN_CHUNK_SIZE, MAX_MANUAL_CHUNK_SIZE)
    }

    // ==================== 镜像源 ====================

    /** 把镜像模板展开为候选 URL（模板需含 {url} 占位符；与桌面版 build_mirror_urls 一致） */
    private fun buildMirrorUrls(url: String, mirrors: List<String>): List<String> =
        mirrors.mapNotNull { m ->
            val t = m.trim()
            if ("{url}" in t) t.replace("{url}", url) else null
        }

    /** 对候选源测速并按速度降序排列（与桌面版 _rank_sources 一致；测速并发受 probeConns 限制） */
    private suspend fun rankSources(
        sources: List<String>,
        timeoutSeconds: Long,
        probeConns: Int,
        cancel: AtomicBoolean,
        referer: String? = null,
    ): List<String> {
        if (sources.size <= 1) return sources
        val sem = Semaphore(probeConns.coerceIn(1, 8))
        val results = ConcurrentHashMap<String, SpeedResult>()
        coroutineScope {
            sources.forEach { src ->
                launch(Dispatchers.IO) {
                    sem.acquire()
                    try {
                        results[src] = measureSpeed(src, timeoutSeconds, cancel = cancel, referer = referer)
                    } catch (e: Exception) {
                        results[src] = SpeedResult(0.0, null, false, src, "")
                    } finally {
                        sem.release()
                    }
                }
            }
        }
        val ranked = sources.map { s ->
            val r = results[s] ?: SpeedResult(0.0, null, false, s, "")
            Triple(s, if (r.total != null && r.supportsRange) 1 else 0, r.speed)
        }.sortedWith(
            compareByDescending<Triple<String, Int, Double>> { it.second }
                .thenByDescending { it.third },
        )
        return ranked.map { it.first }
    }

    // ==================== 下载核心 ====================

    /**
     * 单线程下载（服务器不支持 Range 或文件过小时使用）。
     * writtenOffset > 0 时从该偏移续传（Range: bytes=N-，服务器忽略 Range 返回 200 时截断重写）。
     */
    private suspend fun downloadSingle(
        url: String,
        dest: File,
        maxRetries: Int,
        cancel: AtomicBoolean,
        progress: (Long, Long?) -> Unit,
        localCalls: MutableSet<Call>,
        writtenOffset: Long = 0,
        referer: String? = null,
    ) {
        var downloaded = writtenOffset
        var attempt = 0
        while (true) {
            if (cancel.get()) throw DownloadCancelled()
            try {
                val hb = headers(if (downloaded > 0) "bytes=$downloaded-" else null, referer)
                val call = client.newCall(Request.Builder().url(url).headers(hb).build())
                activeCalls.add(call)
                localCalls.add(call)
                try {
                    call.execute().use { resp ->
                        if (cancel.get()) throw DownloadCancelled()
                        if (resp.code !in 200..299) {
                            throw DownloadError("服务器返回异常状态码：${resp.code}")
                        }
                        val input = resp.body?.byteStream() ?: throw DownloadError("响应无内容")
                        var total: Long? = null
                        var first = ByteArray(0)
                        if (downloaded == 0L) {
                            val contentType = (resp.header("Content-Type") ?: "").lowercase(Locale.ROOT)
                            first = ByteArray(2048)
                            var read = 0
                            while (read < first.size) {
                                val n = input.read(first, read, first.size - read)
                                if (n < 0) break
                                read += n
                            }
                            val lower = String(first, 0, minOf(read, 512), Charsets.ISO_8859_1).lowercase(Locale.ROOT)
                            if ("text/html" in contentType || HTML_MARKERS.any { it in lower }) {
                                throw HtmlPageError("目标 URL 返回的是网页（text/html），可能不是直链。")
                            }
                            total = resp.header("Content-Length")?.toLongOrNull()
                            first = first.copyOf(read)
                        }
                        RandomAccessFile(dest, "rw").use { raf ->
                            if (downloaded > 0) {
                                raf.seek(downloaded)
                                if (resp.code == 200) {
                                    // 服务器忽略 Range：截断从头重写
                                    raf.setLength(0)
                                    downloaded = 0
                                    raf.seek(0)
                                }
                            } else {
                                raf.seek(0)
                            }
                            if (first.isNotEmpty()) {
                                raf.write(first)
                                downloaded += first.size.toLong()
                                progress(downloaded, total)
                            }
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                if (cancel.get()) throw DownloadCancelled()
                                val n = input.read(buf)
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                downloaded += n.toLong()
                                progress(downloaded, total)
                            }
                        }
                        return
                    }
                } finally {
                    activeCalls.remove(call)
                    localCalls.remove(call)
                }
            } catch (e: DownloadCancelled) {
                throw e
            } catch (e: Exception) {
                if (cancel.get()) throw DownloadCancelled()
                attempt++
                if (attempt > maxRetries) throw DownloadError("下载失败：$e")
                // 可中断退避：取消立即响应，不等完整退避时长
                var waited = 0L
                while (waited < 1000) {
                    if (cancel.get()) throw DownloadCancelled()
                    delay(100)
                    waited += 100
                }
            }
        }
    }

    /**
     * ≤1MB 但服务器支持 Range 的文件：单线程下载 + .part/.meta 断点续传
     * （对应桌面版 _download_small_resumable）。暂停保留断点，再次下载自动续传。
     */
    private suspend fun downloadSmallResumable(
        url: String,
        target: DownloadTarget,
        part: File,
        metaFile: File,
        fileName: String,
        total: Long,
        etag: String,
        lastModified: String,
        timeoutSeconds: Long,
        maxRetries: Int,
        cancel: AtomicBoolean,
        progress: (Long, Long?) -> Unit,
        mode: (DownloadMode, Int, Long?) -> Unit,
        localCalls: MutableSet<Call>,
        referer: String? = null,
    ): DownloadResult {
        val singleSeg = listOf(0L to (total - 1))
        var written = 0L
        val meta = loadMeta(metaFile)
        if (metaValid(meta, url, total, etag, lastModified) && part.exists() &&
            meta?.optLong("chunk_size", -1) == 1L &&
            meta.optJSONArray("segments")?.length() == 1
        ) {
            written = part.length().coerceIn(0, total)
        }
        if (written >= total) {
            mode(DownloadMode.RESUME, 1, total)
            progress(total, total)
            val label = target.writeFrom(part, fileName, "", total)
            metaFile.delete()
            part.delete()
            return DownloadResult(label, total)
        }
        mode(if (written > 0) DownloadMode.RESUME else DownloadMode.SINGLE, 1, total)
        progress(written, total)
        try {
            downloadSingle(url, part, maxRetries, cancel, { d, t -> progress(d, t) },
                localCalls, written, referer)
        } catch (e: Exception) {
            if (cancel.get()) {
                // 暂停：保留 .part/.meta 供续传
                runCatching {
                    val sz = part.length()
                    saveMeta(metaFile, url, total, etag, lastModified, fileName, 1, 1,
                        singleSeg, BooleanArray(1) { sz >= total }, LongArray(1) { sz.coerceAtMost(total) })
                }
                throw e
            }
            part.delete()
            throw e
        }
        val actual = part.length()
        if (actual != total) {
            metaFile.delete()
            part.delete()
            throw DownloadError("文件大小校验失败：期望 $total 字节，实际 $actual 字节")
        }
        val label = target.writeFrom(part, fileName, "", actual)
        metaFile.delete()
        part.delete()
        return DownloadResult(label, actual)
    }

    /**
     * 下载 [start, end] 区间到 part 文件对应偏移。
     * 分块内断点重试：失败从 blockWritten[idx]（该块已写字节）处续传；
     * 重试耗尽 → 抛 DownloadError（由 worker 统一转暂停）。
     */
    private suspend fun downloadRange(
        url: String,
        start: Long,
        end: Long,
        part: File,
        maxRetries: Int,
        cancel: AtomicBoolean,
        pause: AtomicBoolean,
        doneBytes: AtomicLong,
        total: Long,
        progress: (Long, Long?) -> Unit,
        blockWritten: LongArray,
        idx: Int,
        maybeSaveMeta: () -> Unit,
        localCalls: MutableSet<Call>,
        referer: String? = null,
    ) {
        val expected = end - start + 1
        var attempt = 0
        while (true) {
            if (cancel.get()) throw DownloadCancelled()
            if (pause.get()) throw DownloadPaused("下载已暂停")
            val downloaded = blockWritten[idx]
            try {
                val call = client.newCall(
                    Request.Builder().url(url).headers(headers("bytes=${start + downloaded}-$end", referer)).build()
                )
                activeCalls.add(call)
                localCalls.add(call)
                try {
                    call.execute().use { resp ->
                        if (resp.code == 200) {
                            throw DownloadError("服务器忽略了 Range 请求（返回 200），已停止该分块。")
                        }
                        if (resp.code != 206) {
                            throw DownloadError("服务器返回异常状态码：${resp.code}")
                        }
                        if (cancel.get()) throw DownloadCancelled()
                        if (pause.get()) throw DownloadPaused("下载已暂停")
                        val input = resp.body?.byteStream() ?: throw DownloadError("响应无内容")
                        RandomAccessFile(part, "rw").use { raf ->
                            raf.seek(start + downloaded)
                            var written = downloaded
                            val buf = ByteArray(256 * 1024)
                            while (written < expected) {
                                if (cancel.get()) throw DownloadCancelled()
                                if (pause.get()) throw DownloadPaused("下载已暂停")
                                val n = input.read(buf)
                                if (n < 0) break
                                if (written + n > expected) {
                                    throw DownloadError("服务器返回数据超过分块范围（${written + n}/$expected）")
                                }
                                raf.write(buf, 0, n)
                                written += n.toLong()
                                blockWritten[idx] = written
                                doneBytes.addAndGet(n.toLong())
                                progress(doneBytes.get(), total)
                                maybeSaveMeta()
                            }
                            if (written != expected) {
                                throw DownloadError("分块下载不完整：$written/$expected")
                            }
                        }
                        return
                    }
                } finally {
                    activeCalls.remove(call)
                    localCalls.remove(call)
                }
            } catch (e: DownloadCancelled) {
                throw e
            } catch (e: DownloadPaused) {
                throw e
            } catch (e: Exception) {
                if (cancel.get()) throw DownloadCancelled()
                if (pause.get()) throw DownloadPaused("下载已暂停")
                attempt++
                if (attempt > maxRetries) {
                    throw DownloadError("分片 $start-$end 下载失败：$e")
                }
                // 可中断退避：暂停/取消立即响应，不等完整退避时长
                var waited = 0L
                val backoff = minOf(1000L * (1L shl (attempt - 1)), 8000L)
                while (waited < backoff) {
                    if (cancel.get()) throw DownloadCancelled()
                    if (pause.get()) throw DownloadPaused("下载已暂停")
                    delay(100)
                    waited += 100
                }
            }
        }
    }

    /**
     * 下载 url 到 target，返回最终结果（对应桌面版 download_file）。
     *
     * 流程：探测 → 写断点缓存 .part（支持随机偏移）→ 大小校验 → 拷入目标位置 → 清理断点。
     */
    suspend fun downloadFile(
        url: String,
        target: DownloadTarget,
        numThreads: Int = 4,
        timeoutSeconds: Long = 60,
        progressCallback: (done: Long, total: Long?) -> Unit = { _, _ -> },
        modeCallback: (mode: DownloadMode, threads: Int, total: Long?) -> Unit = { _, _, _ -> },
        cancel: AtomicBoolean = AtomicBoolean(false),
        maxRetries: Int = 3,
        autoChunk: Boolean = true,
        chunkSizeBytes: Long = 4L * 1024 * 1024,
        mirrors: List<String> = emptyList(),
        mirrorStrategy: String = "direct",
        mirrorSpeedLimit: Long = 0,
        mirrorProbeConns: Int = 3,
        referer: String? = null,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val probeRes = probe(url, timeoutSeconds, cancel, referer)
        val fileName = guessFilename(probeRes.finalUrl, probeRes.contentType, probeRes.contentDisposition)
        val (part, metaFile) = partPaths(url, fileName)
        var finished = false
        // 本次下载专属的请求集合：暂停/出错只中断本下载，不影响其它并发任务
        val localCalls = ConcurrentHashMap.newKeySet<Call>()

        try {
            val threads = if (numThreads > 1) numThreads else 4
            if (!probeRes.supportsRange || probeRes.total == null || probeRes.total <= MIN_MULTITHREAD_SIZE) {
                if (probeRes.supportsRange && probeRes.total != null) {
                    // 小文件但支持 Range：单线程 + 断点续传（暂停可续传）
                    return@withContext downloadSmallResumable(
                        url, target, part, metaFile, fileName, probeRes.total,
                        probeRes.etag, probeRes.lastModified, timeoutSeconds, maxRetries,
                        cancel, { d, t -> progressCallback(d, t) }, { m, n, t -> modeCallback(m, n, t) },
                        localCalls, referer,
                    )
                }
                // 回退单线程（不支持断点：清理旧残留）
                metaFile.delete()
                part.delete()
                modeCallback(DownloadMode.SINGLE, 1, probeRes.total)
                progressCallback(0, probeRes.total)
                try {
                    downloadSingle(url, part, maxRetries, cancel, { d, t -> progressCallback(d, t) },
                        localCalls, referer = referer)
                } catch (e: Exception) {
                    part.delete()
                    throw e
                }
                val actual = part.length()
                val total = probeRes.total
                if (total != null && actual != total) {
                    part.delete()
                    throw DownloadError("文件大小校验失败：期望 $total 字节，实际 $actual 字节")
                }
                val label = target.writeFrom(part, fileName, probeRes.contentType, actual)
                finished = true
                return@withContext DownloadResult(label, actual)
            } else {
                val total = probeRes.total
                val chunk = computeChunkSize(total, threads, autoChunk, chunkSizeBytes)
                val segments = ArrayList<Pair<Long, Long>>()
                var pos = 0L
                while (pos < total) {
                    val end = minOf(pos + chunk - 1, total - 1)
                    segments.add(pos to end)
                    pos = end + 1
                }
                val done = BooleanArray(segments.size)
                val blockWritten = LongArray(segments.size)
                var resumed = false

                val meta = loadMeta(metaFile)
                if (meta != null && metaValid(meta, url, total, probeRes.etag, probeRes.lastModified) && part.exists()) {
                    val mChunk = meta.optLong("chunk_size", 0)
                    val mSegs = meta.optJSONArray("segments")
                    val mDone = meta.optJSONArray("done")
                    val mWritten = meta.optJSONArray("block_written")
                    val segsMatch = mSegs != null && mSegs.length() == segments.size &&
                        (0 until segments.size).all { i ->
                            val s = mSegs.optJSONArray(i)
                            s != null && s.length() == 2 &&
                                s.optLong(0) == segments[i].first && s.optLong(1) == segments[i].second
                        }
                    if (mChunk == chunk && segsMatch && mDone != null && mWritten != null &&
                        mDone.length() == segments.size && mWritten.length() == segments.size
                    ) {
                        for (i in segments.indices) {
                            done[i] = mDone.optBoolean(i)
                            blockWritten[i] = mWritten.optLong(i)
                            if (done[i]) blockWritten[i] = segments[i].second - segments[i].first + 1
                        }
                        resumed = done.any { it } || blockWritten.any { it > 0 }
                    } else {
                        // 分块参数变化：断点不可用，清理重下
                        metaFile.delete()
                        part.delete()
                    }
                } else {
                    // 一切非续传场景（无 meta / meta 无效 / part 缺失）：清理残留后重下，
                    // 防止旧 .part 被复用导致脏数据或大小校验失败
                    metaFile.delete()
                    part.delete()
                }

                if (!resumed && !part.exists()) {
                    part.createNewFile()
                }

                val doneBytes = AtomicLong(blockWritten.sum())
                progressCallback(doneBytes.get(), total)

                // 镜像源顺序（与桌面版一致）
                val sources: List<String> = when (mirrorStrategy) {
                    "mirror_first" -> rankSources(buildMirrorUrls(url, mirrors), timeoutSeconds, mirrorProbeConns, cancel, referer) + url
                    "auto_fallback" -> listOf(url) + buildMirrorUrls(url, mirrors)
                    else -> listOf(url)
                }

                var curEtag = probeRes.etag
                var curLm = probeRes.lastModified
                val switchEvent = AtomicBoolean(false)
                val speedSamples = ArrayDeque<Pair<Long, Long>>()
                val speedLock = Any()

                for ((si, srcUrl) in sources.withIndex()) {
                    if (cancel.get()) throw DownloadCancelled()

                    // 每个源的切换决策独立：进入新源时重置上一源的切换标记与测速窗口
                    switchEvent.set(false)
                    synchronized(speedLock) { speedSamples.clear() }

                    if (si > 0) {
                        // 切换源：探测并校验大小/ETag 一致才允许续传
                        val sp = try {
                            probe(srcUrl, timeoutSeconds, cancel, referer)
                        } catch (e: Exception) {
                            modeCallback(DownloadMode.SOURCE_FAIL, 0, total)
                            continue
                        }
                        if (sp.total != total || !sp.supportsRange) {
                            modeCallback(DownloadMode.SOURCE_FAIL, 0, total)
                            continue
                        }
                        if ((sp.etag.isNotEmpty() && curEtag.isNotEmpty() && sp.etag != curEtag) ||
                            (sp.lastModified.isNotEmpty() && curLm.isNotEmpty() && sp.lastModified != curLm)
                        ) {
                            // 内容不一致：重置断点，从该源重下
                            for (i in segments.indices) {
                                done[i] = false
                                blockWritten[i] = 0
                            }
                            doneBytes.set(0)
                            part.delete()
                            part.createNewFile()
                            progressCallback(0, total)
                            curEtag = sp.etag
                            curLm = sp.lastModified
                        }
                        modeCallback(DownloadMode.SOURCE_SWITCH, threads, total)
                    } else {
                        modeCallback(if (resumed) DownloadMode.RESUME else DownloadMode.MULTITHREAD, threads, total)
                    }

                    val pause = AtomicBoolean(false)
                    val errors = ConcurrentLinkedQueue<Throwable>()

                    // 待领取队列：只含未完成块
                    val pending = segments.indices.filter { !done[it] }
                    val nextIndex = AtomicInteger(0)
                    var lastSave = 0L
                    val metaLock = Any()
                    val stateLock = Any() // 保护 done/blockWritten 与 meta 快照的一致性

                    fun maybeSaveMeta() {
                        val now = System.currentTimeMillis()
                        synchronized(metaLock) {
                            if (now - lastSave >= META_SAVE_INTERVAL_MS) {
                                lastSave = now
                                synchronized(stateLock) {
                                    saveMeta(metaFile, url, total, curEtag, curLm,
                                        fileName, chunk, threads, segments, done, blockWritten)
                                }
                            }
                        }
                    }

                    // 低速监测（仅直连源 + auto_fallback）。多 worker 并发调用，用锁保护样本队列
                    val limit = mirrorSpeedLimit
                    fun speedWatch(doneNow: Long) {
                        if (limit <= 0 || si != 0 || mirrorStrategy != "auto_fallback" || sources.size <= 1) return
                        synchronized(speedLock) {
                            val now = System.currentTimeMillis()
                            speedSamples.addLast(now to doneNow)
                            while (speedSamples.isNotEmpty() && now - speedSamples.first().first > 5000) {
                                speedSamples.removeFirst()
                            }
                            val dt = (now - speedSamples.first().first) / 1000.0
                            if (dt >= 3.0) {
                                val avg = (doneNow - speedSamples.first().second) / dt
                                if (avg < limit) {
                                    switchEvent.set(true)
                                    pause.set(true)
                                }
                            }
                        }
                    }

                    val workerCount = minOf(threads, pending.size)
                    coroutineScope {
                        repeat(workerCount) {
                            launch(Dispatchers.IO) {
                                while (true) {
                                    if (cancel.get() || pause.get()) return@launch
                                    val pi = nextIndex.getAndIncrement()
                                    if (pi >= pending.size) return@launch
                                    val idx = pending[pi]
                                    val (s, e) = segments[idx]
                                    try {
                                        downloadRange(srcUrl, s, e, part, maxRetries, cancel, pause,
                                            doneBytes, total, { d, t ->
                                                progressCallback(d, t)
                                                speedWatch(d)
                                            }, blockWritten, idx, ::maybeSaveMeta, localCalls, referer)
                                        synchronized(stateLock) {
                                            blockWritten[idx] = e - s + 1
                                            done[idx] = true
                                        }
                                        maybeSaveMeta()
                                    } catch (ex: DownloadCancelled) {
                                        return@launch
                                    } catch (ex: DownloadPaused) {
                                        return@launch
                                    } catch (ex: Exception) {
                                        errors.add(ex)
                                        pause.set(true)
                                        localCalls.forEach { c -> runCatching { c.cancel() } }
                                        return@launch
                                    }
                                }
                            }
                        }
                    }

                    saveMeta(metaFile, url, total, curEtag, curLm,
                        fileName, chunk, threads, segments, done, blockWritten)

                    if (cancel.get()) throw DownloadCancelled()

                    // 低速切换 / 源失败：换下一个源继续（断点保留）
                    if ((switchEvent.get() || errors.isNotEmpty() || pause.get()) && si + 1 < sources.size) {
                        modeCallback(DownloadMode.SOURCE_SWITCH, threads, total)
                        continue
                    }

                    if (errors.isNotEmpty() || pause.get()) {
                        val detail = errors.peek()?.message ?: ""
                        val finishedBlocks = done.count { it }
                        throw DownloadPaused(
                            "下载已暂停：$finishedBlocks/${segments.size} 分块完成" +
                                (if (detail.isNotBlank()) "（$detail）" else "") +
                                "；断点已保存，再次下载将自动续传。"
                        )
                    }
                    break
                }
            }

            // 最终大小校验：不完整/多余的文件绝不保留
            val actual = part.length()
            val total = probeRes.total
            if (total != null && actual != total) {
                metaFile.delete()
                part.delete()
                throw DownloadError("文件大小校验失败：期望 $total 字节，实际 $actual 字节")
            }

            // 校验通过，落盘到目标位置
            val label = target.writeFrom(part, fileName, probeRes.contentType, actual)
            finished = true
            DownloadResult(label, actual)
        } finally {
            // 仅成功时清理断点；取消/暂停保留 .part/.meta 供续传
            if (finished) {
                metaFile.delete()
                part.delete()
            }
        }
    }

    /** 对 URL 做一次快速测速（对应桌面版 measure_speed）；失败返回 speed=0 */
    suspend fun measureSpeed(
        url: String,
        timeoutSeconds: Long = 30,
        sampleBytes: Int = 1024 * 1024,
        cancel: AtomicBoolean = AtomicBoolean(false),
        referer: String? = null,
    ): SpeedResult = withContext(Dispatchers.IO) {
        try {
            val p = probe(url, timeoutSeconds, cancel, referer)
            val call = client.newCall(
                Request.Builder().url(url).headers(headers("bytes=0-${sampleBytes - 1}", referer)).build()
            )
            activeCalls.add(call)
            try {
                val start = System.nanoTime()
                var read = 0
                call.execute().use { resp ->
                    val input = resp.body?.byteStream()
                    if (input != null) {
                        val buf = ByteArray(256 * 1024)
                        while (read < sampleBytes) {
                            if (cancel.get()) throw DownloadCancelled()
                            val n = input.read(buf)
                            if (n < 0) break
                            read += n
                        }
                    }
                }
                val elapsed = (System.nanoTime() - start) / 1e9
                SpeedResult(
                    speed = if (elapsed > 0) read / elapsed else 0.0,
                    total = p.total,
                    supportsRange = p.supportsRange,
                    finalUrl = p.finalUrl,
                    contentType = p.contentType,
                )
            } finally {
                activeCalls.remove(call)
            }
        } catch (e: DownloadCancelled) {
            throw e
        } catch (e: Exception) {
            SpeedResult(0.0, null, false, url, "")
        }
    }
}
