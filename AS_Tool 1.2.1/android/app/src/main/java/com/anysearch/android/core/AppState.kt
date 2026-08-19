package com.anysearch.android.core

import android.content.Context
import android.net.Uri
import com.anysearch.android.DownloadForegroundService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 上次解析出的直链已失效（签名过期等）：触发重新解析 */
private class StaleResolved : Exception()

/** 直链下载被站点拒绝（403/429 等）：尝试用解析器解析页面 */
private class BlockedRetry : Exception()

private fun isBlockError(msg: String?): Boolean {
    val m = msg ?: return false
    return "403" in m || "429" in m || "forbidden" in m.lowercase(Locale.ROOT) ||
        "禁止" in m || "拒绝" in m
}

/**
 * 应用级单例状态中心：设置/Key/各页输出/下载状态/批量任务。
 *
 * 设计要点：
 *  - 全部长任务跑在应用级 scope（不随 Activity 重建取消），旋转屏幕不影响下载；
 *  - 状态用 StateFlow 暴露，UI 层 collectAsState 订阅；
 *  - 下载进度回调在此节流（桌面版靠消息队列节流，Compose 下避免每 256KB 就触发重组）。
 */
object AppState {

    private var initialized = false
    lateinit var context: Context
        private set
    lateinit var downloader: Downloader
        private set
    lateinit var resolver: LinkResolver
        private set

    /** 应用级后台 scope：下载/搜索/抓取任务都跑在这里 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- 设置 / Key ----
    val settings = MutableStateFlow(Settings())
    val apiKey = MutableStateFlow("")

    // ---- 全局状态栏 / 一次性提示 ----
    val status = MutableStateFlow("就绪")
    val events = MutableSharedFlow<String>(extraBufferCapacity = 32)

    // ---- 各页输出文本（保留完整内容，显示时按 max_display_len 截断） ----
    val searchText = MutableStateFlow("")
    val extractText = MutableStateFlow("")
    val downloadLog = MutableStateFlow("")

    // ---- 单文件下载状态 ----
    data class SingleDownload(
        val url: String = "",
        val running: Boolean = false,
        val paused: Boolean = false,
        val pct: Float = 0f,
        val done: Long = 0,
        val total: Long? = null,
        val speed: Double = 0.0,
        val message: String = "0%",
    )

    val singleDownload = MutableStateFlow(SingleDownload())
    private var singleCancel = AtomicBoolean(false)
    private var singleAbort = AtomicBoolean(false)

    // ---- 链接解析的会话记忆（签名直链每次解析都不同：暂停后续传复用） ----
    private var resolvedUrls: List<String>? = null
    private var resolvedReferer: String? = null

    // ---- 解析交互桥（worker 等待 UI 弹窗作答） ----
    val pendingOptions = MutableStateFlow<ResolveResult?>(null)
    val pendingCaptcha = MutableStateFlow<CaptchaChallenge?>(null)
    private var optionsChoice: CompletableDeferred<List<String>?>? = null
    private var captchaAnswer: CompletableDeferred<Map<String, String>?>? = null

    /** UI：用户选择了若干选项（空列表/null = 放弃） */
    fun chooseOptions(urls: List<String>?) {
        pendingOptions.value = null
        optionsChoice?.complete(urls)
        optionsChoice = null
    }

    /** UI：用户输入验证码（null = 放弃） */
    fun answerCaptcha(answers: Map<String, String>?) {
        pendingCaptcha.value = null
        captchaAnswer?.complete(answers)
        captchaAnswer = null
    }

    /** worker：等待 UI 弹出下载选项（返回选中的 URL 列表；放弃返回 null） */
    private suspend fun askOptions(rr: ResolveResult): List<String>? {
        val d = CompletableDeferred<List<String>?>()
        optionsChoice = d
        pendingOptions.value = rr
        return d.await()
    }

    /** worker：等待 UI 弹出验证码（返回 {字段: 输入}；放弃返回 null） */
    private suspend fun askCaptcha(ch: CaptchaChallenge): Map<String, String>? {
        val d = CompletableDeferred<Map<String, String>?>()
        captchaAnswer = d
        pendingCaptcha.value = ch
        return d.await()
    }

