package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.*
import com.example.ui.components.DropdownSelectorField
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.SyncStatusBadge
import com.example.ui.viewmodel.MainViewModel
import com.example.util.SecurityUtils
import java.io.File

@Composable
fun CitizensListScreen(
    viewModel: MainViewModel,
    canAdd: Boolean,
    onAddCitizen: () -> Unit,
    onImportExcel: () -> Unit = {},
    onSelectCitizen: (String) -> Unit
) {
    val citizens by viewModel.pagedCitizens.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMorePages.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val neighborhoods by viewModel.neighborhoods.collectAsStateWithLifecycle()
    val squares by viewModel.squares.collectAsStateWithLifecycle()
    val totalCount by viewModel.metrics.collectAsStateWithLifecycle()

    val neighMap = remember(neighborhoods) { neighborhoods.associateBy { it.id } }
    val sqMap = remember(squares) { squares.associateBy { it.id } }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchFilters(query = it) },
                label = { Text("بحث سريع (الاسم، رقم الهوية، الهاتف، رقم التعريف)") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearchFilters(query = "") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "مسح")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("citizens_search_input")
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "المسجلون: ${totalCount.totalPeople} مواطن",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (canAdd) {
                    FilledTonalButton(
                        onClick = onImportExcel,
                        modifier = Modifier.testTag("import_excel_button")
                    ) {
                        Icon(Icons.Filled.UploadFile, contentDescription = "رفع ملف إكسل", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("استيراد إكسل")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            if (citizens.isEmpty()) {
                EmptyStateCard(
                    icon = Icons.Filled.PeopleOutline,
                    title = "لا توجد سجلات مواطنين مطابقة",
                    description = "اضغط على زر إضافة مواطن للبدء بتسجيل المواطنين في الحارات والمربعات"
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(citizens, key = { it.id }) { person ->
                        CitizenRowCard(
                            person = person,
                            neighborhoodName = neighMap[person.neighborhoodId]?.name ?: "",
                            squareName = sqMap[person.squareId]?.name ?: "",
                            onClick = { onSelectCitizen(person.id) }
                        )
                    }
                    if (hasMore) {
                        item {
                            OutlinedButton(
                                onClick = { viewModel.loadNextCitizensPage() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("load_more_citizens_button")
                            ) {
                                Text("تحميل الدفعة التالية (50 سجل)")
                            }
                        }
                    }
                }
            }
        }

        if (canAdd) {
            ExtendedFloatingActionButton(
                onClick = onAddCitizen,
                icon = { Icon(Icons.Filled.PersonAdd, contentDescription = "إضافة مواطن") },
                text = { Text("إضافة مواطن") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag("add_citizen_fab")
            )
        }
    }
}

@Composable
fun CitizenRowCard(
    person: PersonEntity,
    neighborhoodName: String,
    squareName: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("citizen_card_${person.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = person.fullName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                SyncStatusBadge(statusDb = person.syncStatus)
            }
            Text(
                text = "التعريف: ${person.citizenCode}  |  ${person.idType}: ${person.nationalId.ifBlank { "غير مسجل" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "$neighborhoodName — $squareName",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "الأسرة: ${person.familySize} • ${person.housingType}",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
fun CitizenDetailScreen(
    personId: String,
    viewModel: MainViewModel,
    isAdmin: Boolean,
    canEdit: Boolean,
    canDelete: Boolean,
    onEdit: (String) -> Unit,
    onOpenIdCard: (String) -> Unit,
    onOpenOfficialForm: (String) -> Unit,
    onDeleted: () -> Unit
) {
    val person by viewModel.repo.observePerson(personId).collectAsStateWithLifecycle(initialValue = null)
    val docs by viewModel.repo.observePersonDocuments(personId).collectAsStateWithLifecycle(initialValue = emptyList())
    val distributions by viewModel.repo.observePersonDistributions(personId).collectAsStateWithLifecycle(initialValue = emptyList())
    val neighborhoods by viewModel.neighborhoods.collectAsStateWithLifecycle()
    val squares by viewModel.squares.collectAsStateWithLifecycle()
    val programs by viewModel.aidPrograms.collectAsStateWithLifecycle()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val p = person
    if (p == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val nName = neighborhoods.firstOrNull { it.id == p.neighborhoodId }?.name ?: ""
    val sName = squares.firstOrNull { it.id == p.squareId }?.name ?: ""
    val programMap = programs.associateBy { it.id }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(p.fullName, style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "رقم التعريف: ${p.citizenCode}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        SyncStatusBadge(p.syncStatus)
                    }

                    HorizontalDivider()

                    // Quick export actions for ID Card & Official Form
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onOpenIdCard(p.id) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("open_id_card_button")
                        ) {
                            Icon(Icons.Filled.Badge, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("البطاقة التعريفية")
                        }
                        OutlinedButton(
                            onClick = { onOpenOfficialForm(p.id) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("open_citizen_form_button")
                        ) {
                            Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("الاستمارة الرسمية")
                        }
                    }

                    if (isAdmin || canEdit || canDelete) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isAdmin || canEdit) {
                                OutlinedButton(
                                    onClick = { onEdit(p.id) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("edit_citizen_button")
                                ) {
                                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("تعديل البيانات")
                                }
                            }
                            if (isAdmin || canDelete) {
                                OutlinedButton(
                                    onClick = { showDeleteConfirm = true },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("delete_citizen_button")
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("حذف السجل")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Personal & Housing Details
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("البيانات الشخصية والسكنية", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    DetailRow("نوع الهوية", p.idType)
                    DetailRow("رقم الهوية", p.nationalId.ifBlank { "غير مسجل" })
                    DetailRow("تاريخ الإصدار", p.idIssueDate.ifBlank { "غير محدد" })
                    DetailRow("رقم الهاتف", p.phone.ifBlank { "غير مسجل" })
                    DetailRow("المحافظة / المديرية", "${p.governorate} — ${p.district}")
                    DetailRow("الحارة والمربع", "$nName — $sName")
                    DetailRow("المهنة", p.occupation.ifBlank { "غير محدد" })
                    DetailRow("عدد أفراد الأسرة", "${p.familySize}")
                    DetailRow("نوع السكن والحالة", "${p.housingType} (${p.housingStatus})")
                    DetailRow("تاريخ النزوح", p.displacementDate.ifBlank { "لا يوجد" })
                    DetailRow("الحالة الصحية", p.healthStatus)
                    if (p.healthDetails.isNotBlank()) DetailRow("تفاصيل صحية", p.healthDetails)
                    DetailRow("تاريخ آخر مساعدة", p.lastAidDate.ifBlank { "لم يستلم" })
                    if (p.notes.isNotBlank()) DetailRow("ملاحظات", p.notes)
                    DetailRow("الموظف المدخل", "${p.createdByName} (${SecurityUtils.formatArabicDateTime(p.createdAt)})")
                }
            }
        }

        // Attached Documents
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("صور الوثائق المرفقة (${docs.size})", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    if (docs.isEmpty()) {
                        Text("لا توجد صور مرفقة لهذا السجل", style = MaterialTheme.typography.bodySmall)
                    } else {
                        docs.forEach { doc ->
                            val file = File(doc.localFilePath)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "${doc.documentType} — ${DocumentType.sideArabicLabel(doc.side)}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                if (file.exists()) {
                                    val bmp = remember(file.absolutePath) { BitmapFactory.decodeFile(file.absolutePath) }
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = doc.documentType,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(170.dp),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Aid History for Citizen
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("سجل المساعدات المستلمة (${distributions.size})", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    if (distributions.isEmpty()) {
                        Text("لم يحصل هذا المواطن على مساعدات مسجلة حتى الآن", style = MaterialTheme.typography.bodySmall)
                    } else {
                        distributions.forEach { d ->
                            val progName = programMap[d.programId]?.name ?: "برنامج مساعدة"
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(progName, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "الكمية: ${d.quantityDelivered} • الموظف: ${d.responsibleEmployeeName}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Text(
                                        SecurityUtils.formatArabicDateTime(d.receivedAt),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("تأكيد حذف السجل") },
            text = { Text("هل أنت متأكد من حذف سجل المواطن (${p.fullName})؟ سيتم تسجيل عملية الحذف في سجل العمليات.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteCitizen(p.id, onDeleted)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("نعم، حذف")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.58f)
        )
    }
}

@Composable
fun SearchFilterScreen(
    viewModel: MainViewModel,
    onSelectCitizen: (String) -> Unit
) {
    val neighborhoods by viewModel.neighborhoods.collectAsStateWithLifecycle()
    val squares by viewModel.squares.collectAsStateWithLifecycle()
    val results by viewModel.pagedCitizens.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedNeighId by viewModel.filterNeighborhoodId.collectAsStateWithLifecycle()
    val selectedSqId by viewModel.filterSquareId.collectAsStateWithLifecycle()
    val selectedHousing by viewModel.filterHousingType.collectAsStateWithLifecycle()
    val selectedHealth by viewModel.filterHealthStatus.collectAsStateWithLifecycle()

    val filteredSquares = remember(squares, selectedNeighId) {
        if (selectedNeighId.isEmpty()) squares else squares.filter { it.neighborhoodId == selectedNeighId }
    }
    val neighMap = remember(neighborhoods) { neighborhoods.associateBy { it.id } }
    val sqMap = remember(squares) { squares.associateBy { it.id } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("البحث والتصفية المتقدمة", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { viewModel.updateSearchFilters(query = it) },
                        label = { Text("الاسم أو رقم الهوية أو الهاتف") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownSelectorField(
                        label = "الحارة",
                        selectedText = neighMap[selectedNeighId]?.name ?: "جميع الحارات",
                        items = listOf<NeighborhoodEntity?>(null) + neighborhoods,
                        itemLabel = { it?.name ?: "جميع الحارات" },
                        onSelect = { viewModel.updateSearchFilters(neighborhoodId = it?.id ?: "", squareId = "") }
                    )
                    DropdownSelectorField(
                        label = "المربع السكني",
                        selectedText = sqMap[selectedSqId]?.name ?: "جميع المربعات",
                        items = listOf<ResidentialSquareEntity?>(null) + filteredSquares,
                        itemLabel = { it?.name ?: "جميع المربعات" },
                        onSelect = { viewModel.updateSearchFilters(squareId = it?.id ?: "") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DropdownSelectorField(
                            label = "نوع السكن",
                            selectedText = selectedHousing.ifBlank { "الكل" },
                            items = listOf("", "مالك", "مستأجر", "أخرى"),
                            itemLabel = { it.ifBlank { "الكل" } },
                            onSelect = { viewModel.updateSearchFilters(housingType = it) },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = selectedHealth,
                            onValueChange = { viewModel.updateSearchFilters(healthStatus = it) },
                            label = { Text("الحالة الصحية") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    TextButton(
                        onClick = { viewModel.updateSearchFilters("", "", "", "", "") },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("إعادة ضبط التصفية")
                    }
                }
            }
        }

        item {
            Text("النتائج المطابقة (${results.size})", style = MaterialTheme.typography.titleSmall)
        }

        items(results, key = { it.id }) { person ->
            CitizenRowCard(
                person = person,
                neighborhoodName = neighMap[person.neighborhoodId]?.name ?: "",
                squareName = sqMap[person.squareId]?.name ?: "",
                onClick = { onSelectCitizen(person.id) }
            )
        }
    }
}
