package com.example.gleannote.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.example.gleannote.core.contract.Limits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/*
 * 图片预处理：HEIC 等格式统一转 JPEG、等比压缩长边 1600 / Q85、重编码丢弃 Exif 位置信息、
 * 计算 SHA-256 供预签名直传与 :complete 校验（PRD FR-04、API.md 5.4）。
 */
object ImageProcessor {

    data class ProcessedImage(
        val localPath: String,
        val fileName: String,
        val contentType: String,
        val bytes: Long,
        val sha256: String,
        val width: Int,
        val height: Int
    )

    private val counter = AtomicInteger(0)

    suspend fun process(context: Context, uri: Uri): ProcessedImage = withContext(Dispatchers.IO) {
        val bitmap = decode(context, uri)
        val scaled = scaleDown(bitmap)
        val dir = File(context.cacheDir, "gleannote/uploads").apply { mkdirs() }
        val file = File(dir, "p_${System.currentTimeMillis()}_${counter.incrementAndGet()}.jpg")

        val width = scaled.width
        val height = scaled.height
        FileOutputStream(file).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, Limits.JPEG_QUALITY, out)
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()

        val bytes = file.readBytes()
        ProcessedImage(
            localPath = file.absolutePath,
            fileName = file.name,
            contentType = "image/jpeg",
            bytes = bytes.size.toLong(),
            sha256 = sha256(bytes),
            width = width,
            height = height
        )
    }

    /* 相机写盘后按文件处理：避免 file:// URI 在部分 ROM 上 ContentResolver 打不开。 */
    suspend fun processFile(context: Context, file: File): ProcessedImage {
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IllegalArgumentException("图片无法读取")
        return try {
            processBitmap(context, bitmap)
        } finally {
            runCatching { file.delete() }
        }
    }

    /* 系统相机预览返回 Bitmap 时复用同一套压缩与摘要口径。 */
    suspend fun processBitmap(context: Context, bitmap: Bitmap): ProcessedImage = withContext(Dispatchers.IO) {
        val scaled = scaleDown(bitmap)
        val width = scaled.width
        val height = scaled.height
        val dir = File(context.cacheDir, "gleannote/uploads").apply { mkdirs() }
        val file = File(dir, "c_${System.currentTimeMillis()}_${counter.incrementAndGet()}.jpg")
        FileOutputStream(file).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, Limits.JPEG_QUALITY, out)
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        val bytes = file.readBytes()
        ProcessedImage(file.absolutePath, file.name, "image/jpeg", bytes.size.toLong(), sha256(bytes), width, height)
    }

    /* API 28+ 用 ImageDecoder 原生支持 HEIC；低版本回退 BitmapFactory（Q-07 兼容口径）。 */
    private fun decode(context: Context, uri: Uri): Bitmap {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
        return bitmap ?: throw IllegalArgumentException("图片无法读取")
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= Limits.IMAGE_LONG_EDGE) return bitmap
        val ratio = Limits.IMAGE_LONG_EDGE.toFloat() / longEdge
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun fileSha256(path: String): String = sha256(File(path).readBytes())

    /* 上传完成后清理临时产物；原始图片副本由笔记页面文件单独管理（主文档 5.6）。 */
    fun cleanup(context: Context) {
        File(context.cacheDir, "gleannote/uploads").deleteRecursively()
    }
}