    /** 关闭遗留弹窗（取消/暂停/退出时） */
    private fun dismissPendingDialogs() {
        pendingOptions.value = null
        pendingCaptcha.value = null
        optionsChoice?.complete(null)
        optionsChoice = null
        captchaAnswer?.complete(null)
        captchaAnswer = null
    }

    // ---- 批量下载状态 ----
    data class BatchTask(
        val iid: Int,
        val url: String,
        val status: String = "等待",
        val pct: String = "",
        val speed: String = "",
        val cancel: AtomicBoolean = AtomicBoolean(false),
        val discard: AtomicBoolean = AtomicBoolean(false),
        var resolved: List<String>? = null,   // 解析出的直链：续传复用（签名直链每次解析都不同）
        var referer: String? = null,          // 解析出直链的页面地址（抗防盗链）
    )

    val batchTasks = MutableStateFlow<List<BatchTask>>(emptyList())
    val batchRunning = MutableStateFlow(false)
    private var batchStop = AtomicBoolean(false)

    /** 在 Application.onCreate 调用一次 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        this.context = context.applicationContext
        downloader = Downloader(this.context)
        resolver = LinkResolver(downloader)
        val s = SettingsStore.load(this.context)
        settings.value = s
        apiKey.value = KeyStore.load(this.context)
        applyNetworkSettings(s)
    }

    /** 把代理/自定义请求头应用到 API 与下载请求（对应桌面版保存设置后的立即生效） */
    fun applyNetworkSettings(s: Settings) {
        ApiClient.applyProxy(s.useProxy, s.proxyUrl)
        downloader.applyProxy(s.useProxy, s.proxyUrl)
        val headers = SettingsStore.parseHeaders(s.customHeaders)
        ApiClient.customHeaders = headers
        downloader.customHeaders = headers
    }

    fun saveKey(key: String) {
        apiKey.value = key
        KeyStore.save(context, key)
    }

    fun saveSettings(s: Settings) {
        SettingsStore.save(context, s)
        settings.value = s
        applyNetworkSettings(s)
    }

    // ---- 下载保存位置 ----
    fun currentTarget(): DownloadTarget {
        val loc = settings.value.downloadLocation
        return when {
            loc == "app" -> AppDirTarget(context)
            loc.startsWith("saf:") -> SafDirTarget(context, Uri.parse(loc.removePrefix("saf:")))
            else -> PublicDownloadsTarget(context)
        }
    }

    fun currentTargetLabel(): String = runCatching { currentTarget().display }
        .getOrDefault("系统公共下载目录（Download）")

    // ================= 前台服务下载通知 =================

    /** 依据当前下载状态刷新通知；无活动下载则停止前台服务 */
    private fun updateDownloadNotification() {
        val single = singleDownload.value
        val batchActive = batchRunning.value
        if (!single.running && !batchActive) {
            DownloadForegroundService.stop(context)
            return
        }
        DownloadForegroundService.start(context)
        val text: String
        val done: Long
        val total: Long?
        if (single.running) {
            done = single.done
            total = single.total
            text = if (total != null && total > 0) {
                String.format(Locale.US, "下载中 %.1f%%  %s/%s", single.pct, com.anysearch.android.util.Fmt.size(done), com.anysearch.android.util.Fmt.size(total))
            } else {
                "下载中 ${com.anysearch.android.util.Fmt.size(done)}"
            }
        } else {
            done = 0
            total = null
            val finished = batchTasks.value.count { it.status == "完成" }
            text = "批量下载 $finished/${batchTasks.value.size} 任务完成"
        }
        DownloadForegroundService.update(context, text, done, total)
    }

    // ================= 搜索 =================
    fun search(query: String, maxResults: Int) {
        val timeout = settings.value.apiTimeout
        status.value = "正在搜索：$query ..."
        scope.launch {
            try {
                val (obj, _) = ApiClient.call(apiKey.value.trim(), "search", mapOf("query" to query, "max_results" to maxResults), timeout)
                searchText.value = when {
                    obj == null -> "[响应不是 JSON]\n"
                    obj.has("error") -> "[API 错误] " + obj.get("error").toString() + "\n"
                    else -> (ApiClient.extractResultText(obj) ?: obj.toString(2)) + "\n"
                }
                status.value = "搜索完成"
            } catch (e: Exception) {
                searchText.value = "[错误] ${e.message}\n"
                status.value = "出错"
                events.tryEmit("搜索失败：${e.message}")
            }
        }
    }

