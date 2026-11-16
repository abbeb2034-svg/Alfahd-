package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.UserRole
import com.example.export.CitizenImportField
import com.example.export.ExcelCitizensImporter
import com.example.export.ExcelImportResult
import com.example.export.ImportedWorkbook
import com.example.ui.components.DropdownSelectorField
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CitizensImportScreen(
    viewModel: MainViewModel,
    onTriggerCloudSync: () -> Unit = {},
    onDoneNavigateCitizens: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val neighborhoods by viewModel.neighborhoods.collectAsStateWithLifecycle()
    val squares by viewModel.squares.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val permissions by viewModel.currentPermissions.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    val canEditExisting = currentUser?.let { UserRole.fromDb(it.role) == UserRole.ADMIN } == true || permissions.canEditCitizen

    var workbook by remember { mutableStateOf<ImportedWorkbook?>(null) }
    var selectedSheetIndex by remember { mutableIntStateOf(0) }
    var headerRowIndex by remember { mutableIntStateOf(0) }
    var customColumnNames by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var colToFieldMapping by remember { mutableStateOf<Map<Int, CitizenImportField?>>(emptyMap()) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var isParsing by remember { mutableStateOf(false) }

    var defaultNeighborhoodId by remember(neighborhoods) {
        mutableStateOf(neighborhoods.firstOrNull()?.id ?: "")
    }
    val availableSquares = remember(squares, defaultNeighborhoodId) {
        squares.filter { it.neighborhoodId == defaultNeighborhoodId }
    }
    var defaultSquareId by remember(availableSquares) {
        mutableStateOf(availableSquares.firstOrNull()?.id ?: "")
    }
    var createMissingAreas by remember { mutableStateOf(true) }
    var skipDuplicateIds by remember { mutableStateOf(true) }
    var updateExistingOnDuplicate by remember { mutableStateOf(false) }
    var syncAfterImport by remember { mutableStateOf(true) }
    var importResult by remember { mutableStateOf<ExcelImportResult?>(null) }

    fun applyHeaderSelection(rows: List<List<String>>, rowIdx: Int) {
        val safeIdx = rowIdx.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        headerRowIndex = safeIdx
        val headerRow = rows.getOrElse(safeIdx) { emptyList() }
        customColumnNames = headerRow.mapIndexed { idx, txt -> idx to txt }.toMap()
        colToFieldMapping = ExcelCitizensImporter.suggestColumnToFieldMapping(headerRow)
    }

    val openExcelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isParsing = true
            parseError = null
            importResult = null
            try {
                val parsed = withContext(Dispatchers.IO) {
                    ExcelCitizensImporter.parseUri(context, uri)
                }
                workbook = parsed
                selectedSheetIndex = 0
                val firstRows = parsed.sheets.firstOrNull()?.rows.orEmpty()
                val suggestedIdx = ExcelCitizensImporter.suggestHeaderRowIndex(firstRows)
                applyHeaderSelection(firstRows, suggestedIdx)
            } catch (e: Exception) {
                workbook = null
                parseError = e.message ?: "تعذر قراءة ملف الإكسل"
            } finally {
                isParsing = false
            }
        }
    }

    val activeSheet = workbook?.sheets?.getOrNull(selectedSheetIndex)
    val sheetRows = activeSheet?.rows.orEmpty()
    val headerCells = sheetRows.getOrElse(headerRowIndex) { emptyList() }
    val firstDataRow = sheetRows.getOrNull(headerRowIndex + 1).orEmpty()
    val dataRowCount = (sheetRows.size - headerRowIndex - 1).coerceAtLeast(0)
    val fieldToColMapping = remember(colToFieldMapping) {
        ExcelCitizensImporter.toFieldToColumnMapping(colToFieldMapping)
    }
    val hasFullNameMapped = fieldToColMapping[CitizenImportField.FULL_NAME] != null

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Step 1: Pick Excel File
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.TableChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "1. اختيار ملف إكسل لبيانات المواطنين",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "يدعم ملفات Excel (.xlsx) وملفات CSV باللغة العربية. تتيح هذه الواجهة للمدير تحديد صف رؤوس الجدول ومطابقة كل عمود مع حقول قاعدة البيانات ثم رفع البيانات.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            openExcelLauncher.launch(
                                arrayOf(
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    "text/csv",
                                    "text/comma-separated-values",
                                    "application/csv",
                                    "application/vnd.ms-excel",
                                    "*/*"
                                )
                            )
                        },
                        enabled = !isParsing && !isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pick_excel_file_button")
                    ) {
                        if (isParsing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("جاري قراءة الملف...")
                        } else {
                            Icon(Icons.Filled.UploadFile, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (workbook == null) "اختيار ملف إكسل (.xlsx / .csv)" else "اختيار ملف آخر")
                        }
                    }

                    parseError?.let { err ->
                        Surface(
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = err,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().padding(10.dp)
                            )
                        }
                    }

                    workbook?.let { wb ->
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("الملف: ${wb.fileName}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    "الأوراق: ${wb.sheets.size} • إجمالي الصفوف: ${sheetRows.size} • الأعمدة المكتشفة: ${activeSheet?.maxColumns ?: 0}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }

        if (workbook != null && activeSheet != null) {
            // Step 2: Sheet & Header Row Selection
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "2. تحديد صف رؤوس الجدول (العناوين)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )

                        if (workbook!!.sheets.size > 1) {
                            DropdownSelectorField(
                                label = "ورقة العمل في الملف",
                                selectedText = activeSheet.name,
                                items = workbook!!.sheets.indices.toList(),
                                itemLabel = { idx -> workbook!!.sheets[idx].name },
                                onSelect = { idx ->
                                    selectedSheetIndex = idx
                                    val r = workbook!!.sheets[idx].rows
                                    applyHeaderSelection(r, ExcelCitizensImporter.suggestHeaderRowIndex(r))
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "صف رؤوس الجدول: الصف ${headerRowIndex + 1} (من ${sheetRows.size})",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilledTonalIconButton(
                                    onClick = { applyHeaderSelection(sheetRows, headerRowIndex - 1) },
                                    enabled = headerRowIndex > 0,
                                    modifier = Modifier.minimumInteractiveComponentSize().testTag("header_row_prev")
                                ) {
                                    Icon(Icons.Filled.Remove, contentDescription = "الصف السابق")
                                }
                                FilledTonalIconButton(
                                    onClick = { applyHeaderSelection(sheetRows, headerRowIndex + 1) },
                                    enabled = headerRowIndex < (sheetRows.size - 1),
                                    modifier = Modifier.minimumInteractiveComponentSize().testTag("header_row_next")
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = "الصف التالي")
                                }
                            }
                        }

                        val candidateRows = (0 until minOf(sheetRows.size, 15)).toList()
                        DropdownSelectorField(
                            label = "اختيار مباشر لصف رؤوس الجدول",
                            selectedText = "الصف ${headerRowIndex + 1}: ${
                                headerCells.filter { it.isNotBlank() }.take(3).joinToString(" | ").ifBlank { "(فارغ)" }
                            }",
                            items = candidateRows,
                            itemLabel = { idx ->
                                val preview = sheetRows[idx].filter { it.isNotBlank() }.take(3).joinToString(" | ").ifBlank { "(صف فارغ)" }
                                "الصف ${idx + 1}: $preview"
                            },
                            onSelect = { idx -> applyHeaderSelection(sheetRows, idx) }
                        )

                        Text(
                            text = "معاينة الجدول (صف الرؤوس المحدد + أول 3 صفوف بيانات):",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val previewColsCount = minOf(headerCells.size.coerceAtLeast(1), 8)
                        val previewEnd = minOf(sheetRows.size, headerRowIndex + 4)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            for (rIdx in headerRowIndex until previewEnd) {
                                val rowVals = sheetRows[rIdx]
                                val isHeader = rIdx == headerRowIndex
                                Row(
                                    modifier = Modifier
                                        .background(
                                            if (isHeader) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                        )
                                        .padding(vertical = 6.dp, horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = if (isHeader) "رأس (${rIdx + 1})" else "#${rIdx + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.width(56.dp)
                                    )
                                    for (cIdx in 0 until previewColsCount) {
                                        val colLetter = ExcelCitizensImporter.excelColumnLabel(cIdx)
                                        val cellVal = if (isHeader) {
                                            customColumnNames[cIdx] ?: rowVals.getOrElse(cIdx) { "" }
                                        } else {
                                            rowVals.getOrElse(cIdx) { "" }
                                        }
                                        Text(
                                            text = if (isHeader) "[$colLetter] ${cellVal.ifBlank { "-" }}" else cellVal.ifBlank { "-" },
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(min = 84.dp, max = 140.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Step 3: Interactive Manager Console for Defining Column Headers & Mapping to DB Fields
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "3. تحديد رؤوس الأعمدة ومطابقتها بحقول قاعدة البيانات",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    val editedHeaders = headerCells.indices.map { cIdx ->
                                        customColumnNames[cIdx] ?: headerCells[cIdx]
                                    }
                                    colToFieldMapping = ExcelCitizensImporter.suggestColumnToFieldMapping(editedHeaders)
                                }
                            ) {
                                Text("مطابقة تلقائية", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            AssistChip(
                                onClick = {},
                                label = { Text("أعمدة مطابقة: ${colToFieldMapping.values.count { it != null }}") }
                            )
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(if (hasFullNameMapped) "الاسم الكامل * (تم التحديد)" else "الاسم الكامل * (مطلوب)")
                                }
                            )
                        }

                        val fieldOptions: List<CitizenImportField?> = listOf(null) + CitizenImportField.entries
                        headerCells.indices.forEach { colIdx ->
                            val colLetter = ExcelCitizensImporter.excelColumnLabel(colIdx)
                            val headerLabel = customColumnNames[colIdx] ?: headerCells[colIdx]
                            val sampleValue = firstDataRow.getOrElse(colIdx) { "" }
                            val mappedField = colToFieldMapping[colIdx]

                            Surface(
                                color = if (mappedField != null) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                },
                                shape = MaterialTheme.shapes.small,
                                border = BorderStroke(
                                    1.dp,
                                    if (mappedField?.required == true) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "العمود [$colLetter]${if (sampleValue.isNotBlank()) " • مثال: $sampleValue" else ""}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    OutlinedTextField(
                                        value = headerLabel,
                                        onValueChange = { newText ->
                                            customColumnNames = customColumnNames.toMutableMap().apply { put(colIdx, newText) }
                                        },
                                        label = { Text("رأس العمود [$colLetter]") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("excel_header_name_$colIdx")
                                    )

                                    DropdownSelectorField(
                                        label = "حقل قاعدة البيانات المقابل",
                                        selectedText = mappedField?.arabicLabel ?: "تجاهل هذا العمود",
                                        items = fieldOptions,
                                        itemLabel = { f -> f?.arabicLabel ?: "تجاهل هذا العمود" },
                                        onSelect = { chosenField ->
                                            colToFieldMapping = colToFieldMapping.toMutableMap().apply {
                                                // Prevent duplicate DB field assignment by clearing any other column using the same DB field
                                                if (chosenField != null) {
                                                    entries.filter { it.key != colIdx && it.value == chosenField }
                                                        .map { it.key }
                                                        .forEach { prevCol -> put(prevCol, null) }
                                                }
                                                put(colIdx, chosenField)
                                            }
                                        },
                                        modifier = Modifier.testTag("map_excel_col_$colIdx")
                                    )
                                }
                            }
                        }

                        if (!hasFullNameMapped) {
                            Text(
                                text = "تنبيه: يجب ربط أحد الأعمدة بحقل (الاسم الكامل *) لإتمام عملية الاستيراد.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            // Step 4: Defaults, Duplicate Policy & Upload
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "4. خيارات الربط بقاعدة البيانات ورفع الملف",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )

                        DropdownSelectorField(
                            label = "الحارة الافتراضية (عند خلو عمود الحارة)",
                            selectedText = neighborhoods.firstOrNull { it.id == defaultNeighborhoodId }?.name ?: "اختر الحارة",
                            items = neighborhoods,
                            itemLabel = { it.name },
                            onSelect = { n ->
                                defaultNeighborhoodId = n.id
                                defaultSquareId = squares.firstOrNull { it.neighborhoodId == n.id }?.id ?: ""
                            }
                        )

                        DropdownSelectorField(
                            label = "المربع السكني الافتراضي (عند خلو عمود المربع)",
                            selectedText = availableSquares.firstOrNull { it.id == defaultSquareId }?.name ?: "تلقائي",
                            items = availableSquares,
                            itemLabel = { it.name },
                            onSelect = { sq -> defaultSquareId = sq.id }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("إنشاء الحارات والمربعات الجديدة تلقائياً", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = createMissingAreas, onCheckedChange = { createMissingAreas = it })
                        }

                        if (canEditExisting) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("تحديث بيانات المواطن إذا طابق رقم الهوية", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = updateExistingOnDuplicate,
                                    onCheckedChange = {
                                        updateExistingOnDuplicate = it
                                        if (it) skipDuplicateIds = false
                                    }
                                )
                            }
                        }

                        if (!updateExistingOnDuplicate) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("تخطي أصحاب رقم الهوية المسجل مسبقاً", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = skipDuplicateIds, onCheckedChange = { skipDuplicateIds = it })
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("مزامنة تلقائية مع السحابة (Firestore) بعد الحفظ", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = syncAfterImport, onCheckedChange = { syncAfterImport = it })
                        }

                        Button(
                            onClick = {
                                viewModel.importCitizensFromExcel(
                                    rows = sheetRows,
                                    headerRowIndex = headerRowIndex,
                                    mapping = fieldToColMapping,
                                    defaultNeighborhoodId = defaultNeighborhoodId,
                                    defaultSquareId = defaultSquareId,
                                    createMissingAreas = createMissingAreas,
                                    skipDuplicateIds = skipDuplicateIds,
                                    updateExistingOnDuplicateId = updateExistingOnDuplicate
                                ) { res ->
                                    importResult = res
                                    if (syncAfterImport && (res.importedCount > 0 || res.updatedCount > 0)) {
                                        onTriggerCloudSync()
                                    }
                                }
                            },
                            enabled = !isBusy && hasFullNameMapped && dataRowCount > 0,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("confirm_excel_import_button")
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(8.dp))
                                Text("جاري الاستيراد والربط...")
                            } else {
                                Icon(Icons.Filled.CloudUpload, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("استيراد وربط البيانات بقاعدة البيانات ($dataRowCount صف)")
                            }
                        }
                    }
                }
            }
        }

        // Result Card
        importResult?.let { res ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ملخص عملية استيراد ملف الإكسل", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("• تم استيراد وإضافة: ${res.importedCount} مواطن", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        if (res.updatedCount > 0) {
                            Text("• تم تحديث سجلات قائمة: ${res.updatedCount} مواطن", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        Text("• سجلات مكررة تم تخطيها: ${res.skippedDuplicates}", style = MaterialTheme.typography.bodySmall)
                        Text("• صفوف غير صالحة/فارغة: ${res.skippedInvalid}", style = MaterialTheme.typography.bodySmall)
                        if (res.createdAreasCount > 0) {
                            Text("• حارات/مربعات جديدة أُنشئت تلقائياً: ${res.createdAreasCount}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (res.sampleErrors.isNotEmpty()) {
                            HorizontalDivider()
                            Text("ملاحظات الصفوف المتخطاة:", style = MaterialTheme.typography.labelMedium)
                            res.sampleErrors.take(6).forEach { line ->
                                Text("- $line", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onTriggerCloudSync,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("مزامنة Firestore")
                            }
                            Button(
                                onClick = onDoneNavigateCitizens,
                                modifier = Modifier.weight(1f).testTag("after_import_view_citizens_button")
                            ) {
                                Text("عرض السجل")
                            }
                        }
                    }
                }
            }
        }
    }
}
