package com.example.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

enum class CitizenImportField(
    val key: String,
    val arabicLabel: String,
    val required: Boolean = false
) {
    FULL_NAME("fullName", "الاسم الكامل *", required = true),
    NATIONAL_ID("nationalId", "رقم الهوية / الوثيقة"),
    ID_TYPE("idType", "نوع الهوية"),
    ID_ISSUE_DATE("idIssueDate", "تاريخ إصدار الهوية"),
    CITIZEN_CODE("citizenCode", "رقم التعريف (الكود)"),
    PHONE("phone", "رقم الهاتف"),
    NEIGHBORHOOD("neighborhood", "الحارة"),
    SQUARE("square", "المربع السكني"),
    GOVERNORATE("governorate", "المحافظة"),
    DISTRICT("district", "المديرية"),
    OCCUPATION("occupation", "المهنة / العمل"),
    FAMILY_SIZE("familySize", "عدد أفراد الأسرة"),
    HOUSING_TYPE("housingType", "نوع السكن"),
    HOUSING_STATUS("housingStatus", "حالة السكن"),
    HEALTH_STATUS("healthStatus", "الحالة الصحية"),
    HEALTH_DETAILS("healthDetails", "تفاصيل الحالة الصحية"),
    DISPLACEMENT_DATE("displacementDate", "تاريخ النزوح"),
    LAST_AID_DATE("lastAidDate", "تاريخ آخر مساعدة"),
    NOTES("notes", "ملاحظات")
}

data class ImportedSheet(
    val name: String,
    val rows: List<List<String>>
) {
    val maxColumns: Int get() = rows.maxOfOrNull { it.size } ?: 0
}

data class ImportedWorkbook(
    val fileName: String,
    val sheets: List<ImportedSheet>
)

data class ExcelImportResult(
    val importedCount: Int,
    val skippedDuplicates: Int,
    val skippedInvalid: Int,
    val createdAreasCount: Int,
    val sampleErrors: List<String>,
    val updatedCount: Int = 0
)

object ExcelCitizensImporter {

    private const val MAX_FILE_BYTES = 25 * 1024 * 1024

    fun parseUri(context: Context, uri: Uri): ImportedWorkbook {
        val fileName = resolveFileName(context, uri)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("تعذر فتح الملف المختار")
        return input.use { parseStream(it, fileName) }
    }

    fun parseStream(inputStream: InputStream, fileName: String = "citizens.xlsx"): ImportedWorkbook {
        val bytes = readLimitedBytes(inputStream)
        if (bytes.isEmpty()) {
            throw IllegalArgumentException("الملف المختار فارغ")
        }

        // Reject legacy binary BIFF8 .xls
        if (bytes.size >= 4 &&
            bytes[0] == 0xD0.toByte() &&
            bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() &&
            bytes[3] == 0xE0.toByte()
        ) {
            throw IllegalArgumentException(
                "صيغة .xls الثنائية القديمة غير مدعومة، يرجى حفظ الملف من إكسل بصيغة .xlsx أو .csv ثم إعادة رفعه"
            )
        }

        val isZip = bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        val sheets = if (isZip) {
            parseXlsxBytes(bytes)
        } else {
            parseCsvBytes(bytes, fileName)
        }

        val nonEmptySheets = sheets.filter { sheet ->
            sheet.rows.any { row -> row.any { it.isNotBlank() } }
        }
        if (nonEmptySheets.isEmpty()) {
            throw IllegalArgumentException("لم يتم العثور على صفوف بيانات داخل الملف")
        }

        return ImportedWorkbook(fileName = fileName, sheets = nonEmptySheets)
    }

    fun excelColumnLabel(colIndex: Int): String {
        var num = colIndex + 1
        val sb = StringBuilder()
        while (num > 0) {
            val rem = (num - 1) % 26
            sb.append(('A'.code + rem).toChar())
            num = (num - 1) / 26
        }
        return sb.reverse().toString()
    }