    // ================= 网页抓取 =================
    fun extract(url: String) {
        val timeout = settings.value.apiTimeout
        status.value = "正在抓取：$url ..."
        scope.launch {
            try {
                val (obj, _) = ApiClient.call(apiKey.value.trim(), "extract", mapOf("url" to url), timeout)
                extractText.value = when {
                    obj == null -> "[响应不是 JSON]\n"
                    obj.has("error") -> "[API 错误] " + obj.get("error").toString() + "\n"
                    else -> (ApiClient.extractResultText(obj) ?: obj.toString(2)) + "\n"
                }
                status.value = "抓取完成"
            } catch (e: Exception) {
                extractText.value = "[错误] ${e.message}\n"
                status.value = "出错"
                events.tryEmit("抓取失败：${e.message}")
            }
        }
    }

    fun extractLinks(url: String) {
        val timeout = settings.value.apiTimeout
        status.value = "正在抓取并提取链接：$url ..."
        scope.launch {
            try {
                val (obj, _) = ApiClient.call(apiKey.value.trim(), "extract", mapOf("url" to url), timeout)
                extractText.value = when {
                    obj == null -> "[响应不是 JSON]\n"
                    obj.has("error") -> "[API 错误] " + obj.get("error").toString() + "\n"
                    else -> {
                        val text = ApiClient.extractResultText(obj) ?: ""
                        val links = ApiClient.extractLinks(text)
                        "共提取到 ${links.size} 个链接：\n\n" +
                            links.mapIndexed { i, l -> "${i + 1}. $l" }.joinToString("\n") + "\n"
                    }
                }
                status.value = "链接提取完成"
            } catch (e: Exception) {
                extractText.value = "[错误] ${e.message}\n"
                status.value = "出错"
                events.tryEmit("提取链接失败：${e.message}")
            }
        }
    }

