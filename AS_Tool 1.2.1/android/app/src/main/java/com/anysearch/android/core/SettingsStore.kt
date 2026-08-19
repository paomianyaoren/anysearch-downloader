package com.anysearch.android.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 设置项（对应桌面版 anysearch_settings.json）。
 *
 * 与桌面版差异：
 *  - 桌面版的 chunk_size（分块渲染）是 tkinter 文本框卡顿的补丁，
 *    Compose 原生按需渲染，无需该参数，故安卓版不保留。
 *  - 新增 downloadLocation：下载保存位置（public / app / saf:<uri>），安卓特有。
 */
data class Settings(
    val maxDisplayLen: Int = 80000,
    val apiTimeout: Int = 60,
    val downloadTimeout: Int = 60,
    val defaultThreads: Int = 8,
    val useProxy: Boolean = false,
    val proxyUrl: String = "",
    val customHeaders: String = "",
    val maxTotalConnections: Int = 16,
    val allowBreakLimit: Boolean = false,
    val downloadLocation: String = "public",
    val autoChunk: Boolean = true,
    val chunkSizeMb: Int = 4,
    val mirrors: List<String> = emptyList(),
    val mirrorStrategy: String = "direct",
    val mirrorSpeedLimitKbps: Int = 0,
    val mirrorProbeConns: Int = 3,
    val resolveLinks: Boolean = true,
    val resolveAutoBest: Boolean = false,
)

object SettingsStore {
    private const val FILE = "anysearch_settings.json"

    fun load(context: Context): Settings {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return Settings()
        return try {
            val o = JSONObject(f.readText())
            Settings(
                maxDisplayLen = o.optInt("max_display_len", 80000),
                apiTimeout = o.optInt("api_timeout", 60),
                downloadTimeout = o.optInt("download_timeout", 60),
                defaultThreads = o.optInt("default_threads", 8),
                useProxy = o.optBoolean("use_proxy", false),
                proxyUrl = o.optString("proxy_url", ""),
                customHeaders = o.optString("custom_headers", ""),
                maxTotalConnections = o.optInt("max_total_connections", 16),
                allowBreakLimit = o.optBoolean("allow_break_limit", false),
                downloadLocation = o.optString("download_location", "public"),
                autoChunk = o.optBoolean("auto_chunk", true),
                chunkSizeMb = o.optInt("chunk_size_mb", 4),
                mirrors = run {
                    val arr = o.optJSONArray("mirrors")
                    if (arr != null) (0 until arr.length()).map { arr.optString(it) } else emptyList()
                },
                mirrorStrategy = o.optString("mirror_strategy", "direct"),
                mirrorSpeedLimitKbps = o.optInt("mirror_speed_limit_kbps", 0),
                mirrorProbeConns = o.optInt("mirror_probe_conns", 3),
                resolveLinks = o.optBoolean("resolve_links", true),
                resolveAutoBest = o.optBoolean("resolve_auto_best", false),
            )
        } catch (e: Exception) {
            Settings()
        }
    }

    fun save(context: Context, s: Settings) {
        val o = JSONObject().apply {
            put("max_display_len", s.maxDisplayLen)
            put("api_timeout", s.apiTimeout)
            put("download_timeout", s.downloadTimeout)
            put("default_threads", s.defaultThreads)
            put("use_proxy", s.useProxy)
            put("proxy_url", s.proxyUrl)
            put("custom_headers", s.customHeaders)
            put("max_total_connections", s.maxTotalConnections)
            put("allow_break_limit", s.allowBreakLimit)
            put("download_location", s.downloadLocation)
            put("auto_chunk", s.autoChunk)
            put("chunk_size_mb", s.chunkSizeMb)
            put("mirrors", org.json.JSONArray().apply { s.mirrors.forEach { put(it) } })
            put("mirror_strategy", s.mirrorStrategy)
            put("mirror_speed_limit_kbps", s.mirrorSpeedLimitKbps)
            put("mirror_probe_conns", s.mirrorProbeConns)
            put("resolve_links", s.resolveLinks)
            put("resolve_auto_best", s.resolveAutoBest)
        }
        File(context.filesDir, FILE).writeText(o.toString(2))
    }

    /** 把多行文本 "名称: 值" 解析成 headers（对应桌面版 _parse_headers） */
    fun parseHeaders(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val idx = t.indexOf(':')
            if (idx > 0) {
                result[t.substring(0, idx).trim()] = t.substring(idx + 1).trim()
            }
        }
        return result
    }
}
