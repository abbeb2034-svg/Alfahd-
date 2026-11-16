package com.example.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.data.local.AppSettingsEntity
import com.example.util.SecurityUtils
import java.io.File
import java.io.FileOutputStream

data class ReportTableData(
    val title: String,
    val subtitle: String,
    val headers: List<String>, // Without "#" - generator adds "#" automatically as first RTL column
    val rows: List<List<String>>
)

data class MeasuredReportPage(
    val pageNumber: Int,
    val rows: List<Pair<List<String>, Float>>
)

object MultiPageReportPdfGenerator {

    fun computePageChunks(
        context: Context,
        report: ReportTableData,
        settings: AppSettingsEntity?,
        landscape: Boolean = false
    ): List<MeasuredReportPage> {
        val pageW = if (landscape) 842 else 595
        val pageH = if (landscape) 595 else 842
        val margin = 24f
        val contentW = pageW - margin * 2
        val regType = OfficialAssetsHelper.loadArabicTypeface(context, bold = false)
        val headerBmp = OfficialAssetsHelper.loadOrRenderOfficialHeaderBitmap(context, settings, 1500)
        val headerAspect = headerBmp.height.toFloat() / headerBmp.width.toFloat().coerceAtLeast(1f)
        val headerDrawH = (contentW * headerAspect).coerceAtMost(if (landscape) 78f else 92f)
        headerBmp.recycle()

        val tableTopAfterHeader = margin + headerDrawH + 34f
        val colHeaderH = 26f
        val footerReserve = 30f
        val bodyStartY = tableTopAfterHeader + colHeaderH
        val maxBodyY = pageH - footerReserve

        val allHeaders = listOf("#") + report.headers
        val colCount = allHeaders.size
        val indexColW = 28f
        val remainingW = (contentW - indexColW).coerceAtLeast(100f)
        val dataColW = remainingW / (colCount - 1).coerceAtLeast(1)
        val colWidths = listOf(indexColW) + List(colCount - 1) { dataColW }

        val cellTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#161E1B")
            typeface = regType
            textSize = if (colCount > 8) 8.2f else 9.2f
        }

        val numberedRows = report.rows.mapIndexed { idx, row ->
            listOf((idx + 1).toString()) + row
        }