    // ================= 单文件下载 =================
    fun startSingleDownload(
        url: String,
        target: DownloadTarget,
        threads: Int,
        timeoutSeconds: Int,
        autoChunk: Boolean = true,
        chunkSizeBytes: Long = 4L * 1024 * 1024,
    ) {
        val cancel = AtomicBoolean(false)
        singleCancel = cancel
        singleAbort.set(false)
        singleDownload.value = SingleDownload(url = url, running = true, message = "0%")
        downloadLog.value = ""
        status.value = "正在下载：$url ..."
        updateDownloadNotification()

        scope.launch {
            appendDownloadLog("目标：${target.display}")
            var prevDone = 0L
            var prevTime = System.currentTimeMillis()

            // 节流：最多每 200ms 更新一次进度（桌面版按秒刷新显示，这里取更平滑的值）
            val progress: (Long, Long?) -> Unit = { done, total ->
                val now = System.currentTimeMillis()
                if (now - prevTime >= 200 || (total != null && done == total)) {
                    val dt = (now - prevTime) / 1000.0
                    val speed = if (dt > 0) (done - prevDone) / dt else 0.0
                    prevDone = done
                    prevTime = now
                    val pct = if (total != null && total > 0) minOf(100f, done * 100f / total) else 0f
                    singleDownload.value = SingleDownload(url, true, false, pct, done, total, speed, message = String.format(Locale.US, "%.1f%%", pct))
                    updateDownloadNotification()
                }
            }
            val mode: (DownloadMode, Int, Long?) -> Unit = { m, n, _ ->
                appendDownloadLog(
                    when (m) {
                        DownloadMode.MULTITHREAD -> "服务器支持分块下载，正在使用 $n 线程多线程下载。"
                        DownloadMode.RESUME -> "检测到未完成下载，从断点续传。"
                        DownloadMode.SOURCE_SWITCH -> "当前源不可用或过慢，正在切换镜像源继续下载…"
                        DownloadMode.SOURCE_FAIL -> "镜像源不可用（404/内容不一致），已跳过。"
                        else -> "服务器不支持分块下载（或文件太小），已回退单线程下载。"
                    }
                )
            }

            val s0 = settings.value

            fun finishCancelled() {
                if (singleAbort.get()) {
                    resolvedUrls = null
                    resolvedReferer = null
                    downloader.discardFor(url)
                    appendDownloadLog("已取消下载，未完成的文件与断点已删除。")
                    status.value = "已取消"
                    singleDownload.value = SingleDownload(url = url, running = false, message = "已取消")
                } else {
                    appendDownloadLog("已暂停（断点已保留，点击“继续下载”从断点续传）。")
                    status.value = "已暂停"
                    singleDownload.value = SingleDownload(url = url, running = false, paused = true, message = "已暂停")
                }
                updateDownloadNotification()
            }

            // 依次下载多个 URL；网页错误/失效直链/被拒直链向上抛；其余就地收尾返回 false
            suspend fun runTargets(
                targets: List<Pair<String, String?>>,
                staleRecover: Boolean,
                resolvedAttempt: Boolean,
            ): Boolean {
                for ((tUrl, tRef) in targets) {
                    if (cancel.get()) {
                        finishCancelled()
                        return false
                    }
                    try {
                        val result = downloader.downloadFile(
                            url = tUrl,
                            target = target,
                            numThreads = threads,
                            timeoutSeconds = timeoutSeconds.coerceAtLeast(5).toLong(),
                            progressCallback = progress,
                            modeCallback = mode,
                            cancel = cancel,
                            autoChunk = autoChunk,
                            chunkSizeBytes = chunkSizeBytes,
                            mirrors = s0.mirrors,
                            mirrorStrategy = s0.mirrorStrategy,
                            mirrorSpeedLimit = s0.mirrorSpeedLimitKbps.toLong() * 1024,
                            mirrorProbeConns = s0.mirrorProbeConns,
                            referer = tRef,
                        )
                        appendDownloadLog("下载完成：${result.displayName}")
                    } catch (e: HtmlPageError) {
                        throw e
                    } catch (e: DownloadCancelled) {
                        finishCancelled()
                        return false
                    } catch (e: DownloadPaused) {
                        appendDownloadLog("${e.message}")
                        status.value = "已暂停（点击“继续下载”从断点续传）"
                        singleDownload.value = SingleDownload(url = url, running = false, paused = true, message = "已暂停")
                        events.tryEmit(e.message ?: "下载已暂停")
                        updateDownloadNotification()
                        return false
                    } catch (e: Exception) {
                        if (staleRecover) {
                            appendDownloadLog("上次解析的直链已失效（${e.message}），正在重新解析…")
                            throw StaleResolved()
                        }
                        if (!resolvedAttempt && s0.resolveLinks && isBlockError(e.message)) {
                            appendDownloadLog("直连被拒绝（${e.message}），尝试解析页面获取真实直链…")
                            throw BlockedRetry()
                        }
                        if (resolvedAttempt) {
                            appendDownloadLog("${e.message}\n该直链可能绑定浏览器会话或已失效：请用浏览器打开页面，点击下载并复制链接后重试。")
                        }
                        appendDownloadLog("下载失败：${e.message}")
                        status.value = "出错"
                        singleDownload.value = SingleDownload(url = url, running = false, message = "失败")
                        events.tryEmit("下载失败：${e.message}")
                        updateDownloadNotification()
                        return false
                    }
                }
                return true
            }

            try {
                // ---------- 直链优先：直接下载；只有返回网页/被拒绝才启用解析 ----------
                val targets: List<Pair<String, String?>>
                if (resolvedUrls != null && singleDownload.value.paused) {
                    // 暂停后继续：复用上次解析出的直链（签名直链每次解析都不同）
                    targets = resolvedUrls!!.map { it to resolvedReferer }
                } else {
                    targets = listOf(url to null)
                }
                val staleRecover = resolvedUrls != null && s0.resolveLinks
                if (runTargets(targets, staleRecover = staleRecover, resolvedAttempt = resolvedUrls != null)) {
                    resolvedUrls = null
                    resolvedReferer = null
                    status.value = "下载完成"
                    singleDownload.value = SingleDownload(url, false, false, 100f,
                        singleDownload.value.done, singleDownload.value.total, 0.0, "100%")
                    events.tryEmit("下载完成")
                    updateDownloadNotification()
                }
                return@launch
            } catch (e: HtmlPageError) {
                if (!s0.resolveLinks) {
                    appendDownloadLog("目标链接返回的是网页而不是文件（已关闭自动解析，请改用真实直链）。")
                    status.value = "出错"
                    singleDownload.value = SingleDownload(url = url, running = false, message = "失败")
                    updateDownloadNotification()
                    return@launch
                }
            } catch (e: BlockedRetry) {
                // 直连被拒：进入解析流程
            } catch (e: StaleResolved) {
                // 旧直链失效：进入解析流程
            }

            // ---------- 解析阶段 ----------
            status.value = "检测到网页链接，正在解析真实下载地址…"
            singleDownload.value = SingleDownload(url = url, running = true, message = "解析中…")
            try {
                val rr = resolver.resolve(
                    url = url,
                    timeoutSeconds = timeoutSeconds.coerceAtLeast(5).toLong(),
                    cancel = cancel,
                    interactive = { ch -> askCaptcha(ch) },
                    progress = { _, d -> status.value = d },
                )
                if (rr.options.isEmpty()) {
                    appendDownloadLog(rr.note.ifBlank { "页面中未找到可下载的链接。" })
                    status.value = "未找到可下载的链接"
                    singleDownload.value = SingleDownload(url = url, running = false, message = "失败")
                    events.tryEmit(rr.note.ifBlank { "页面中未找到可下载的链接。" })
                    updateDownloadNotification()
                    return@launch
                }
                val chosen: List<LinkOption> = when {
                    rr.options.size == 1 -> listOf(rr.options[0])
                    s0.resolveAutoBest -> listOf(rr.options[0]).also {
                        appendDownloadLog("解析出 ${rr.options.size} 个选项，已自动选择最佳。")
                    }
                    else -> {
                        val picked = askOptions(rr) ?: run {
                            status.value = "未选择下载选项"
                            singleDownload.value = SingleDownload(url = url, running = false, message = "未选择")
                            updateDownloadNotification()
                            return@launch
                        }
                        rr.options.filter { it.url in picked }
                    }
                }
                if (chosen.isEmpty()) {
                    status.value = "未选择下载选项"
                    singleDownload.value = SingleDownload(url = url, running = false, message = "未选择")
                    updateDownloadNotification()
                    return@launch
                }
                resolvedUrls = chosen.map { it.url }
                resolvedReferer = rr.finalUrl
                status.value = "正在下载…"
                singleDownload.value = SingleDownload(url = url, running = true, message = "0%")
                if (runTargets(chosen.map { it.url to rr.finalUrl }, staleRecover = false, resolvedAttempt = true)) {
                    resolvedUrls = null
                    resolvedReferer = null
                    status.value = "下载完成"
                    singleDownload.value = SingleDownload(url, false, false, 100f,
                        singleDownload.value.done, singleDownload.value.total, 0.0, "100%")
                    events.tryEmit("下载完成")
                    updateDownloadNotification()
                }
            } catch (e: DownloadCancelled) {
                finishCancelled()
            } catch (e: ResolveError) {
                val msg = e.message ?: ""
                val advice = if (isBlockError(msg)) {
                    "该站点拒绝了自动抓取（可能有人机验证或风控）。\n建议：用浏览器打开该页面，手动获取真实下载直链后重试；\n若持续被拒，请停止使用本工具下载该站点。"
                } else {
                    "解析失败（瞬时网络错误已自动重试 1 次）。\n请检查网络/代理设置，或改用真实直链后重试。"
                }
                appendDownloadLog("链接解析失败：$msg\n$advice")
                status.value = "解析失败"
                singleDownload.value = SingleDownload(url = url, running = false, message = "失败")
                events.tryEmit("链接解析失败：$msg")
                updateDownloadNotification()
            }
        }
    }

