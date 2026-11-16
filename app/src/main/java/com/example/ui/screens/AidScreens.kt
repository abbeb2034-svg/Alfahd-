package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.*
import com.example.ui.components.EmptyStateCard
import com.example.ui.viewmodel.MainViewModel
import com.example.util.SecurityUtils

@Composable
fun AidProgramsScreen(
    viewModel: MainViewModel,
    canManageAid: Boolean,
    onOpenProgram: (String) -> Unit
) {
    val programs by viewModel.aidPrograms.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 84.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (programs.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Filled.VolunteerActivism,
                        title = "لا توجد برامج مساعدات حالياً",
                        description = "أضف برنامج مساعدة جديداً لتحديد المستفيدين وتوزيع الإعانات"
                    )
                }
            }
            items(programs, key = { it.id }) { prog ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenProgram(prog.id) }
                        .testTag("aid_program_card_${prog.id}"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(prog.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            AssistChip(onClick = {}, label = { Text(prog.aidType) })
                        }
                        Text("الجهة المانحة: ${prog.donorName.ifBlank { "غير محدد" }}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "تاريخ التوزيع: ${prog.distributionDate} • الوحدة: ${prog.unitLabel} (${prog.defaultQuantity})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        if (canManageAid) {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = "إضافة مساعدة") },
                text = { Text("برنامج مساعدة جديد") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag("add_aid_program_fab")
            )
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        var aidType by remember { mutableStateOf("سلة غذائية") }
        var donor by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(SecurityUtils.todayDate()) }
        var unit by remember { mutableStateOf("سلة") }
        var qty by remember { mutableStateOf("1") }
        var amount by remember { mutableStateOf("0") }
        var notes by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("إضافة برنامج مساعدة وإعانة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم المساعدة *") }, singleLine = true)
                    OutlinedTextField(value = aidType, onValueChange = { aidType = it }, label = { Text("النوع (غذائية، نقدية، صحية...)") }, singleLine = true)
                    OutlinedTextField(value = donor, onValueChange = { donor = it }, label = { Text("الجهة المانحة") }, singleLine = true)
                    OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("تاريخ التوزيع") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("الوحدة") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = qty, onValueChange = { qty = it }, label = { Text("الكمية") }, singleLine = true, modifier = Modifier.weight(0.7f))
                        OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("المبلغ") }, singleLine = true, modifier = Modifier.weight(0.8f))
                    }
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("ملاحظات") })
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveAidProgram(
                            AidProgramEntity(
                                id = SecurityUtils.newUuid(),
                                name = name,
                                aidType = aidType,
                                donorName = donor,
                                distributionDate = date,
                                unitLabel = unit,
                                defaultQuantity = qty.toDoubleOrNull() ?: 1.0,
                                defaultAmount = amount.toDoubleOrNull() ?: 0.0,
                                notes = notes
                            )
                        ) { showCreateDialog = false }
                    },
                    enabled = name.isNotBlank()
                ) { Text("حفظ البرنامج") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun AidDetailScreen(
    programId: String,
    viewModel: MainViewModel,
    canManageAid: Boolean
) {
    val programs by viewModel.aidPrograms.collectAsStateWithLifecycle()
    val program = programs.firstOrNull { it.id == programId }
    val beneficiaries by viewModel.repo.observeProgramBeneficiaries(programId).collectAsStateWithLifecycle(initialValue = emptyList())
    val distributions by viewModel.repo.observeProgramDistributions(programId).collectAsStateWithLifecycle(initialValue = emptyList())
    val people by viewModel.repo.peopleFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val peopleMap = remember(people) { people.associateBy { it.id } }
    val distByPerson = remember(distributions) { distributions.associateBy { it.personId } }
    var showAddBeneficiariesDialog by remember { mutableStateOf(false) }

    if (program == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(program.name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text("النوع: ${program.aidType} • الجهة المانحة: ${program.donorName.ifBlank { "-" }}", style = MaterialTheme.typography.bodySmall)
                    Text("المستفيدون المعتمدون: ${beneficiaries.size} • المستلمون فعلياً: ${distributions.size}", style = MaterialTheme.typography.labelLarge)
                    if (canManageAid) {
                        Button(
                            onClick = { showAddBeneficiariesDialog = true },
                            modifier = Modifier.fillMaxWidth().testTag("select_beneficiaries_button")
                        ) {
                            Icon(Icons.Filled.GroupAdd, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("اختيار وإضافة مستفيدين")
                        }
                    }
                }
            }
        }

        item {
            Text("كشف المستفيدين وتسجيل التوزيع", style = MaterialTheme.typography.titleMedium)
        }

        if (beneficiaries.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Filled.Group,
                    title = "لا يوجد مستفيدون مضافون بعد",
                    description = "اضغط على اختيار وإضافة مستفيدين لبدء التوزيع"
                )
            }
        }

        items(beneficiaries, key = { it.id }) { ben ->
            val person = peopleMap[ben.personId]
            val existingDist = distByPerson[ben.personId]
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(person?.fullName ?: "مواطن", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "التعريف: ${person?.citizenCode ?: ""} • الكمية: ${ben.allocatedQuantity} ${program.unitLabel}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (existingDist != null) {
                            Text(
                                "تم التسليم بواسطة: ${existingDist.responsibleEmployeeName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (existingDist != null) {
                        AssistChip(
                            onClick = {},
                            label = { Text("تم الاستلام") },
                            leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    } else {
                        Button(
                            onClick = {
                                viewModel.recordDistribution(
                                    programId = program.id,
                                    personId = ben.personId,
                                    beneficiaryId = ben.id,
                                    qty = ben.allocatedQuantity,
                                    amount = ben.allocatedAmount,
                                    status = "تم الاستلام",
                                    notes = ""
                                )
                            },
                            modifier = Modifier.testTag("deliver_aid_${ben.personId}")
                        ) {
                            Text("تسجيل استلام")
                        }
                    }
                }
            }
        }
    }

    if (showAddBeneficiariesDialog) {
        val existingBenIds = beneficiaries.map { it.personId }.toSet()
        val candidates = people.filter { it.id !in existingBenIds }
        val selectedIds = remember { mutableStateMapOf<String, Boolean>() }

        AlertDialog(
            onDismissRequest = { showAddBeneficiariesDialog = false },
            title = { Text("اختيار المستفيدين (${candidates.size} متاح)") },
            text = {
                if (candidates.isEmpty()) {
                    Text("جميع المواطنين المسجلين مضافون بالفعل إلى هذا البرنامج.")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                        items(candidates.take(200), key = { it.id }) { p ->
                            val checked = selectedIds[p.id] == true
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedIds[p.id] = !checked }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { selectedIds[p.id] = it })
                                Column {
                                    Text(p.fullName, style = MaterialTheme.typography.bodyMedium)
                                    Text("${p.citizenCode} • أفراد الأسرة: ${p.familySize}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val picked = selectedIds.filterValues { it }.keys.toList()
                        if (picked.isNotEmpty()) {
                            viewModel.addBeneficiaries(program.id, picked, program.defaultQuantity, program.defaultAmount)
                        }
                        showAddBeneficiariesDialog = false
                    }
                ) { Text("اعتماد المختارين") }
            },
            dismissButton = { TextButton(onClick = { showAddBeneficiariesDialog = false }) { Text("إغلاق") } }
        )
    }
}
