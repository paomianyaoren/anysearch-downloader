package com.anysearch.android.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/**
 * 下载落盘目标（安卓特有抽象，对应桌面版的“保存目录”）。
 *
 * 桌面版直接写任意本地路径；安卓受分区存储限制，提供三种目标：
 *  - 系统公共下载目录（API 29+ 走 MediaStore；API 26-28 直接写公共 Download 目录，需 WRITE_EXTERNAL_STORAGE）
 *  - 应用专属下载目录（无需任何权限，路径见应用信息）
 *  - 用户通过系统文件选择器（SAF）授权的自定义文件夹
 *
 * 下载器总是先写入应用缓存临时文件（支持随机偏移写入），校验通过后由这里拷入最终位置。
 */
interface DownloadTarget {
    /** 给用户看的当前位置说明 */
    val display: String

    /** 把校验通过的临时文件写入最终位置，返回用户可见的最终位置描述 */
    suspend fun writeFrom(temp: File, fileName: String, mimeType: String, total: Long): String
}

private fun copyOrMove(temp: File, dest: File) {
    try {
        // 跨文件系统 move 会失败，此时回退 copy + delete
        Files.move(temp.toPath(), dest.toPath())
    } catch (e: Exception) {
        temp.copyTo(dest, overwrite = true)
        temp.delete()
    }
}

/** 应用专属下载目录：Android/data/<包名>/files/Download/downloads（无需权限） */
class AppDirTarget(context: Context) : DownloadTarget {

    private val dir: File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
        "downloads",
    ).apply { mkdirs() }

    override val display: String get() = "应用专属目录：$dir"

    override suspend fun writeFrom(temp: File, fileName: String, mimeType: String, total: Long): String =
        withContext(Dispatchers.IO) {
            var dest = File(dir, fileName)
            if (dest.exists()) {
                val ext = dest.extension.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: ""
                dest = File(dir, dest.nameWithoutExtension + "_" + (System.currentTimeMillis() / 1000) + ext)
            }
            copyOrMove(temp, dest)
            dest.absolutePath
        }
}

/** 系统公共下载目录（Download/） */
class PublicDownloadsTarget(private val context: Context) : DownloadTarget {

    override val display: String get() = "系统公共下载目录（Download）"

    override suspend fun writeFrom(temp: File, fileName: String, mimeType: String, total: Long): String =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val mime = mimeType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw DownloadError("无法在系统下载目录创建文件")
                try {
                    val out = resolver.openOutputStream(uri)
                        ?: throw DownloadError("无法写入系统下载目录")
                    out.use { o -> temp.inputStream().use { it.copyTo(o) } }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                "系统下载目录：Download/$fileName"
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                var dest = File(dir, fileName)
                if (dest.exists()) {
                    val ext = dest.extension.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: ""
                    dest = File(dir, dest.nameWithoutExtension + "_" + (System.currentTimeMillis() / 1000) + ext)
                }
                copyOrMove(temp, dest)
                dest.absolutePath
            }
        }
}

/** 用户通过 SAF 选择的文件夹（已持久化授权） */
class SafDirTarget(private val context: Context, private val treeUri: Uri) : DownloadTarget {

    override val display: String get() = runCatching {
        val df = DocumentFile.fromTreeUri(context, treeUri)
        val name = df?.name
        if (name.isNullOrBlank()) "自定义文件夹" else "自定义文件夹：$name"
    }.getOrDefault("自定义文件夹")

    override suspend fun writeFrom(temp: File, fileName: String, mimeType: String, total: Long): String =
        withContext(Dispatchers.IO) {
            val parent = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw DownloadError("无法访问所选文件夹（授权可能已失效，请重新选择）")
            val mime = mimeType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
            val doc = parent.createFile(mime, fileName)
                ?: throw DownloadError("无法在该文件夹创建文件")
            try {
                val out = context.contentResolver.openOutputStream(doc.uri)
                    ?: throw DownloadError("无法写入所选文件夹")
                out.use { o -> temp.inputStream().use { it.copyTo(o) } }
            } catch (e: Exception) {
                runCatching { doc.delete() }
                throw e
            }
            doc.name ?: fileName
        }
}
