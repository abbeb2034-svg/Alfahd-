package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object ImageStorageManager {

    private const val MAX_DIMENSION = 1600
    private const val JPEG_QUALITY = 85

    fun createCameraCaptureUri(context: Context): Pair<Uri, File> {
        val dir = File(context.filesDir, "camera_tmp").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return uri to file
    }

    suspend fun saveCompressedDocumentImage(
        context: Context,
        sourceUri: Uri,
        subFolder: String = "id_documents",
        filePrefix: String = "doc"
    ): SavedImageResult = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
            ?: throw IllegalStateException("تعذر قراءة ملف الصورة المحدد")

        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("الصورة غير صالحة أو تالفة")

        val scaled = scaleDownKeepingAspect(decoded, MAX_DIMENSION)
        val outputBytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        if (scaled !== decoded) {
            decoded.recycle()
        }
        scaled.recycle()

        val targetDir = File(context.filesDir, subFolder).apply { mkdirs() }
        val fileName = "${filePrefix}_${SecurityUtils.newUuid()}.jpg"
        val targetFile = File(targetDir, fileName)
        FileOutputStream(targetFile).use { it.write(outputBytes) }

        SavedImageResult(
            absolutePath = targetFile.absolutePath,
            sizeBytes = outputBytes.size.toLong(),
            sha256 = SecurityUtils.sha256Hex(outputBytes)
        )
    }

    suspend fun saveCompressedFromFile(
        context: Context,
        sourceFile: File,
        subFolder: String = "id_documents",
        filePrefix: String = "cam"
    ): SavedImageResult = withContext(Dispatchers.IO) {
        val bytes = sourceFile.readBytes()
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("تعذر معالجة الصورة الملتقطة")

        val scaled = scaleDownKeepingAspect(decoded, MAX_DIMENSION)
        val outputBytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        if (scaled !== decoded) {
            decoded.recycle()
        }
        scaled.recycle()

        val targetDir = File(context.filesDir, subFolder).apply { mkdirs() }
        val fileName = "${filePrefix}_${SecurityUtils.newUuid()}.jpg"
        val targetFile = File(targetDir, fileName)
        FileOutputStream(targetFile).use { it.write(outputBytes) }
        sourceFile.delete()

        SavedImageResult(
            absolutePath = targetFile.absolutePath,
            sizeBytes = outputBytes.size.toLong(),
            sha256 = SecurityUtils.sha256Hex(outputBytes)
        )
    }

    private fun scaleDownKeepingAspect(bitmap: Bitmap, maxDim: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDim && height <= maxDim) return bitmap
        val ratio = width.toFloat() / height.toFloat()
        val newW: Int
        val newH: Int
        if (width >= height) {
            newW = maxDim
            newH = (maxDim / ratio).toInt().coerceAtLeast(1)
        } else {
            newH = maxDim
            newW = (maxDim * ratio).toInt().coerceAtLeast(1)
        }
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}

data class SavedImageResult(
    val absolutePath: String,
    val sizeBytes: Long,
    val sha256: String
)