    fun suggestHeaderRowIndex(rows: List<List<String>>): Int {
        if (rows.isEmpty()) return 0
        var bestIdx = rows.indexOfFirst { r -> r.any { it.isNotBlank() } }.coerceAtLeast(0)
        var bestScore = -1
        val limit = minOf(rows.size, 15)
        for (i in 0 until limit) {
            val row = rows[i]
            val nonBlankCount = row.count { it.isNotBlank() }
            if (nonBlankCount < 2) continue
            val mappedCount = suggestColumnMapping(row).values.count { it != null }
            val score = mappedCount * 10 + nonBlankCount
            if (mappedCount >= 1 && score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }
        return bestIdx
    }

    fun suggestColumnMapping(headerRow: List<String>): Map<CitizenImportField, Int?> {
        val result = mutableMapOf<CitizenImportField, Int?>()
        val usedCols = mutableSetOf<Int>()
        val normalizedHeaders = headerRow.map { normalizeText(it) }

        val matchers: List<Pair<CitizenImportField, List<String>>> = listOf(
            CitizenImportField.FULL_NAME to listOf(
                "الاسم الكامل", "اسم المواطن", "الاسم الرباعي", "الاسم الثلاثي", "رب الاسره", "الاسم",
                "full name", "fullname", "citizen name", "name"
            ),
            CitizenImportField.NATIONAL_ID to listOf(
                "رقم الهويه", "رقم البطاقه", "الرقم الوطني", "رقم الوثيقه", "الهويه",
                "national id", "id number", "nid"
            ),
            CitizenImportField.ID_TYPE to listOf(
                "نوع الهويه", "نوع الوثيقه", "نوع الاثبات", "id type", "document type"
            ),
            CitizenImportField.ID_ISSUE_DATE to listOf(
                "تاريخ اصدار الهويه", "تاريخ الاصدار", "اصدار الهويه", "issue date", "id issue date"
            ),
            CitizenImportField.CITIZEN_CODE to listOf(
                "رقم التعريف", "الكود", "رمز المواطن", "كود المواطن", "citizen code", "code"
            ),
            CitizenImportField.PHONE to listOf(
                "رقم الهاتف", "الهاتف", "الجوال", "الموبايل", "رقم التواصل", "رقم التلفون",
                "phone", "mobile", "tel"
            ),
            CitizenImportField.NEIGHBORHOOD to listOf(
                "الحاره", "اسم الحاره", "الحي", "المنطقه", "neighborhood", "neighbourhood"
            ),
            CitizenImportField.SQUARE to listOf(
                "المربع السكني", "المربع", "اسم المربع", "square", "block"
            ),
            CitizenImportField.GOVERNORATE to listOf(
                "المحافظه", "governorate", "province"
            ),
            CitizenImportField.DISTRICT to listOf(
                "المديريه", "العزله", "district"
            ),
            CitizenImportField.OCCUPATION to listOf(
                "المهنه", "العمل", "الوظيفه", "occupation", "job", "work"
            ),
            CitizenImportField.FAMILY_SIZE to listOf(
                "عدد افراد الاسره", "افراد الاسره", "حجم الاسره", "عدد الاسره", "الافراد",
                "family size", "members"
            ),
            CitizenImportField.HOUSING_TYPE to listOf(
                "نوع السكن", "طبيعه السكن", "السكن", "housing type"
            ),
            CitizenImportField.HOUSING_STATUS to listOf(
                "حاله السكن", "وضع السكن", "housing status"
            ),
            CitizenImportField.HEALTH_STATUS to listOf(
                "الحاله الصحيه", "الصحه", "الوضع الصحي", "health status", "health"
            ),
            CitizenImportField.HEALTH_DETAILS to listOf(
                "تفاصيل الحاله الصحيه", "تفاصيل المرض", "التشخيص", "health details"
            ),
            CitizenImportField.DISPLACEMENT_DATE to listOf(
                "تاريخ النزوح", "displacement date"
            ),
            CitizenImportField.LAST_AID_DATE to listOf(
                "تاريخ اخر مساعده", "اخر مساعده", "last aid", "aid date"
            ),
            CitizenImportField.NOTES to listOf(
                "ملاحظات", "ملاحظه", "notes", "remarks", "comment"
            )
        )

        // Pass 1: exact normalized matches
        for ((field, keywords) in matchers) {
            val colIdx = normalizedHeaders.indexOfFirst { h ->
                h.isNotBlank() && keywords.any { k -> h == normalizeText(k) }
            }
            if (colIdx >= 0 && colIdx !in usedCols) {
                result[field] = colIdx
                usedCols += colIdx
            }
        }

        // Pass 2: partial contains matches for unmapped fields
        for ((field, keywords) in matchers) {
            if (result[field] != null) continue
            val colIdx = normalizedHeaders.indices.firstOrNull { idx ->
                if (idx in usedCols) return@firstOrNull false
                val h = normalizedHeaders[idx]
                h.isNotBlank() && h != "#" && h != "م" && keywords.any { k ->
                    val nk = normalizeText(k)
                    nk.length >= 3 && h.contains(nk)
                }
            }
            result[field] = colIdx
            if (colIdx != null) usedCols += colIdx
        }

        return result
    }

    fun suggestColumnToFieldMapping(headerRow: List<String>): Map<Int, CitizenImportField?> {
        val fieldToCol = suggestColumnMapping(headerRow)
        val colToField = linkedMapOf<Int, CitizenImportField?>()
        for (colIdx in headerRow.indices) {
            colToField[colIdx] = fieldToCol.entries.firstOrNull { it.value == colIdx }?.key
        }
        return colToField
    }

    fun toFieldToColumnMapping(colToField: Map<Int, CitizenImportField?>): Map<CitizenImportField, Int?> {
        val map = mutableMapOf<CitizenImportField, Int?>()
        for ((colIdx, field) in colToField) {
            if (field != null && !map.containsKey(field)) {
                map[field] = colIdx
            }
        }
        return map
    }

    fun formatExcelDateIfSerial(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val serial = trimmed.toDoubleOrNull() ?: return trimmed
        if (serial < 10000.0 || serial > 90000.0) return trimmed
        val days = serial.toInt()
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(1899, java.util.Calendar.DECEMBER, 30)
            add(java.util.Calendar.DAY_OF_YEAR, days)
        }
        val y = cal.get(java.util.Calendar.YEAR)
        val m = cal.get(java.util.Calendar.MONTH) + 1
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        return "%04d-%02d-%02d".format(java.util.Locale.US, y, m, d)
    }