        val pages = mutableListOf<List<Pair<List<String>, Float>>>()
        if (numberedRows.isEmpty()) {
            pages.add(emptyList())
        } else {
            var currentPageRows = mutableListOf<Pair<List<String>, Float>>()
            var currentY = bodyStartY
            for (row in numberedRows) {
                val rowH = measureRowHeight(row, colWidths, cellTextPaint)
                if (currentPageRows.isNotEmpty() && currentY + rowH > maxBodyY) {
                    pages.add(currentPageRows)
                    currentPageRows = mutableListOf()
                    currentY = bodyStartY
                }
                currentPageRows.add(row to rowH)
                currentY += rowH
            }
            if (currentPageRows.isNotEmpty()) {
                pages.add(currentPageRows)
            }
        }
        return pages.mapIndexed { idx, list -> MeasuredReportPage(idx + 1, list) }
    }

    fun generateReportPdf(
        context: Context,
        report: ReportTableData,
        settings: AppSettingsEntity?,
        landscape: Boolean = false
    ): File {
        val pageW = if (landscape) 842 else 595
        val pageH = if (landscape) 595 else 842
        val margin = 24f
        val contentW = pageW - margin * 2

        val boldType = OfficialAssetsHelper.loadArabicTypeface(context, bold = true)
        val regType = OfficialAssetsHelper.loadArabicTypeface(context, bold = false)

        val headerBmp = OfficialAssetsHelper.loadOrRenderOfficialHeaderBitmap(context, settings, 1500)
        val headerAspect = headerBmp.height.toFloat() / headerBmp.width.toFloat().coerceAtLeast(1f)
        val headerDrawH = (contentW * headerAspect).coerceAtMost(if (landscape) 78f else 92f)

        val tableTopAfterHeader = margin + headerDrawH + 34f
        val colHeaderH = 26f
        val bodyStartY = tableTopAfterHeader + colHeaderH

        // Build RTL columns: rightmost is "#", followed by user-selected headers right-to-left
        val allHeaders = listOf("#") + report.headers
        val colCount = allHeaders.size
        val indexColW = 28f
        val remainingW = (contentW - indexColW).coerceAtLeast(100f)
        val dataColW = remainingW / (colCount - 1).coerceAtLeast(1)
        val colWidths = listOf(indexColW) + List(colCount - 1) { dataColW }

        val cellTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#161E1B")
            typeface = regType
            textSize = if (colCount > 8) 8.2f else 9.2f
        }
        val headerTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = boldType
            textSize = if (colCount > 8) 8.5f else 9.5f
        }

        val measuredPages = computePageChunks(context, report, settings, landscape)
        val pagesOfRows = measuredPages.map { it.rows }

        val totalPages = pagesOfRows.size
        val pdf = PdfDocument()

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2A3A34")
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
        }
        val headerFillPaint = Paint().apply {
            color = Color.parseColor("#0F3D32")
            style = Paint.Style.FILL
        }
        val zebraFillPaint = Paint().apply {
            color = Color.parseColor("#F3F6F4")
            style = Paint.Style.FILL
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F3D32")
            typeface = boldType
            textSize = 12.5f
            textAlign = Paint.Align.RIGHT
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4F5D57")
            typeface = regType
            textSize = 9.5f
            textAlign = Paint.Align.LEFT
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4F5D57")
            typeface = regType
            textSize = 9.5f
            textAlign = Paint.Align.CENTER
        }

        pagesOfRows.forEachIndexed { pageIdx, pageRows ->
            val pageNumber = pageIdx + 1
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNumber).create())
            val canvas = page.canvas

            // 1. Official Header repeated on EVERY page
            canvas.drawBitmap(
                headerBmp,
                null,
                RectF(margin, margin, margin + contentW, margin + headerDrawH),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )

            // 2. Report Title & Date Bar on EVERY page
            val titleY = margin + headerDrawH + 18f
            canvas.drawText(report.title, pageW - margin, titleY, titlePaint)
            canvas.drawText(
                "${report.subtitle} | ${SecurityUtils.todayDate()}",
                margin,
                titleY,
                subPaint
            )

            // 3. Column Headers repeated on EVERY page (RTL right-to-left)
            drawTableRow(
                canvas = canvas,
                cells = allHeaders,
                colWidths = colWidths,
                rightEdge = pageW - margin,
                topY = tableTopAfterHeader,
                rowH = colHeaderH,
                textPaint = headerTextPaint,
                fillPaint = headerFillPaint,
                borderPaint = borderPaint,
                centered = true
            )

            // 4. Data Rows
            var y = bodyStartY
            if (pageRows.isEmpty()) {
                val emptyRect = RectF(margin, y, pageW - margin, y + 32f)
                canvas.drawRect(emptyRect, borderPaint)
                canvas.drawText("لا توجد سجلات مطابقة لهذا الكشف", pageW / 2f, y + 20f, footerPaint)
            } else {
                pageRows.forEachIndexed { rIdx, (rowCells, rowH) ->
                    drawTableRow(
                        canvas = canvas,
                        cells = rowCells,
                        colWidths = colWidths,
                        rightEdge = pageW - margin,
                        topY = y,
                        rowH = rowH,
                        textPaint = cellTextPaint,
                        fillPaint = if (rIdx % 2 == 1) zebraFillPaint else null,
                        borderPaint = borderPaint,
                        centered = false
                    )
                    y += rowH
                }
            }

            // 5. Page X of Y Footer
            canvas.drawText(
                "الصفحة $pageNumber من $totalPages",
                pageW / 2f,
                pageH - 12f,
                footerPaint
            )

            pdf.finishPage(page)
        }

        headerBmp.recycle()
        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val safeName = "report_${System.currentTimeMillis()}.pdf"
        val outFile = File(dir, safeName)
        FileOutputStream(outFile).use { pdf.writeTo(it) }
        pdf.close()
        return outFile
    }

    private fun measureRowHeight(
        cells: List<String>,
        colWidths: List<Float>,
        paint: TextPaint
    ): Float {
        var maxLinesH = 18f
        cells.forEachIndexed { idx, value ->
            val w = (colWidths.getOrElse(idx) { 60f } - 8f).toInt().coerceAtLeast(16)
            val text = value.ifBlank { "-" }
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, w)
                .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
                .setIncludePad(false)
                .build()
            if (layout.height + 8f > maxLinesH) {
                maxLinesH = layout.height + 8f
            }
        }
        return maxLinesH.coerceAtLeast(22f)
    }

    private fun drawTableRow(
        canvas: Canvas,
        cells: List<String>,
        colWidths: List<Float>,
        rightEdge: Float,
        topY: Float,
        rowH: Float,
        textPaint: TextPaint,
        fillPaint: Paint?,
        borderPaint: Paint,
        centered: Boolean
    ) {
        var curRight = rightEdge
        cells.forEachIndexed { idx, value ->
            val w = colWidths.getOrElse(idx) { 60f }
            val left = curRight - w
            val rect = RectF(left, topY, curRight, topY + rowH)
            if (fillPaint != null) {
                canvas.drawRect(rect, fillPaint)
            }
            canvas.drawRect(rect, borderPaint)

            val text = value.ifBlank { "-" }
            val innerW = (w - 6f).toInt().coerceAtLeast(14)
            val alignment = if (centered || idx == 0) {
                Layout.Alignment.ALIGN_CENTER
            } else {
                Layout.Alignment.ALIGN_OPPOSITE
            }
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, textPaint, innerW)
                .setAlignment(alignment)
                .setIncludePad(false)
                .build()
            canvas.save()
            val dy = topY + ((rowH - layout.height).coerceAtLeast(2f) / 2f)
            canvas.translate(left + 3f, dy)
            layout.draw(canvas)
            canvas.restore()

            curRight = left
        }
    }
}
