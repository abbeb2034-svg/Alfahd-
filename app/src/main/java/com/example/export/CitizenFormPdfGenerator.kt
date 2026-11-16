package com.example.export

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.data.local.AppSettingsEntity
import com.example.data.local.IdentityDocumentEntity
import com.example.data.local.PersonEntity
import com.example.util.SecurityUtils
import java.io.File
import java.io.FileOutputStream

object CitizenFormPdfGenerator {

    fun generateSinglePageFormPdf(
        context: Context,
        person: PersonEntity,
        neighborhoodName: String,
        squareName: String,
        documents: List<IdentityDocumentEntity>,
        settings: AppSettingsEntity?
    ): File {
        val pdf = PdfDocument()
        val pageW = 595
        val pageH = 842
        val margin = 28f
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
        val canvas = page.canvas

        val boldType = OfficialAssetsHelper.loadArabicTypeface(context, bold = true)
        val regType = OfficialAssetsHelper.loadArabicTypeface(context, bold = false)

        // 1. Official Header
        val headerBmp = OfficialAssetsHelper.loadOrRenderOfficialHeaderBitmap(context, settings, 1400)
        val headerAspect = headerBmp.height.toFloat() / headerBmp.width.toFloat().coerceAtLeast(1f)
        val drawW = pageW - margin * 2
        val drawH = (drawW * headerAspect).coerceAtMost(105f)
        canvas.drawBitmap(headerBmp, null, RectF(margin, margin, margin + drawW, margin + drawH), Paint(Paint.FILTER_BITMAP_FLAG))
        headerBmp.recycle()

        var y = margin + drawH + 14f

        // 2. Title Bar
        val titleBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F3D32")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(margin, y, pageW - margin, y + 32f), 6f, 6f, titleBg)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = boldType
            textSize = 14f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("استمارة بيانات مواطن رسمية (${person.citizenCode})", pageW / 2f, y + 21f, titlePaint)
        y += 42f

        // 3. Two-column ruled fields grid
        val rows = listOf(
            "الاسم الكامل" to person.fullName,
            "نوع الهوية / الرقم" to "${person.idType} — ${person.nationalId.ifBlank { "غير مسجل" }}",
            "تاريخ إصدار الهوية" to person.idIssueDate.ifBlank { "غير محدد" },
            "رقم الهاتف" to person.phone.ifBlank { "غير مسجل" },
            "المحافظة / المديرية" to "${person.governorate} / ${person.district}",
            "الحارة / المربع" to "$neighborhoodName — $squareName",
            "المهنة" to person.occupation.ifBlank { "غير محدد" },
            "عدد أفراد الأسرة" to "${person.familySize} أفراد",
            "نوع السكن / الحالة" to "${person.housingType} (${person.housingStatus})",
            "تاريخ النزوح / الانتقال" to person.displacementDate.ifBlank { "لا يوجد" },
            "الحالة الصحية" to "${person.healthStatus} ${if (person.healthDetails.isNotBlank()) "- ${person.healthDetails}" else ""}",
            "تاريخ آخر مساعدة" to person.lastAidDate.ifBlank { "لم يستلم بعد" },
            "المدخل وتاريخ الإدخال" to "${person.createdByName} (${SecurityUtils.formatArabicDateTime(person.createdAt)})"
        )

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2B3B35")
            style = Paint.Style.STROKE
            strokeWidth = 0.9f
        }
        val labelFill = Paint().apply {
            color = Color.parseColor("#EAF2EE")
            style = Paint.Style.FILL
        }
        val labelTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F3D32")
            typeface = boldType
            textSize = 10.5f
        }
        val valTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#161E1B")
            typeface = regType
            textSize = 10.5f
        }

        val totalW = pageW - margin * 2
        val labelW = totalW * 0.34f
        val valW = totalW - labelW
        val rowH = 26f

        for ((label, value) in rows) {
            val labelRect = RectF(pageW - margin - labelW, y, pageW - margin, y + rowH)
            val valRect = RectF(margin, y, pageW - margin - labelW, y + rowH)
            canvas.drawRect(labelRect, labelFill)
            canvas.drawRect(labelRect, borderPaint)
            canvas.drawRect(valRect, borderPaint)

            drawRtlTextInCell(canvas, label, labelTextPaint, labelRect)
            drawRtlTextInCell(canvas, value, valTextPaint, valRect)
            y += rowH
        }

        // 4. Notes row
        val notesH = 44f
        val notesRect = RectF(margin, y, pageW - margin, y + notesH)
        canvas.drawRect(notesRect, borderPaint)
        drawRtlTextInCell(canvas, "ملاحظات: ${person.notes.ifBlank { "لا توجد ملاحظات" }}", valTextPaint, notesRect)
        y += notesH + 14f

        // 5. ID Document image preview(s)
        if (documents.isNotEmpty() && y + 120f < pageH - 40f) {
            val docHeader = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F3D32")
                typeface = boldType
                textSize = 11f
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText("مرفقات الوثيقة (${person.idType}):", pageW - margin, y + 12f, docHeader)
            y += 20f

            val availH = (pageH - 42f - y).coerceAtMost(140f)
            val cardW = if (documents.size >= 2) (totalW - 12f) / 2f else totalW * 0.6f
            documents.take(2).forEachIndexed { idx, doc ->
                val f = File(doc.localFilePath)
                if (f.exists()) {
                    BitmapFactory.decodeFile(f.absolutePath)?.let { img ->
                        val leftX = if (documents.size >= 2) {
                            margin + idx * (cardW + 12f)
                        } else {
                            (pageW - cardW) / 2f
                        }
                        val dst = RectF(leftX, y, leftX + cardW, y + availH)
                        canvas.drawBitmap(img, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                        canvas.drawRect(dst, borderPaint)
                        img.recycle()
                    }
                }
            }
        }

        // Footer
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4F5D57")
            typeface = regType
            textSize = 9.5f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("سجل الفهد الميداني — وثيقة مستخرجة آلياً بتاريخ ${SecurityUtils.todayDate()}", pageW / 2f, pageH - 18f, footerPaint)

        pdf.finishPage(page)
        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val outFile = File(dir, "form_${person.citizenCode}.pdf")
        FileOutputStream(outFile).use { pdf.writeTo(it) }
        pdf.close()
        return outFile
    }

    private fun drawRtlTextInCell(
        canvas: Canvas,
        text: String,
        paint: TextPaint,
        rect: RectF
    ) {
        val innerW = (rect.width() - 10f).toInt().coerceAtLeast(20)
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, innerW)
            .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
            .setIncludePad(false)
            .build()
        canvas.save()
        val dy = rect.top + ((rect.height() - layout.height).coerceAtLeast(2f) / 2f)
        canvas.translate(rect.left + 5f, dy)
        layout.draw(canvas)
        canvas.restore()
    }
}