    fun normalizeText(raw: String): String {
        return raw.trim()
            .lowercase()
            .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseXlsxBytes(bytes: ByteArray): List<ImportedSheet> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val cleanName = entry.name.trimStart('/').lowercase()
                    entries[cleanName] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val sharedStrings = entries["xl/sharedstrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val rels = entries["xl/_rels/workbook.xml.rels"]?.let { parseWorkbookRels(it) } ?: emptyMap()
        val sheetDefs = entries["xl/workbook.xml"]?.let { parseWorkbookSheets(it, rels) }.orEmpty()

        val targets = if (sheetDefs.isNotEmpty()) {
            sheetDefs
        } else {
            entries.keys
                .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
                .sorted()
                .mapIndexed { idx, path -> "ورقة ${idx + 1}" to path }
        }

        return targets.mapNotNull { (sheetName, path) ->
            val xmlBytes = entries[path.lowercase()] ?: return@mapNotNull null
            val rows = parseWorksheetXml(xmlBytes, sharedStrings)
            ImportedSheet(name = sheetName, rows = rows)
        }
    }

    private fun parseSharedStrings(xmlBytes: ByteArray): List<String> {
        val list = mutableListOf<String>()
        val parser = createXmlParser(xmlBytes)
        var inSi = false
        var inText = false
        val sb = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfter(':')
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (tag) {
                        "si" -> {
                            inSi = true
                            sb.setLength(0)
                        }
                        "t" -> if (inSi) inText = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inSi && inText) {
                        sb.append(parser.text ?: "")
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (tag) {
                        "t" -> inText = false
                        "si" -> {
                            list += sb.toString()
                            inSi = false
                        }
                    }
                }
            }
            event = parser.next()
        }
        return list
    }

    private fun parseWorkbookRels(xmlBytes: ByteArray): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val parser = createXmlParser(xmlBytes)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name?.substringAfter(':') == "Relationship") {
                val id = attrValue(parser, "Id")
                val target = attrValue(parser, "Target")
                if (!id.isNullOrBlank() && !target.isNullOrBlank()) {
                    val cleanTarget = target.trimStart('/').removePrefix("../")
                    val normalizedPath = if (cleanTarget.startsWith("xl/")) {
                        cleanTarget
                    } else {
                        "xl/$cleanTarget"
                    }
                    map[id] = normalizedPath
                }
            }
            event = parser.next()
        }
        return map
    }

    private fun parseWorkbookSheets(xmlBytes: ByteArray, rels: Map<String, String>): List<Pair<String, String>> {
        val sheets = mutableListOf<Pair<String, String>>()
        val parser = createXmlParser(xmlBytes)
        var idx = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name?.substringAfter(':') == "sheet") {
                idx++
                val name = attrValue(parser, "name")?.ifBlank { "ورقة $idx" } ?: "ورقة $idx"
                val relId = attrValue(parser, "id")
                val target = relId?.let { rels[it] } ?: "xl/worksheets/sheet$idx.xml"
                sheets += name to target
            }
            event = parser.next()
        }
        return sheets
    }

    private fun parseWorksheetXml(xmlBytes: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val rowMap = linkedMapOf<Int, MutableMap<Int, String>>()
        var maxCol = 0
        var currentRowNum = 0
        var nextAutoCol = 0
        var cellCol = 0
        var cellType = ""
        var inCell = false
        var inValue = false
        var inInlineText = false
        val valueBuilder = StringBuilder()

        val parser = createXmlParser(xmlBytes)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfter(':')
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (tag) {
                        "row" -> {
                            val rAttr = attrValue(parser, "r")?.toIntOrNull()
                            currentRowNum = rAttr ?: (currentRowNum + 1)
                            nextAutoCol = 0
                            rowMap.getOrPut(currentRowNum) { mutableMapOf() }
                        }
                        "c" -> {
                            inCell = true
                            val ref = attrValue(parser, "r")
                            cellCol = if (!ref.isNullOrBlank()) colRefToIndex(ref) else nextAutoCol
                            nextAutoCol = cellCol + 1
                            if (cellCol + 1 > maxCol) maxCol = cellCol + 1
                            cellType = attrValue(parser, "t") ?: ""
                            valueBuilder.setLength(0)
                        }
                        "v" -> if (inCell) {
                            inValue = true
                            valueBuilder.setLength(0)
                        }
                        "t" -> if (inCell && cellType == "inlineStr") {
                            inInlineText = true
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inCell && (inValue || inInlineText)) {
                        valueBuilder.append(parser.text ?: "")
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (tag) {
                        "v" -> inValue = false
                        "t" -> inInlineText = false
                        "c" -> {
                            if (inCell) {
                                val raw = valueBuilder.toString()
                                val decoded = when (cellType) {
                                    "s" -> {
                                        val sIdx = raw.trim().toIntOrNull()
                                        if (sIdx != null && sIdx in sharedStrings.indices) sharedStrings[sIdx] else ""
                                    }
                                    "b" -> if (raw.trim() == "1") "نعم" else "لا"
                                    "inlineStr", "str", "e" -> raw
                                    else -> cleanNumericCell(raw)
                                }
                                if (decoded.isNotEmpty()) {
                                    rowMap.getOrPut(currentRowNum) { mutableMapOf() }[cellCol] = decoded.trim()
                                }
                                inCell = false
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        if (rowMap.isEmpty() || maxCol == 0) return emptyList()
        val ordered = rowMap.toSortedMap()
        val result = ordered.values.map { cols ->
            List(maxCol) { colIdx -> cols[colIdx] ?: "" }
        }
        // Trim trailing empty rows
        val lastNonEmpty = result.indexOfLast { r -> r.any { it.isNotBlank() } }
        return if (lastNonEmpty >= 0) result.take(lastNonEmpty + 1) else emptyList()
    }

    private fun cleanNumericCell(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.endsWith(".0") && trimmed.dropLast(2).all { it.isDigit() }) {
            return trimmed.dropLast(2)
        }
        if (trimmed.contains('e', ignoreCase = true)) {
            val plain = runCatching {
                java.math.BigDecimal(trimmed).stripTrailingZeros().toPlainString()
            }.getOrNull()
            if (!plain.isNullOrBlank()) return plain
        }
        return trimmed
    }

    private fun colRefToIndex(ref: String): Int {
        var col = 0
        for (ch in ref) {
            if (!ch.isLetter()) break
            col = col * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return (col - 1).coerceAtLeast(0)
    }

    private fun attrValue(parser: XmlPullParser, localName: String): String? {
        for (i in 0 until parser.attributeCount) {
            val attrName = parser.getAttributeName(i)
            if (attrName == localName || attrName.substringAfter(':') == localName) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun createXmlParser(bytes: ByteArray): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = false
        }
        return factory.newPullParser().apply {
            setInput(bytes.inputStream(), "UTF-8")
        }
    }

    private fun parseCsvBytes(bytes: ByteArray, fileName: String): List<ImportedSheet> {
        var text = bytes.toString(Charsets.UTF_8)
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1)
        }
        val lines = text.replace("\r\n", "\n").replace("\r", "\n")
        val firstLine = lines.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
        val delimiter = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','

        val parsedRows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < lines.length) {
            val ch = lines[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < lines.length && lines[i + 1] == '"') {
                        cell.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == delimiter && !inQuotes -> {
                    currentRow += cell.toString().trim()
                    cell.setLength(0)
                }
                ch == '\n' && !inQuotes -> {
                    currentRow += cell.toString().trim()
                    cell.setLength(0)
                    if (currentRow.any { it.isNotBlank() }) {
                        parsedRows += currentRow.toList()
                    }
                    currentRow.clear()
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow += cell.toString().trim()
            if (currentRow.any { it.isNotBlank() }) {
                parsedRows += currentRow.toList()
            }
        }

        val maxCols = parsedRows.maxOfOrNull { it.size } ?: 0
        val padded = parsedRows.map { r -> List(maxCols) { idx -> r.getOrElse(idx) { "" } } }
        return listOf(ImportedSheet(name = fileName.substringBeforeLast('.').ifBlank { "ورقة CSV" }, rows = padded))
    }

    private fun resolveFileName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx) ?: "citizens.xlsx"
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "citizens.xlsx"
    }

    private fun readLimitedBytes(input: InputStream): ByteArray {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            total += read
            if (total > MAX_FILE_BYTES) {
                throw IllegalArgumentException("حجم الملف يتجاوز الحد الأقصى المسموح (25 ميجابايت)")
            }
            baos.write(buf, 0, read)
        }
        return baos.toByteArray()
    }
}