    fun cancelSingleDownload() {
        // 暂停：保留断点，可继续
        singleCancel.set(true)
        downloader.cancelActive()
        dismissPendingDialogs()
        status.value = "正在暂停…（断点保留，可继续下载）"
    }

    /** 取消：删除未完成文件与断点 */
    fun abortSingleDownload() {
        singleAbort.set(true)
        singleCancel.set(true)
        downloader.cancelActive()
        dismissPendingDialogs()
        val st = singleDownload.value
        if (!st.running) {
            // 已暂停：worker 已退出，直接收尾（否则永远停在“正在取消…”）
            downloader.discardFor(st.url)
            resolvedUrls?.forEach { downloader.discardFor(it) }
            resolvedUrls = null
            resolvedReferer = null
            appendDownloadLog("已取消下载，未完成的文件与断点已删除。")
            status.value = "已取消"
            singleDownload.value = SingleDownload(url = st.url, running = false, message = "已取消")
            updateDownloadNotification()
            return
        }
        status.value = "正在取消…（将删除已下载内容）"
    }

    /** 放弃当前下载的断点（下次从头开始） */
    fun discardCurrentDownload() {
        val url = singleDownload.value.url
        if (url.isNotBlank()) downloader.discardFor(url)
        singleDownload.value = SingleDownload(url = url, running = false, message = "断点已放弃")
        appendDownloadLog("已放弃断点，下次下载将从头开始。")
        status.value = "断点已放弃"
    }

