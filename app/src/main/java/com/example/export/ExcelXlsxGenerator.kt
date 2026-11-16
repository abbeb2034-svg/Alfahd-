package com.example.export

import android.content.Context
import android.graphics.Bitmap
import com.example.data.local.AppSettingsEntity
import com.example.util.SecurityUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure-Kotlin OOXML (.xlsx) generator supporting:
 * - RTL worksheet view (rightToLeft="1")
 * - Embedded Official Header PNG image in rows 1..4
 * - Print Titles (_xlnm.Print_Titles) repeating Official Header + Column Headers on EVERY printed page
 * - Print Area (_xlnm.Print_Area) & Page Setup (A4, Portrait or Landscape, fitToWidth=1)
 * - Full horizontal/vertical cell borders, shaded header row, row numbering, and auto-fitted widths
 * - Editable worksheet cells in Microsoft Excel / LibreOffice Calc / Google Sheets
 */
object ExcelXlsxGenerator {

    private const val SHEET_NAME = "الكشف"

    fun generateXlsx(
        context: Context,
        report: ReportTableData,
        settings: AppSettingsEntity?,
        landscape: Boolean = false
    ): File {
        val headerBmp = OfficialAssetsHelper.loadOrRenderOfficialHeaderBitmap(context, settings, 1400)
        val headerPngBytes = ByteArrayOutputStream().use { baos ->
            headerBmp.compress(Bitmap.CompressFormat.PNG, 95, baos)
            baos.toByteArray()
        }
        headerBmp.recycle()

        val allHeaders = listOf("#") + report.headers
        val numberedRows = report.rows.mapIndexed { idx, r ->
            listOf((idx + 1).toString()) + r
        }
        val totalCols = allHeaders.size.coerceAtLeast(2)
        val lastColLetter = colIndexToLetter(totalCols)
        val lastRowNum = (5 + numberedRows.size).coerceAtLeast(6)

        // Compute optimal column widths based on Arabic header and cell contents
        val colWidths = DoubleArray(totalCols) { cIdx ->
            var maxLen = allHeaders[cIdx].length + 4
            for (row in numberedRows) {
                val cellLen = row.getOrElse(cIdx) { "" }.length + 3
                if (cellLen > maxLen) maxLen = cellLen
            }
            if (cIdx == 0) 7.0 else maxLen.coerceIn(14, 38).toDouble()
        }

        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val outFile = File(dir, "report_${System.currentTimeMillis()}.xlsx")

        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            writeZipEntry(zip, "[Content_Types].xml", contentTypesXml())
            writeZipEntry(zip, "_rels/.rels", rootRelsXml())
            writeZipEntry(zip, "xl/workbook.xml", workbookXml(lastColLetter, lastRowNum))
            writeZipEntry(zip, "xl/_rels/workbook.xml.rels", workbookRelsXml())
            writeZipEntry(zip, "xl/styles.xml", stylesXml())
            writeZipEntry(zip, "xl/worksheets/sheet1.xml", sheetXml(report, allHeaders, numberedRows, colWidths, landscape))
            writeZipEntry(zip, "xl/worksheets/_rels/sheet1.xml.rels", sheetRelsXml())
            writeZipEntry(zip, "xl/drawings/drawing1.xml", drawingXml(totalCols))
            writeZipEntry(zip, "xl/drawings/_rels/drawing1.xml.rels", drawingRelsXml())
            writeZipBytes(zip, "xl/media/header.png", headerPngBytes)
        }

