package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.example.ui.components.DropdownSelectorField
import com.example.ui.components.EmptyStateCard
import com.example.ui.viewmodel.MainViewModel
import com.example.util.SecurityUtils

@Composable
fun NeighborhoodsScreen(
    viewModel: MainViewModel,
    canManage: Boolean
) {
    val neighborhoods by viewModel.neighborhoods.collectAsStateWithLifecycle()
    val squares by viewModel.squares.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var showNeighDialog by remember { mutableStateOf(false) }
    var editingNeigh by remember { mutableStateOf<NeighborhoodEntity?>(null) }
    var showSquareDialogForNeighId by remember { mutableStateOf<String?>(null) }
    var editingSquare by remember { mutableStateOf<ResidentialSquareEntity?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 84.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (neighborhoods.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Filled.LocationCity,
                        title = "لا توجد حارات مضافة",
                        description = "أضف حارة جديدة ثم أضف المربعات السكنية التابعة لها"
                    )
                }
            }
            items(neighborhoods, key = { it.id }) { neigh ->
                val neighSquares = squares.filter { it.neighborhoodId == neigh.id }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(neigh.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    "${neigh.governorate} — ${neigh.district}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (canManage) {
                                IconButton(
                                    onClick = {
                                        editingNeigh = neigh
                                        showNeighDialog = true
                                    },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Filled.Edit, contentDescription = "تعديل الحارة")
                                }
                            }
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "العاقل: ${neigh.aqilName.ifBlank { "غير معين" }}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    neigh.aqilPhone.ifBlank { "-" },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Text("المربعات السكنية (${neighSquares.size}):", style = MaterialTheme.typography.labelLarge)
                        neighSquares.forEach { sq ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${sq.name} (${sq.squareCode})", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "المندوب: ${sq.representativeName.ifBlank { "غير معين" }} • ${sq.representativePhone.ifBlank { "-" }}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (canManage) {
                                        IconButton(
                                            onClick = {
                                                editingSquare = sq
                                                showSquareDialogForNeighId = neigh.id
                                            },
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Icon(Icons.Filled.Edit, contentDescription = "تعديل المربع", modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }

                        if (canManage) {
                            OutlinedButton(
                                onClick = {
                                    editingSquare = null
                                    showSquareDialogForNeighId = neigh.id
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("إضافة مربع سكني لهذه الحارة")
                            }
                        }
                    }
                }
            }
        }

        if (canManage) {
            ExtendedFloatingActionButton(
                onClick = {
                    editingNeigh = null
                    showNeighDialog = true
                },
                icon = { Icon(Icons.Filled.AddLocationAlt, contentDescription = "إضافة حارة") },
                text = { Text("إضافة حارة") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag("add_neighborhood_fab")
            )
        }
    }

    if (showNeighDialog) {
        NeighborhoodFormDialog(
            existing = editingNeigh,
            defaultGovernorate = settings.defaultGovernorate,
            defaultDistrict = settings.defaultDistrict,
            profiles = profiles,
            onDismiss = { showNeighDialog = false },
            onSave = { name, gov, dist, aqilProf, aqilName, aqilPhone, notes ->
                viewModel.saveNeighborhood(editingNeigh?.id, name, gov, dist, aqilProf, aqilName, aqilPhone, notes) {
                    showNeighDialog = false
                }
            }
        )
    }

    if (showSquareDialogForNeighId != null) {
        SquareFormDialog(
            existing = editingSquare,
            profiles = profiles,
            onDismiss = { showSquareDialogForNeighId = null },
            onSave = { name, code, repProf, repName, repPhone, notes ->
                viewModel.saveSquare(
                    existingId = editingSquare?.id,
                    neighborhoodId = showSquareDialogForNeighId!!,
                    name = name,
                    code = code,
                    repProfile = repProf,
                    repName = repName,
                    repPhone = repPhone,
                    notes = notes
                ) {
                    showSquareDialogForNeighId = null
                }
            }
        )
    }
}