    /** 断点缓存占用字节数（设置页展示用） */
    fun partsCacheBytes(): Long = runCatching { downloader.partsCacheBytes() }.getOrDefault(0L)

    /** 清理全部断点缓存，返回释放字节数 */
    fun cleanupPartsCache(): Long = runCatching { downloader.cleanupParts() }.getOrDefault(0L)

    private fun appendDownloadLog(line: String) {
        downloadLog.value = (downloadLog.value + line + "\n").let {
            // 日志最多保留 8000 字符，防止无限增长
            if (it.length > 8000) it.takeLast(8000) else it
        }
    }

    // ================= 批量下载 =================
    /** 批量任务的运行上下文（重试时复用） */
    private var batchCtx: BatchContext? = null

    data class BatchContext(
        val target: DownloadTarget,
        val threads: Int,
        val timeoutSeconds: Int,
        val autoChunk: Boolean,
        val chunkSizeBytes: Long,
        val mirrors: List<String>,
        val mirrorStrategy: String,
        val mirrorSpeedLimit: Long,
        val mirrorProbeConns: Int,
    )

    /**
     * 启动批量下载（连接数上限检查由 UI 层做，因为需要弹窗交互）。
     * parallel: 并行任务数；threads: 每任务线程数。
     */
    fun startBatch(
        urls: List<String>,
        parallel: Int,
        threads: Int,
        timeoutSeconds: Int,
        autoChunk: Boolean = true,
        chunkSizeBytes: Long = 4L * 1024 * 1024,
    ) {
        batchStop = AtomicBoolean(false)
        batchTasks.value = urls.mapIndexed { i, u -> BatchTask(i, u) }
        batchRunning.value = true
        val s = settings.value
        batchCtx = BatchContext(
            currentTarget(), threads, timeoutSeconds, autoChunk, chunkSizeBytes,
            s.mirrors, s.mirrorStrategy,
            s.mirrorSpeedLimitKbps.toLong() * 1024, s.mirrorProbeConns,
        )
        status.value = "批量下载开始"
        updateDownloadNotification()

        scope.launch {
            val semaphore = Semaphore(parallel.coerceIn(1, 5))
            val remaining = AtomicInteger(urls.size)
            val ctx = batchCtx ?: return@launch
            val tasks = batchTasks.value
            for (task in tasks) {
                launch {
                    updateBatch(task.iid, status = "排队中")
                    semaphore.acquire()
                    try {
                        if (batchStop.get() || task.cancel.get()) return@launch
                        runBatchOne(task, ctx)
                    } finally {
                        semaphore.release()
                        if (remaining.decrementAndGet() == 0) {
                            status.value = "批量下载结束"
                            batchRunning.value = false
                            updateDownloadNotification()
                        }
                    }
                }
            }
        }
    }

    fun cancelBatchTask(iid: Int) {
        // 暂停：保留断点，可继续
        val t = batchTasks.value.firstOrNull { it.iid == iid } ?: return
        t.cancel.set(true)
        updateBatch(iid, status = "暂停中")
    }

