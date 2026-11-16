package com.example.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.data.local.AppSettingsEntity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object OfficialAssetsHelper {

    fun loadArabicTypeface(context: Context, bold: Boolean = false): Typeface =
        loadHeaderTypeface(context, "Cairo", bold)

    fun loadHeaderTypeface(context: Context, fontId: String, bold: Boolean = false): Typeface {
        val resId = when (fontId) {
            "Amiri" -> R.font.amiri
            "Tajawal" -> R.font.tajawal
            "IBM Plex Sans Arabic" -> R.font.ibm_plex_sans_arabic
            else -> R.font.cairo
        }
        val base = try {
            ResourcesCompat.getFont(context, resId) ?: Typeface.DEFAULT
        } catch (_: Exception) {
            Typeface.DEFAULT
        }
        return if (bold) Typeface.create(base, Typeface.BOLD) else base
    }

    /**
     * Eagle emblem WITHOUT Basmala — used on Citizen ID Cards.
     */
    fun loadEagleEmblemBitmap(context: Context, settings: AppSettingsEntity?): Bitmap {
        val customPath = settings?.eagleEmblemLocalPath
        if (!customPath.isNullOrBlank()) {
            val file = File(customPath)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
            }
        }
        return BitmapFactory.decodeResource(context.resources, R.drawable.img_eagle_no_basmala)
            ?: Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
    }

    /**
     * Eagle emblem WITH Basmala — used at the center top of Official Forms and Reports.
     */
    fun loadEagleWithBasmalaBitmap(context: Context): Bitmap {
        return BitmapFactory.decodeResource(context.resources, R.drawable.img_eagle_with_basmala)
            ?: Bitmap.createBitmap(260, 140, Bitmap.Config.ARGB_8888)
    }

    /**
     * Renders the unified top-of-page Official Header:
     * Right side: customizable multi-line right text
     * Center: official Eagle + Basmala emblem (preserving exact aspect ratio)
     * Left side: customizable multi-line left text
     * Uses the font family and font size chosen by the user in App Settings.
     */
    fun loadOrRenderOfficialHeaderBitmap(
        context: Context,
        settings: AppSettingsEntity?,
        targetWidthPx: Int = 1600
    ): Bitmap {
        val customPath = settings?.officialHeaderLocalPath
        if (!customPath.isNullOrBlank()) {
            val file = File(customPath)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.let { original ->
                    val aspect = original.height.toFloat() / original.width.toFloat().coerceAtLeast(1f)
                    val targetH = (targetWidthPx * aspect).toInt().coerceAtLeast(120)
                    return Bitmap.createScaledBitmap(original, targetWidthPx, targetH, true)
                }
            }
        }

        val fontId = settings?.headerFontFamily ?: "Cairo"
        val fontSizeSp = (settings?.headerFontSizeSp ?: 13f).coerceIn(10f, 22f)
        val scaleFactor = targetWidthPx / 600f
        val textSizePx = fontSizeSp * scaleFactor

        val typeface = loadHeaderTypeface(context, fontId, bold = true)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F3D32")
            this.typeface = typeface
            textSize = textSizePx
        }

        val rightText = settings?.headerRightText?.ifBlank { "الجمهورية اليمنية\nسجل الفهد الميداني" }
            ?: "الجمهورية اليمنية\nسجل الفهد الميداني"
        val leftText = settings?.headerLeftText?.ifBlank { "نظام سجل الفهد\nالوثائق والكشوفات" }
            ?: "نظام سجل الفهد\nالوثائق والكشوفات"

        val emblem = loadEagleWithBasmalaBitmap(context)
        val emblemAspect = emblem.height.toFloat() / emblem.width.toFloat().coerceAtLeast(1f)
        val emblemMaxW = targetWidthPx * 0.28f
        val emblemW = emblemMaxW
        val emblemH = emblemW * emblemAspect

        val sideWidth = ((targetWidthPx - emblemW) / 2f - 42f).toInt().coerceAtLeast(160)
        val rightLayout = StaticLayout.Builder
            .obtain(rightText, 0, rightText.length, textPaint, sideWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(4f * scaleFactor, 1.05f)
            .setIncludePad(false)
            .build()

        val leftLayout = StaticLayout.Builder
            .obtain(leftText, 0, leftText.length, textPaint, sideWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(4f * scaleFactor, 1.05f)
            .setIncludePad(false)
            .build()

        val contentHeight = maxOf(emblemH, rightLayout.height.toFloat(), leftLayout.height.toFloat())
        val height = (contentHeight + 48f).toInt().coerceAtLeast(220)

        val bitmap = Bitmap.createBitmap(targetWidthPx, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#FFFFFF"))

        // Bottom double official divider line
        val emeraldLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F3D32")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val goldLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B6891B")
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawLine(12f, height - 10f, targetWidthPx - 12f, height - 10f, emeraldLine)
        canvas.drawLine(12f, height - 17f, targetWidthPx - 12f, height - 17f, goldLine)

        // Draw centered Emblem WITH Basmala
        val emblemLeft = (targetWidthPx - emblemW) / 2f
        val emblemTop = (height - 22f - emblemH) / 2f
        canvas.drawBitmap(
            emblem,
            null,
            RectF(emblemLeft, emblemTop, emblemLeft + emblemW, emblemTop + emblemH),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )

        // Draw Right-side text (يمين الشعار)
        canvas.save()
        val rightX = targetWidthPx - 24f - sideWidth
        val rightY = ((height - 22f - rightLayout.height) / 2f).coerceAtLeast(8f)
        canvas.translate(rightX, rightY)
        rightLayout.draw(canvas)
        canvas.restore()

        // Draw Left-side text (يسار الشعار)
        canvas.save()
        val leftX = 24f
        val leftY = ((height - 22f - leftLayout.height) / 2f).coerceAtLeast(8f)
        canvas.translate(leftX, leftY)
        leftLayout.draw(canvas)
        canvas.restore()

        return bitmap
    }

    fun shareFile(context: Context, file: File, mimeType: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun printPdfFile(context: Context, pdfFile: File, jobName: String) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder(pdfFile.name)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback
            ) {
                try {
                    FileInputStream(pdfFile).use { input ->
                        FileOutputStream(destination.fileDescriptor).use { output ->
                            input.copyTo(output)
                        }
                    }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }
        }
        printManager.print(jobName, adapter, PrintAttributes.Builder().build())
    }
}
