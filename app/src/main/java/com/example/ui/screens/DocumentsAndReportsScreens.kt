package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.*
import com.example.export.*
import com.example.ui.components.DropdownSelectorField
import com.example.ui.components.OfficialHeaderBanner
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun IdCardScreen(
    personId: String,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val person by viewModel.repo.observePerson(personId).collectAsStateWithLifecycle(initialValue = null)
    val neighborhoods by viewModel.neighborhoods.collectAsStateWithLifecycle()
    val squares by viewModel.squares.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()

    val p = person
    if (p == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val nName = neighborhoods.firstOrNull { it.id == p.neighborhoodId }?.name ?: ""
    val sName = squares.firstOrNull { it.id == p.squareId }?.name ?: ""
    val previewBmp = remember(p.id, p.updatedAt, settings.updatedAt) {
        CitizenCardGenerator.renderCardBitmap(context, p, nName, sName, settings)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("معاينة البطاقة التعريفية الرسمية", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Card(
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Image(
                bitmap = previewBmp.asImageBitmap(),
                contentDescription = "البطاقة التعريفية للمواطن ${p.fullName}",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1080f / 660f),
                contentScale = ContentScale.Fit
            )
        }
        Text(
            "ملاحظة أمنية: رمز QR يحتوي على المعرّف الداخلي للمواطن فقط (${p.id}).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = {
                scope.launch {
                    val file = withContext(Dispatchers.IO) {
                        CitizenCardGenerator.exportCardPdf(context, p, nName, sName, settings)
                    }
                    currentUser?.let { viewModel.repo.recordExportAudit(it, "PDF", "بطاقة تعريفية: ${p.fullName}") }
                    OfficialAssetsHelper.shareFile(context, file, "application/pdf", "مشاركة البطاقة PDF")
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("export_card_pdf_button")
        ) {
            Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("تصدير ومشاركة البطاقة (PDF)")
        }

        OutlinedButton(
            onClick = {
                scope.launch {
                    val file = withContext(Dispatchers.IO) {
                        CitizenCardGenerator.exportCardPng(context, p, nName, sName, settings)
                    }
                    currentUser?.let { viewModel.repo.recordExportAudit(it, "PNG", "صورة بطاقة: ${p.fullName}") }
                    OfficialAssetsHelper.shareFile(context, file, "image/png", "مشاركة صورة البطاقة عالية الجودة")
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("export_card_png_button")
        ) {
            Icon(Icons.Filled.Image, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("تصدير صورة عالية الجودة (PNG)")
        }

        OutlinedButton(
            onClick = {
                scope.launch {
                    val file = withContext(Dispatchers.IO) {
                        CitizenCardGenerator.exportCardPdf(context, p, nName, sName, settings)
                    }
                    OfficialAssetsHelper.printPdfFile(context, file, "طباعة بطاقة ${p.fullName}")
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("print_card_button")
        ) {
            Icon(Icons.Filled.Print, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("طباعة مباشرة")
        }
    }
}

@Composable
fun CitizenFormExportScreen(
    personId: String,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val person by viewModel.repo.observePerson(personId).collectAsStateWithLifecycle(initialValue = null)
    val docs by viewModel.repo.observePersonDocuments(personId).collectAsStateWithLifecycle(initialValue = emptyList())
    val neighborhoods by viewModel.neighborhoods.collectAsStateWithLifecycle()
    val squares by viewModel.squares.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()

    val p = person
    if (p == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val nName = neighborhoods.firstOrNull { it.id == p.neighborhoodId }?.name ?: ""
    val sName = squares.firstOrNull { it.id == p.squareId }?.name ?: ""

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OfficialHeaderBanner(settings = settings)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("استمارة المواطن الرسمية (صفحة واحدة A4)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                HorizontalDivider()
                Text("الاسم: ${p.fullName}", fontWeight = FontWeight.Bold)
                Text("التعريف: ${p.citizenCode} | الهوية: ${p.idType} (${p.nationalId})")
                Text("الهاتف: ${p.phone} | الأسرة: ${p.familySize} أفراد")
                Text("السكن: ${p.housingType} (${p.housingStatus}) | المهنة: ${p.occupation}")
                Text("الموقع: $nName — $sName")
                Text("الحالة الصحية: ${p.healthStatus} ${p.healthDetails}")
                Text("تاريخ النزوح: ${p.displacementDate.ifBlank { "لا يوجد" }}")
                Text("المرفقات المرتبطة: ${docs.size} صورة")
            }
        }

        Button(
            onClick = {
                scope.launch {
                    val file = withContext(Dispatchers.IO) {
                        CitizenFormPdfGenerator.generateSinglePageFormPdf(context, p, nName, sName, docs, settings)
                    }
                    currentUser?.let { viewModel.repo.recordExportAudit(it, "PDF", "استمارة مواطن: ${p.fullName}") }
                    OfficialAssetsHelper.shareFile(context, file, "application/pdf", "مشاركة استمارة المواطن PDF")
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("export_citizen_form_pdf_button")
        ) {
            Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("تصدير استمارة المواطن (PDF)")
        }

        OutlinedButton(
            onClick = {
                scope.launch {
                    val file = withContext(Dispatchers.IO) {
                        CitizenFormPdfGenerator.generateSinglePageFormPdf(context, p, nName, sName, docs, settings)
                    }
                    OfficialAssetsHelper.printPdfFile(context, file, "طباعة استمارة ${p.fullName}")
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("print_citizen_form_button")
        ) {
            Icon(Icons.Filled.Print, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("طباعة الاستمارة")
        }
    }
}

@Composable
fun ReportsScreen(
    viewModel: MainViewModel,
    canExport: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val neighborhoods by viewModel.neighborhoods.collectAsStateWithLifecycle()
    val squares by viewModel.squares.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    var selectedReportType by remember { mutableStateOf(ReportType.ALL_CITIZENS) }
    var filterNeighId by remember { mutableStateOf("") }
    var filterSqId by remember { mutableStateOf("") }
    var filterText by remember { mutableStateOf("") }
    var minFamilySize by remember { mutableStateOf("1") }
    var landscape by remember { mutableStateOf(settings.defaultOrientation == "landscape") }

    val selectedCols = remember {
        mutableStateMapOf<ReportColumn, Boolean>().apply {
            ReportColumn.entries.forEach { put(it, it.defaultSelected) }
        }
    }

    var previewData by remember { mutableStateOf<ReportTableData?>(null) }

    LaunchedEffect(
        selectedReportType,
        filterNeighId,
        filterSqId,
        filterText,
        minFamilySize,
        selectedCols.toMap()
    ) {
        val cols = ReportColumn.entries.filter { selectedCols[it] == true }.ifEmpty {
            listOf(ReportColumn.CITIZEN_CODE, ReportColumn.FULL_NAME)
        }
        previewData = viewModel.buildReportData(
            reportType = selectedReportType,
            selectedColumns = cols,
            filterNeighborhoodId = filterNeighId,
            filterSquareId = filterSqId,
            filterText = filterText,
            minFamilySize = minFamilySize.toIntOrNull() ?: 1
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("إعداد الكشف والتقرير", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    DropdownSelectorField(
                        label = "نوع الكشف / التقرير (13 نوعاً)",
                        selectedText = selectedReportType.arabicLabel,
                        items = ReportType.entries,
                        itemLabel = { it.arabicLabel },
                        onSelect = { selectedReportType = it },
                        testTag = "report_type_dropdown"
                    )

                    if (selectedReportType == ReportType.BY_NEIGHBORHOOD || selectedReportType == ReportType.BY_SQUARE) {
                        DropdownSelectorField(
                            label = "اختر الحارة",
                            selectedText = neighborhoods.firstOrNull { it.id == filterNeighId }?.name ?: "كل الحارات",
                            items = listOf<NeighborhoodEntity?>(null) + neighborhoods,
                            itemLabel = { it?.name ?: "كل الحارات" },
                            onSelect = {
                                filterNeighId = it?.id ?: ""
                                filterSqId = ""
                            }
                        )
                    }
                    if (selectedReportType == ReportType.BY_SQUARE) {
                        val sqList = if (filterNeighId.isEmpty()) squares else squares.filter { it.neighborhoodId == filterNeighId }
                        DropdownSelectorField(
                            label = "اختر المربع السكني",
                            selectedText = squares.firstOrNull { it.id == filterSqId }?.name ?: "كل المربعات",
                            items = listOf<ResidentialSquareEntity?>(null) + sqList,
                            itemLabel = { it?.name ?: "كل المربعات" },
                            onSelect = { filterSqId = it?.id ?: "" }
                        )
                    }
                    if (selectedReportType in listOf(
                            ReportType.BY_REPRESENTATIVE,
                            ReportType.BY_AQIL,
                            ReportType.BY_HEALTH_STATUS,
                            ReportType.BY_HOUSING_TYPE,
                            ReportType.ENTRIES_BY_EMPLOYEE
                        )
                    ) {
                        OutlinedTextField(
                            value = filterText,
                            onValueChange = { filterText = it },
                            label = { Text("تصفية (الاسم / الحالة / نوع السكن)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (selectedReportType == ReportType.BY_FAMILY_SIZE) {
                        OutlinedTextField(
                            value = minFamilySize,
                            onValueChange = { minFamilySize = it.filter { c -> c.isDigit() } },
                            label = { Text("الحد الأدنى لأفراد الأسرة") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Orientation toggle (Portrait / Landscape)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("اتجاه الصفحة (A4):", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !landscape,
                                onClick = { landscape = false },
                                label = { Text("عمودي") }
                            )
                            FilterChip(
                                selected = landscape,
                                onClick = { landscape = true },
                                label = { Text("أفقي") }
                            )
                        }
                    }

                    // Column selection chips
                    Text("اختيار الأعمدة المطلوبة قبل التصدير:", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ReportColumn.entries.forEach { col ->
                            val checked = selectedCols[col] == true
                            FilterChip(
                                selected = checked,
                                onClick = { selectedCols[col] = !checked },
                                label = { Text(col.arabicLabel) }
                            )
                        }
                    }

                    if (canExport && previewData != null) {
                        val currentReport = previewData!!
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    viewModel.exportReportPdf(currentReport, landscape) { file ->
                                        OfficialAssetsHelper.shareFile(context, file, "application/pdf", "مشاركة الكشف PDF")
                                    }
                                },
                                enabled = !isBusy,
                                modifier = Modifier.weight(1f).testTag("export_report_pdf_button")
                            ) {
                                Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("تصدير PDF")
                            }
                            Button(
                                onClick = {
                                    viewModel.exportReportExcel(currentReport, landscape) { file ->
                                        OfficialAssetsHelper.shareFile(
                                            context,
                                            file,
                                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                            "مشاركة ملف Excel"
                                        )
                                    }
                                },
                                enabled = !isBusy,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                modifier = Modifier.weight(1f).testTag("export_report_excel_button")
                            ) {
                                Icon(Icons.Filled.TableChart, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("تصدير Excel")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.exportReportPdf(currentReport, landscape) { file ->
                                    OfficialAssetsHelper.printPdfFile(context, file, currentReport.title)
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("طباعة الكشف (بترويسة متكررة في كل صفحة)")
                        }
                    }
                }
            }
        }

        // Live table preview
        previewData?.let { data ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("معاينة الكشف: ${data.title} (${data.rows.size} صف)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "ملاحظة التصدير: تتكرر الترويسة الرسمية وعناوين الأعمدة في كل صفحة في PDF وExcel تلقائياً.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider()
                        data.rows.take(25).forEachIndexed { idx, row ->
                            Text(
                                "${idx + 1}. ${row.joinToString(" | ")}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        }
                        if (data.rows.size > 25) {
                            Text("... والمزيد (${data.rows.size - 25} صف إضافي سيظهر كاملاً عند التصدير)", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
