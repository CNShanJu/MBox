package com.github.tvbox.osc.ui.splash

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.util.HeavyTaskUtil
import com.github.tvbox.osc.util.OkGoHelper
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把节日海报保存到系统相册。
 *
 * - 已经缓存到 `splash_media` 的直接复制，未缓存的先下载到私有缓存目录再写入相册；
 * - Android 10+ 走 MediaStore（`Pictures/MBox`），不需要任何运行时权限；
 * - Android 9 及以下需要 WRITE_EXTERNAL_STORAGE，由调用方申请后调用。
 */
internal object HolidayPosterSaver {

    sealed interface Result {
        data class Saved(val fileName: String) : Result
        /** 平台要求存储权限，调用方应申请后再调一次。 */
        data object NeedsPermission : Result
        data object Failed : Result
    }

    /** Android 10 起应用可直接写入媒体库，无需存储权限。 */
    fun needsLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /**
     * 后台下载/复制并写入相册，结果回主线程。
     *
     * @param cachedPath 已缓存海报的本地路径，null 表示需要用 [url] 现下
     */
    fun save(
        context: Context,
        cachedPath: String?,
        url: String,
        holidayName: String,
        onResult: (Result) -> Unit
    ) {
        val appContext = context.applicationContext
        HeavyTaskUtil.executeBigTask {
            val result = try {
                if (needsLegacyPermission() && !hasLegacyWritePermission(appContext)) {
                    Result.NeedsPermission
                } else {
                    val source = cachedPath?.let { File(it).takeIf(File::isFile) }
                        ?: download(appContext, url)
                    if (source == null) {
                        Result.Failed
                    } else {
                        writeToGallery(appContext, source, holidayName) ?: Result.Failed
                    }
                }
            } catch (error: Throwable) {
                LogStore.fail(Category.SYSTEM, "节日海报: 保存失败，原因=${error.javaClass.simpleName}")
                Result.Failed
            }
            MAIN.post { onResult(result) }
        }
    }

    private fun hasLegacyWritePermission(context: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun download(context: Context, url: String): File? {
        val client = OkGoHelper.getImageClient() ?: OkGoHelper.getDefaultClient() ?: return null
        val target = File(context.cacheDir, "holiday_poster_download")
        return try {
            client.newCall(Request.Builder().url(url).header("Accept", "image/*").build())
                .execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null) return null
                    FileOutputStream(target).use { output ->
                        body.byteStream().use { input -> input.copyTo(output, 16 * 1024) }
                    }
                    target.takeIf { it.length() > 1024 }
                }
        } catch (error: Throwable) {
            LogStore.fail(Category.SYSTEM, "节日海报: 下载失败，原因=${error.javaClass.simpleName}")
            null
        }
    }

    /** 写入相册；成功返回文件名，失败返回 null。 */
    private fun writeToGallery(context: Context, source: File, holidayName: String): Result.Saved? {
        val extension = sniffExtension(source) ?: "jpg"
        // "jpg" 的 MIME 是 image/jpeg，不能直接拼 "image/$extension"。
        val mimeType = when (extension) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        val fileName = buildFileName(holidayName, extension)
        val resolver = context.contentResolver
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MBox")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output, 32 * 1024) }
                } ?: return null
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Result.Saved(fileName)
            } else {
                @Suppress("DEPRECATION")
                val pictures = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES
                )
                val directory = File(pictures, "MBox")
                if (!directory.isDirectory && !directory.mkdirs()) return null
                val destination = File(directory, fileName)
                source.copyTo(destination, overwrite = true)
                MediaScannerConnection.scanFile(
                    context, arrayOf(destination.absolutePath), arrayOf(mimeType), null
                )
                Result.Saved(fileName)
            }
        } catch (error: Throwable) {
            LogStore.fail(Category.SYSTEM, "节日海报: 写入相册失败，原因=${error.javaClass.simpleName}")
            null
        }
    }

    /** 相册里的文件名：MBox_中秋_20261122_122233.jpg，去掉文件名非法字符。 */
    private fun buildFileName(holidayName: String, extension: String): String {
        val safeName = holidayName.filter { it.isLetterOrDigit() || it in "._-（）()" }
            .ifEmpty { "poster" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "MBox_${safeName}_$stamp.$extension"
    }

    private fun sniffExtension(file: File): String? {
        val header = ByteArray(12)
        val read = file.inputStream().use { it.read(header) }
        return HolidaySplashMediaCatalog.sniffImageExtension(header, read)
    }

    private val MAIN = Handler(Looper.getMainLooper())
}
