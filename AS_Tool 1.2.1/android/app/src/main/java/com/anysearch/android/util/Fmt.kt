package com.anysearch.android.util

import java.util.Locale

/** 大小/速度/时间格式化（对应桌面版 _fmt_size / _fmt / _fmt_time） */
object Fmt {

    /** 字节数 -> 人类可读（B/KB/MB/GB/TB），如 "12.34 MB" */
    fun size(n: Long): String {
        var v = n.toDouble()
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        for ((i, u) in units.withIndex()) {
            if (v < 1024.0 || i == units.size - 1) {
                return String.format(Locale.US, "%.2f %s", v, u)
            }
            v /= 1024.0
        }
        return "0 B"
    }

    /** 秒 -> "m:ss" 或 "h:mm:ss" */
    fun time(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        } else {
            String.format(Locale.US, "%d:%02d", m, sec)
        }
    }
}