    /** 取消：删除该任务的未完成文件与断点 */
    fun deleteBatchTask(iid: Int) {
        val t = batchTasks.value.firstOrNull { it.iid == iid } ?: return
        t.discard.set(true)
        t.cancel.set(true)
        if (t.status == "已暂停") {
            // 暂停态：worker 已退出，直接丢弃断点并标记已取消（否则永远停在“取消中”）
            downloader.discardFor(t.url)
            t.resolved?.forEach { downloader.discardFor(it) }
            t.resolved = null
            t.referer = null
            updateBatch(iid, status = "已取消")
            return
        }
        t.resolved = null
        t.referer = null
        dismissPendingDialogs()
        updateBatch(iid, status = "取消中")
    }

    /** 继续/重试暂停或失败的批量任务（自动从断点续传） */
    fun retryBatchTask(iid: Int) {
        val ctx = batchCtx ?: return
        val old = batchTasks.value.firstOrNull { it.iid == iid } ?: return
        val task = old.copy(
            cancel = AtomicBoolean(false),
            discard = AtomicBoolean(false),
            status = "下载中", pct = "", speed = "",
        )
        batchTasks.value = batchTasks.value.map { if (it.iid == iid) task else it }
        scope.launch {
            runBatchOne(task, ctx)
        }
    }

    fun stopBatch() {
        // 暂停全部：保留所有断点，可逐行继续
        batchStop.set(true)
        batchTasks.value.forEach { it.cancel.set(true) }
        downloader.cancelActive()
        dismissPendingDialogs()
        status.value = "正在暂停全部任务…（断点已保留，可逐行继续）"
        updateDownloadNotification()
    }