@Composable
private fun NeighborhoodFormDialog(
    existing: NeighborhoodEntity?,
    defaultGovernorate: String,
    defaultDistrict: String,
    profiles: List<ProfileEntity>,
    onDismiss: () -> Unit,
    onSave: (String, String, String, ProfileEntity?, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var gov by remember { mutableStateOf(existing?.governorate ?: defaultGovernorate) }
    var dist by remember { mutableStateOf(existing?.district ?: defaultDistrict) }
    var selectedAqil by remember { mutableStateOf(profiles.firstOrNull { it.id == existing?.aqilProfileId }) }
    var aqilName by remember { mutableStateOf(existing?.aqilName ?: "") }
    var aqilPhone by remember { mutableStateOf(existing?.aqilPhone ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "إضافة حارة جديدة" else "تعديل الحارة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم الحارة *") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = gov, onValueChange = { gov = it }, label = { Text("المحافظة") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = dist, onValueChange = { dist = it }, label = { Text("المديرية") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                DropdownSelectorField(
                    label = "تعيين عاقل من المستخدمين",
                    selectedText = selectedAqil?.fullName ?: "تعيين يدوي / بدون ربط",
                    items = listOf<ProfileEntity?>(null) + profiles,
                    itemLabel = { it?.let { p -> "${p.fullName} (${p.phone})" } ?: "تعيين يدوي" },
                    onSelect = {
                        selectedAqil = it
                        if (it != null) {
                            aqilName = it.fullName
                            aqilPhone = it.phone
                        }
                    }
                )
                OutlinedTextField(value = aqilName, onValueChange = { aqilName = it }, label = { Text("اسم عاقل الحارة") }, singleLine = true)
                OutlinedTextField(value = aqilPhone, onValueChange = { aqilPhone = it }, label = { Text("رقم هاتف العاقل") }, singleLine = true)
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("ملاحظات") })
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, gov, dist, selectedAqil, aqilName, aqilPhone, notes) },
                enabled = name.isNotBlank()
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun SquareFormDialog(
    existing: ResidentialSquareEntity?,
    profiles: List<ProfileEntity>,
    onDismiss: () -> Unit,
    onSave: (String, String, ProfileEntity?, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var code by remember { mutableStateOf(existing?.squareCode ?: "") }
    var selectedRep by remember { mutableStateOf(profiles.firstOrNull { it.id == existing?.representativeProfileId }) }
    var repName by remember { mutableStateOf(existing?.representativeName ?: "") }
    var repPhone by remember { mutableStateOf(existing?.representativePhone ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "إضافة مربع سكني" else "تعديل المربع السكني") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم المربع *") }, singleLine = true)
                OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("رمز المربع") }, singleLine = true)
                DropdownSelectorField(
                    label = "اختيار مندوب من المستخدمين",
                    selectedText = selectedRep?.fullName ?: "تعيين يدوي / بدون ربط",
                    items = listOf<ProfileEntity?>(null) + profiles,
                    itemLabel = { it?.let { p -> "${p.fullName} (${p.phone})" } ?: "تعيين يدوي" },
                    onSelect = {
                        selectedRep = it
                        if (it != null) {
                            repName = it.fullName
                            repPhone = it.phone
                        }
                    }
                )
                OutlinedTextField(value = repName, onValueChange = { repName = it }, label = { Text("اسم المندوب") }, singleLine = true)
                OutlinedTextField(value = repPhone, onValueChange = { repPhone = it }, label = { Text("هاتف المندوب") }, singleLine = true)
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("ملاحظات") })
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, code, selectedRep, repName, repPhone, notes) },
                enabled = name.isNotBlank()
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
fun AqilsScreen(viewModel: MainViewModel) {
    val neighborhoods by viewModel.neighborhoods.collectAsStateWithLifecycle()
    val history by viewModel.repHistory.collectAsStateWithLifecycle()
    val aqilHistory = history.filter { it.roleType == "aqil" }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("عقال الحارات المعتمدون", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        items(neighborhoods, key = { it.id }) { n ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(n.aqilName.ifBlank { "لم يتم تعيين عاقل" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("الحارة: ${n.name} (${n.district})", style = MaterialTheme.typography.bodySmall)
                    }
                    AssistChip(
                        onClick = {},
                        label = { Text(n.aqilPhone.ifBlank { "بدون هاتف" }) },
                        leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }
        }
        if (aqilHistory.isNotEmpty()) {
            item {
                Text("سجل تغييرات عقال الحارات", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            items(aqilHistory, key = { it.id }) { h ->
                Text(
                    "• ${h.fullName} (${h.phone}) — ${if (h.isCurrent) "حالي" else "سابق"} (${SecurityUtils.formatArabicDateTime(h.assignedAt)})",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun RepresentativesScreen(viewModel: MainViewModel) {
    val neighborhoods by viewModel.neighborhoods.collectAsStateWithLifecycle()
    val squares by viewModel.squares.collectAsStateWithLifecycle()
    val history by viewModel.repHistory.collectAsStateWithLifecycle()
    val neighMap = remember(neighborhoods) { neighborhoods.associateBy { it.id } }
    val repHistory = history.filter { it.roleType == "representative" }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("مندوبو المربعات السكنية", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        items(squares, key = { it.id }) { s ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.representativeName.ifBlank { "لم يتم تعيين مندوب" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${neighMap[s.neighborhoodId]?.name ?: ""} — ${s.name}", style = MaterialTheme.typography.bodySmall)
                    }
                    AssistChip(
                        onClick = {},
                        label = { Text(s.representativePhone.ifBlank { "بدون هاتف" }) },
                        leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }
        }
        if (repHistory.isNotEmpty()) {
            item {
                Text("الأرشيف التاريخي لتعيين المندوبين (محفوظ دون فقدان)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            items(repHistory, key = { it.id }) { h ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("${h.fullName} (${h.phone})", style = MaterialTheme.typography.labelMedium)
                            Text(h.notes, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            if (h.isCurrent) "حالي" else "سابق",
                            color = if (h.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsersScreen(
    viewModel: MainViewModel,
    onProfileSaved: (ProfileEntity) -> Unit = {}
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val actorIsAdmin = currentUser?.let { UserRole.fromDb(it.role) == UserRole.ADMIN } ?: false
    var showDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ProfileEntity?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 84.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(profiles, key = { it.id }) { prof ->
                val perms = viewModel.repo.parsePermissions(prof)
                val activeBadges = buildList {
                    if (perms.canAddCitizen) add("إضافة مواطن")
                    if (perms.canEditCitizen) add("تعديل")
                    if (perms.canDeleteCitizen) add("حذف")
                    if (perms.canViewReports) add("التقارير")
                    if (perms.canExport) add("تصدير")
                    if (perms.canManageAid) add("المساعدات")
                    if (perms.canManageAreas) add("المناطق")
                    if (perms.canManageUsers) add("المستخدمون")
                    if (perms.canBackupRestore) add("النسخ الاحتياطي")
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(prof.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("اسم المستخدم: ${prof.username} • ${UserRole.fromDb(prof.role).arabicLabel}", style = MaterialTheme.typography.bodySmall)
                                if (prof.email.isNotBlank()) {
                                    Text("البريد: ${prof.email}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                Text(
                                    "الهاتف: ${prof.phone.ifBlank { "-" }} • الحالة: ${if (prof.isActive) "نشط" else "معطل"}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            IconButton(
                                onClick = {
                                    editingProfile = prof
                                    showDialog = true
                                },
                                modifier = Modifier.minimumInteractiveComponentSize().testTag("edit_user_${prof.username}")
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = "تعديل المستخدم والصلاحيات")
                            }
                        }
                        if (activeBadges.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                activeBadges.forEach { badge ->
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(badge, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = {
                editingProfile = null
                showDialog = true
            },
            icon = { Icon(Icons.Filled.PersonAdd, contentDescription = "إضافة مستخدم") },
            text = { Text("إضافة مستخدم") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .testTag("add_user_fab")
        )
    }

    if (showDialog) {
        val initialPerms = editingProfile?.let { viewModel.repo.parsePermissions(it) } ?: UserPermissions.forRole(UserRole.EMPLOYEE)
        var username by remember { mutableStateOf(editingProfile?.username ?: "") }
        var fullName by remember { mutableStateOf(editingProfile?.fullName ?: "") }
        var email by remember { mutableStateOf(editingProfile?.email ?: "") }
        var phone by remember { mutableStateOf(editingProfile?.phone ?: "") }
        var role by remember { mutableStateOf(editingProfile?.let { UserRole.fromDb(it.role) } ?: UserRole.EMPLOYEE) }
        var password by remember { mutableStateOf("") }
        var isActive by remember { mutableStateOf(editingProfile?.isActive ?: true) }
        var perms by remember { mutableStateOf(initialPerms) }

        val availableRoles = if (actorIsAdmin) UserRole.entries else UserRole.entries.filter { it != UserRole.ADMIN }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(if (editingProfile == null) "إضافة مستخدم جديد وتحديد الصلاحيات" else "تعديل المستخدم والصلاحيات") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 500.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("اسم المستخدم *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("user_dialog_username")
                    )
                    OutlinedTextField(
                        value = fullName,
                        onValueChange = { fullName = it },
                        label = { Text("الاسم الكامل *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("user_dialog_fullname")
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("البريد الإلكتروني (اختياري)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("user_dialog_email")
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("رقم الهاتف") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownSelectorField(
                        label = "الدور الوظيفي",
                        selectedText = role.arabicLabel,
                        items = availableRoles,
                        itemLabel = { it.arabicLabel },
                        onSelect = {
                            role = it
                            perms = UserPermissions.forRole(it)
                        }
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(if (editingProfile == null) "كلمة المرور *" else "تعيين كلمة مرور جديدة (اختياري)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("user_dialog_password")
                    )
                    PermissionToggleRow(
                        label = "الحساب نشط (يسمح بتسجيل الدخول)",
                        checked = isActive,
                        tag = "user_dialog_active",
                        onCheckedChange = { isActive = it }
                    )

                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("الصلاحيات التفصيلية:", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { perms = UserPermissions.forRole(UserRole.ADMIN) }) {
                                Text("الكل", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { perms = UserPermissions(canAddCitizen = false, canViewReports = false, canExport = false) }) {
                                Text("مسح", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    PermissionToggleRow("إضافة مواطنين جدد / استيراد إكسل", perms.canAddCitizen, "perm_canAddCitizen") {
                        perms = perms.copy(canAddCitizen = it)
                    }
                    PermissionToggleRow("تعديل بيانات المواطنين", perms.canEditCitizen, "perm_canEditCitizen") {
                        perms = perms.copy(canEditCitizen = it)
                    }
                    PermissionToggleRow("حذف سجلات المواطنين", perms.canDeleteCitizen, "perm_canDeleteCitizen") {
                        perms = perms.copy(canDeleteCitizen = it)
                    }
                    PermissionToggleRow("عرض الكشوفات والتقارير", perms.canViewReports, "perm_canViewReports") {
                        perms = perms.copy(canViewReports = it)
                    }
                    PermissionToggleRow("تصدير PDF و Excel والبطاقات", perms.canExport, "perm_canExport") {
                        perms = perms.copy(canExport = it)
                    }
                    PermissionToggleRow("إدارة برامج المساعدات والتوزيع", perms.canManageAid, "perm_canManageAid") {
                        perms = perms.copy(canManageAid = it)
                    }
                    PermissionToggleRow("إدارة الحارات والمربعات السكنية", perms.canManageAreas, "perm_canManageAreas") {
                        perms = perms.copy(canManageAreas = it)
                    }
                    if (actorIsAdmin) {
                        PermissionToggleRow("إدارة المستخدمين وتحديد الصلاحيات", perms.canManageUsers, "perm_canManageUsers") {
                            perms = perms.copy(canManageUsers = it)
                        }
                    }
                    PermissionToggleRow("النسخ الاحتياطي والاستعادة", perms.canBackupRestore, "perm_canBackupRestore") {
                        perms = perms.copy(canBackupRestore = it)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveUser(
                            existingId = editingProfile?.id,
                            username = username,
                            fullName = fullName,
                            phone = phone,
                            role = role,
                            password = password.ifBlank { null },
                            permissions = perms,
                            isActive = isActive,
                            email = email,
                            onPersisted = onProfileSaved,
                            onDone = { showDialog = false }
                        )
                    },
                    enabled = username.isNotBlank() && fullName.isNotBlank() && (editingProfile != null || password.isNotBlank()),
                    modifier = Modifier.testTag("user_dialog_save_button")
                ) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun PermissionToggleRow(
    label: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}
