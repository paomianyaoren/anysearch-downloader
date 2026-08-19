package com.anysearch.android.core

import android.content.Context
import java.io.File

/** API Key 存取：应用私有目录 anysearch_api_key.txt（对应桌面版同目录 key 文件） */
object KeyStore {
    private const val FILE = "anysearch_api_key.txt"

    fun load(context: Context): String {
        val f = File(context.filesDir, FILE)
        return if (f.exists()) f.readText().trim() else ""
    }

    fun save(context: Context, key: String) {
        File(context.filesDir, FILE).writeText(key.trim())
    }
}