    private suspend fun runBatchOne(task: BatchTask, ctx: BatchContext) {
        var prevDone = 0L
        var prevTime = System.currentTimeMillis()

        // 对应桌面版：速率每 0.5s 刷新一次，百分比节流到 100ms（避免快速下载时 UI 高频重组）
        var lastPctUpdate = 0L
        val progress: (Long, Long?) -> Unit = { done, total ->
            val now = System.currentTimeMillis()
            val dt = (now - prevTime) / 1000.0
            val speedText: String? = if (dt >= 0.5) {
                val speed = if (dt > 0) (done - prevDone) / dt else 0.0
                prevDone = done
                prevTime = now
                com.anysearch.android.util.Fmt.size(speed.toLong()) + "/s"
            } else null
            val freshEnough = now - lastPctUpdate >= 100 || (total != null && done == total)
            if (freshEnough) {
                lastPctUpdate = now
                val pctText = if (total != null && total > 0) {
                    String.format(Locale.US, "%.1f%%", minOf(100.0, done * 100.0 / total))
                } else {
                    com.anysearch.android.util.Fmt.size(done)
                }
                updateBatch(task.iid, pct = pctText, speed = speedText)
            } else if (speedText != null) {
                updateBatch(task.iid, speed = speedText)
            }
            updateDownloadNotification()
        }

        val s0 = settings.value

        suspend fun runTargets(
            targets: List<Pair<String, String?>>,
            staleRecover: Boolean,
            resolvedAttempt: Boolean,
        ): Boolean {
            for ((tUrl, tRef) in targets) {
                if (task.cancel.get() || batchStop.get()) {
                    updateBatch(
                        task.iid,
                        status = if (task.discard.get()) {
                            downloader.discardFor(task.url)
                            "已取消"
                        } else "已暂停",
                    )
                    return false
                }
                try {
                    downloader.downloadFile(
                        url = tUrl,
                        target = ctx.target,
                        numThreads = ctx.threads,
                        timeoutSeconds = ctx.timeoutSeconds.coerceAtLeast(5).toLong(),
                        progressCallback = progress,
                        cancel = task.cancel,
                        autoChunk = ctx.autoChunk,
                        chunkSizeBytes = ctx.chunkSizeBytes,
                        mirrors = ctx.mirrors,
                        mirrorStrategy = ctx.mirrorStrategy,
                        mirrorSpeedLimit = ctx.mirrorSpeedLimit,
                        mirrorProbeConns = ctx.mirrorProbeConns,
                        referer = tRef,
                    )
                } catch (e: HtmlPageError) {
                    throw e
                } catch (e: DownloadCancelled) {
                    updateBatch(
                        task.iid,
                        status = if (task.discard.get()) {
                            task.resolved = null
                            task.referer = null
                            downloader.discardFor(task.url)
                            "已取消"
                        } else "已暂停",
                    )
                    return false
                } catch (e: DownloadPaused) {
                    updateBatch(task.iid, status = "已暂停")
                    return false
                } catch (e: Exception) {
                    if (staleRecover) {
                        updateBatch(task.iid, status = "解析中")
                        throw StaleResolved()
                    }
                    if (!resolvedAttempt && s0.resolveLinks && isBlockError(e.message)) {
                        updateBatch(task.iid, status = "解析中")
                        throw BlockedRetry()
                    }
                    if (resolvedAttempt) {
                        updateBatch(task.iid, status = "失败", pct = "", speed = "")
                        return false
                    }
                    updateBatch(task.iid, status = "失败", pct = "", speed = "")
                    return false
                }
            }
            return true
        }

        // ---------- 直链优先：直接下载；只有返回网页/被拒绝才启用解析 ----------
        try {
            val chosen = task.resolved
            if (chosen != null) {
                updateBatch(task.iid, status = "下载中")
                if (runTargets(chosen.map { it to task.referer }, staleRecover = s0.resolveLinks, resolvedAttempt = true)) {
                    task.resolved = null
                    task.referer = null
                    updateBatch(task.iid, status = "完成")
                }
                return
            }
            updateBatch(task.iid, status = "下载中")
            if (runTargets(listOf(task.url to null), staleRecover = false, resolvedAttempt = false)) {
                updateBatch(task.iid, status = "完成")
            }
            return
        } catch (e: HtmlPageError) {
            if (!s0.resolveLinks) {
                updateBatch(task.iid, status = "失败", pct = "", speed = "")
                return
            }
        } catch (e: BlockedRetry) {
            // 直连被拒：进入解析流程
        } catch (e: StaleResolved) {
            // 旧直链失效：进入解析流程
        }

        // ---------- 解析阶段 ----------
        updateBatch(task.iid, status = "解析中")
        try {
            val rr = resolver.resolve(
                url = task.url,
                timeoutSeconds = ctx.timeoutSeconds.coerceAtLeast(5).toLong(),
                cancel = task.cancel,
                interactive = { ch -> askCaptcha(ch) },
                progress = { _, d -> status.value = d },
            )
            if (rr.options.isEmpty()) {
                updateBatch(task.iid, status = "失败", pct = "", speed = "")
                return
            }
            val chosen: List<LinkOption> = when {
                rr.options.size == 1 -> listOf(rr.options[0])
                s0.resolveAutoBest -> listOf(rr.options[0])
                else -> {
                    val picked = askOptions(rr) ?: run {
                        updateBatch(task.iid, status = "已跳过", pct = "", speed = "")
                        return
                    }
                    rr.options.filter { it.url in picked }
                }
            }
            if (chosen.isEmpty()) {
                updateBatch(task.iid, status = "已跳过", pct = "", speed = "")
                return
            }
            task.resolved = chosen.map { it.url }
            task.referer = rr.finalUrl
            updateBatch(task.iid, status = "下载中")
            if (runTargets(chosen.map { it.url to rr.finalUrl }, staleRecover = false, resolvedAttempt = true)) {
                task.resolved = null
                task.referer = null
                updateBatch(task.iid, status = "完成")
            }
        } catch (e: DownloadCancelled) {
            updateBatch(
                task.iid,
                status = if (task.discard.get()) {
                    task.resolved = null
                    task.referer = null
                    downloader.discardFor(task.url)
                    "已取消"
                } else "已暂停",
            )
        } catch (e: ResolveError) {
            updateBatch(task.iid, status = "失败", pct = "", speed = "")
            events.tryEmit("批量任务解析失败：${e.message}")
        }
    }

    private fun updateBatch(iid: Int, status: String? = null, pct: String? = null, speed: String? = null) {
        batchTasks.value = batchTasks.value.map {
            if (it.iid == iid) it.copy(status = status ?: it.status, pct = pct ?: it.pct, speed = speed ?: it.speed) else it
        }
    }
}
