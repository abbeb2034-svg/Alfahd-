package com.example.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.example.data.local.AppSettingsEntity
import com.example.data.local.PersonEntity
import java.io.File
import java.io.FileOutputStream

object CitizenCardGenerator {

    fun renderCardBitmap(
        context: Context,
        person: PersonEntity,
        neighborhoodName: String,
        squareName: String,
        settings: AppSettingsEntity?
    ): Bitmap {
        val width = 1080
        val height = 660
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Card Ivory Background
        canvas.drawColor(Color.parseColor("#FAF8F2"))

        // Top Emerald Header Strip
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F3D32")
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, width.toFloat(), 145f, headerPaint)

        // Gold Accent Band
        val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C59B27")
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 145f, width.toFloat(), 155f, goldPaint)

        // Outer Frame
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F3D32")
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawRect(3f, 3f, width - 3f, height - 3f, borderPaint)

        val boldType = OfficialAssetsHelper.loadArabicTypeface(context, bold = true)
        val regType = OfficialAssetsHelper.loadArabicTypeface(context, bold = false)

        // Eagle Emblem WITHOUT Basmala on Top Right (preserving exact aspect ratio)
        val eagle = OfficialAssetsHelper.loadEagleEmblemBitmap(context, settings)
        val eagleAspect = eagle.width.toFloat() / eagle.height.toFloat().coerceAtLeast(1f)
        val eagleH = 104f
        val eagleW = eagleH * eagleAspect
        val eagleRight = width - 22f
        val eagleTop = 20f
        canvas.drawBitmap(
            eagle,
            null,
            RectF(eagleRight - eagleW, eagleTop, eagleRight, eagleTop + eagleH),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )

        // Header Texts
        val headerTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = boldType
            textSize = 36f
            textAlign = Paint.Align.RIGHT
        }
        val headerSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F4D47C")
            typeface = regType
            textSize = 24f
            textAlign = Paint.Align.RIGHT
        }
        val orgTitle = settings?.organizationTitle?.ifBlank { "سجل الفهد الميداني" } ?: "سجل الفهد الميداني"
        canvas.drawText(orgTitle, eagleRight - eagleW - 22f, 64f, headerTitlePaint)
        canvas.drawText("بطاقة تعريف مواطن رسمية", eagleRight - eagleW - 22f, 110f, headerSubPaint)

        // Citizen Code Badge on Top Left
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C59B27")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(28f, 40f, 270f, 104f), 14f, 14f, badgePaint)
        val codeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#092820")
            typeface = boldType
            textSize = 26f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(person.citizenCode, 149f, 81f, codeTextPaint)

        // Citizen Photo (Right Side) or Initials Box
        val photoBox = RectF(width - 255f, 190f, width - 35f, 430f)
        val photoBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6ECE9")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(photoBox, 16f, 16f, photoBg)
        val photoFile = person.photoLocalPath?.let { File(it) }?.takeIf { it.exists() }
        if (photoFile != null) {
            val photoBmp = BitmapFactory.decodeFile(photoFile.absolutePath)
            if (photoBmp != null) {
                canvas.drawBitmap(
                    photoBmp,
                    null,
                    Rect(photoBox.left.toInt() + 4, photoBox.top.toInt() + 4, photoBox.right.toInt() - 4, photoBox.bottom.toInt() - 4),
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )
            }
        } else {
            val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F3D32")
                typeface = boldType
                textSize = 24f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("صورة", photoBox.centerX(), photoBox.centerY() + 8f, placeholderPaint)
        }
        val photoStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B6891B")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(photoBox, 16f, 16f, photoStroke)

        // QR Code (Left Side - ONLY internal citizen ID)
        val qrSize = 215
        val qrBmp = QrCodeGenerator.generate(person.id, qrSize)
        val qrBox = Rect(35, 195, 35 + qrSize, 195 + qrSize)
        canvas.drawBitmap(qrBmp, null, qrBox, null)
        val qrLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4F5D57")
            typeface = regType
            textSize = 18f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("رمز التحقق الداخلي", 35f + qrSize / 2f, 438f, qrLabelPaint)

        // Citizen Details (Center-Right RTL)
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4F5D57")
            typeface = regType
            textSize = 22f
            textAlign = Paint.Align.RIGHT
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#161E1B")
            typeface = boldType
            textSize = 27f
            textAlign = Paint.Align.RIGHT
        }

        val textRightX = width - 285f
        canvas.drawText("الاسم الكامل:", textRightX, 220f, labelPaint)
        canvas.drawText(person.fullName.take(36), textRightX, 258f, valuePaint)

        canvas.drawText("رقم التعريف / الهوية:", textRightX, 315f, labelPaint)
        val idLine = "${person.citizenCode}  |  ${person.nationalId.ifBlank { "بدون رقم" }}"
        canvas.drawText(idLine, textRightX, 353f, valuePaint)

        canvas.drawText("الحارة والمربع السكني:", textRightX, 410f, labelPaint)
        canvas.drawText("$neighborhoodName — $squareName", textRightX, 448f, valuePaint)

        // Bottom Verification Strip
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EDEAE0")
            style = Paint.Style.FILL
        }
        canvas.drawRect(6f, 520f, width - 6f, height - 6f, footerPaint)
        val footerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F3D32")
            typeface = regType
            textSize = 21f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "هذه البطاقة صادرة عن نظام سجل الفهد ومربوطة إلكترونياً برمز الاستجابة السريعة الداخلي",
            width / 2f,
            585f,
            footerText
        )

        return bmp
    }

    fun exportCardPng(
        context: Context,
        person: PersonEntity,
        neighborhoodName: String,
        squareName: String,
        settings: AppSettingsEntity?
    ): File {
        val bmp = renderCardBitmap(context, person, neighborhoodName, squareName, settings)
        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val outFile = File(dir, "card_${person.citizenCode}.png")
        FileOutputStream(outFile).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bmp.recycle()
        return outFile
    }

    fun exportCardPdf(
        context: Context,
        person: PersonEntity,
        neighborhoodName: String,
        squareName: String,
        settings: AppSettingsEntity?
    ): File {
        val bmp = renderCardBitmap(context, person, neighborhoodName, squareName, settings)
        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdf.startPage(pageInfo)
        val canvas = page.canvas
        // Place crisp card cleanly centered on A4 sheet
        val targetW = 480f
        val targetH = targetW * (bmp.height.toFloat() / bmp.width.toFloat())
        val left = (595f - targetW) / 2f
        val top = 90f
        canvas.drawBitmap(
            bmp,
            null,
            RectF(left, top, left + targetW, top + targetH),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        pdf.finishPage(page)

        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val outFile = File(dir, "card_${person.citizenCode}.pdf")
        FileOutputStream(outFile).use { pdf.writeTo(it) }
        pdf.close()
        bmp.recycle()
        return outFile
    }
}