        return outFile
    }

    private fun writeZipEntry(zip: ZipOutputStream, path: String, content: String) {
        writeZipBytes(zip, path, content.toByteArray(Charsets.UTF_8))
    }

    private fun writeZipBytes(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun contentTypesXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Default Extension="png" ContentType="image/png"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
  <Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>
</Types>"""

    private fun rootRelsXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun workbookXml(lastColLetter: String, lastRowNum: Int) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="$SHEET_NAME" sheetId="1" r:id="rId1"/>
  </sheets>
  <definedNames>
    <definedName name="_xlnm.Print_Titles" localSheetId="0">'$SHEET_NAME'!${'$'}1:${'$'}5</definedName>
    <definedName name="_xlnm.Print_Area" localSheetId="0">'$SHEET_NAME'!${'$'}A${'$'}1:${'$'}$lastColLetter${'$'}$lastRowNum</definedName>
  </definedNames>
</workbook>"""

    private fun workbookRelsXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private fun stylesXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="3">
    <font><sz val="11"/><color rgb="FF161E1B"/><name val="Arial"/></font>
    <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Arial"/></font>
    <font><b/><sz val="12"/><color rgb="FF0F3D32"/><name val="Arial"/></font>
  </fonts>
  <fills count="4">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FF0F3D32"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFF3F6F4"/></patternFill></fill>
  </fills>
  <borders count="2">
    <border><left/><right/><top/><bottom/><diagonal/></border>
    <border>
      <left style="thin"><color rgb="FF2A3A34"/></left>
      <right style="thin"><color rgb="FF2A3A34"/></right>
      <top style="thin"><color rgb="FF2A3A34"/></top>
      <bottom style="thin"><color rgb="FF2A3A34"/></bottom>
      <diagonal/>
    </border>
  </borders>
  <cellStyleXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
  </cellStyleXfs>
  <cellXfs count="5">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <!-- 1: Header Cell (Emerald bg, white bold, full border, centered) -->
    <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">
      <alignment horizontal="center" vertical="center" wrapText="1" readingOrder="2"/>
    </xf>
    <!-- 2: Data Cell Even (White bg, RTL, full border) -->
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1">
      <alignment horizontal="right" vertical="center" wrapText="1" readingOrder="2"/>
    </xf>
    <!-- 3: Data Cell Odd Zebra (Soft tint bg, RTL, full border) -->
    <xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">
      <alignment horizontal="right" vertical="center" wrapText="1" readingOrder="2"/>
    </xf>
    <!-- 4: Report Title Row -->
    <xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1">
      <alignment horizontal="center" vertical="center" readingOrder="2"/>
    </xf>
  </cellXfs>
</styleSheet>"""

    private fun sheetXml(
        report: ReportTableData,
        headers: List<String>,
        rows: List<List<String>>,
        colWidths: DoubleArray,
        landscape: Boolean
    ): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        sb.append("""<sheetPr><pageSetUpPr fitToPage="1"/></sheetPr>""")
        sb.append("""<sheetViews><sheetView tabSelected="1" rightToLeft="1" workbookViewId="0"/></sheetViews>""")
        sb.append("""<sheetFormatPr defaultRowHeight="22"/>""")
        sb.append("<cols>")
        colWidths.forEachIndexed { idx, w ->
            val colNum = idx + 1
            sb.append("""<col min="$colNum" max="$colNum" width="$w" customWidth="1"/>""")
        }
        sb.append("</cols>")
        sb.append("<sheetData>")

        // Rows 1..3 reserve space for embedded official header graphic
        sb.append("""<row r="1" ht="28" customHeight="1"/>""")
        sb.append("""<row r="2" ht="28" customHeight="1"/>""")
        sb.append("""<row r="3" ht="28" customHeight="1"/>""")

        // Row 4: Title & subtitle
        val titleText = escapeXml("${report.title} — ${report.subtitle} (${SecurityUtils.todayDate()})")
        sb.append("""<row r="4" ht="24" customHeight="1"><c r="A4" t="inlineStr" s="4"><is><t>$titleText</t></is></c></row>""")

        // Row 5: Column headers
        sb.append("""<row r="5" ht="28" customHeight="1">""")
        headers.forEachIndexed { cIdx, h ->
            val ref = "${colIndexToLetter(cIdx + 1)}5"
            sb.append("""<c r="$ref" t="inlineStr" s="1"><is><t>${escapeXml(h)}</t></is></c>""")
        }
        sb.append("</row>")

        // Rows 6+: Citizen / Aid / Audit Data
        rows.forEachIndexed { rIdx, row ->
            val rowNum = 6 + rIdx
            val styleId = if (rIdx % 2 == 1) 3 else 2
            sb.append("""<row r="$rowNum" ht="24" customHeight="1">""")
            headers.indices.forEach { cIdx ->
                val ref = "${colIndexToLetter(cIdx + 1)}$rowNum"
                val cellVal = row.getOrElse(cIdx) { "" }
                sb.append("""<c r="$ref" t="inlineStr" s="$styleId"><is><t>${escapeXml(cellVal)}</t></is></c>""")
            }
            sb.append("</row>")
        }

        sb.append("</sheetData>")
        val lastCol = colIndexToLetter(headers.size.coerceAtLeast(1))
        sb.append("""<mergeCells count="1"><mergeCell ref="A4:${lastCol}4"/></mergeCells>""")
        val orient = if (landscape) "landscape" else "portrait"
        sb.append("""<pageMargins left="0.3" right="0.3" top="0.4" bottom="0.4" header="0.2" footer="0.2"/>""")
        sb.append("""<pageSetup paperSize="9" orientation="$orient" fitToWidth="1" fitToHeight="0"/>""")
        sb.append("""<headerFooter><oddFooter>&amp;Cالصفحة &amp;P من &amp;N</oddFooter></headerFooter>""")
        sb.append("""<drawing r:id="rId1"/>""")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun sheetRelsXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing1.xml"/>
</Relationships>"""

    private fun drawingXml(totalCols: Int) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing"
           xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
           xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <xdr:twoCellAnchor>
    <xdr:from><xdr:col>0</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>0</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>
    <xdr:to><xdr:col>$totalCols</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>3</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>
    <xdr:pic>
      <xdr:nvPicPr>
        <xdr:cNvPr id="1" name="OfficialHeader"/>
        <xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr>
      </xdr:nvPicPr>
      <xdr:blipFill>
        <a:blip r:embed="rId1"/>
        <a:stretch><a:fillRect/></a:stretch>
      </xdr:blipFill>
      <xdr:spPr>
        <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
      </xdr:spPr>
    </xdr:pic>
    <xdr:clientData/>
  </xdr:twoCellAnchor>
</xdr:wsDr>"""

    private fun drawingRelsXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/header.png"/>
</Relationships>"""

    private fun colIndexToLetter(colNum: Int): String {
        var n = colNum
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append(('A' + rem))
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    private fun escapeXml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
