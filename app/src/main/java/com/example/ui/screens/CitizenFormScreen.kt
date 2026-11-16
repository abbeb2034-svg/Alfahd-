package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.*
import com.example.ui.components.DropdownSelectorField
import com.example.ui.viewmodel.MainViewModel
import com.example.util.ImageStorageManager
import com.example.util.SecurityUtils
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun CitizenFormScreen(
    existingPersonId: String?,
    viewModel: MainViewModel,
    onSaved: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val neighborhoods by viewModel.neighborhoods.collectAsStateWithLifecycle()
    val allSquares by viewModel.squares.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    var loadedOnce by remember { mutableStateOf(false) }

    // Guided step: 1) Neighborhood -> 2) Square -> 3) Citizen Data & Documents
    var selectedNeighborhoodId by remember { mutableStateOf("") }
    var selectedSquareId by remember { mutableStateOf("") }

    var fullName by remember { mutableStateOf("") }
    var selectedDocType by remember { mutableStateOf(DocumentType.NATIONAL_ID) }
    var nationalId by remember { mutableStateOf("") }
    var idIssueDate by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var governorate by remember { mutableStateOf(settings.defaultGovernorate) }
    var district by remember { mutableStateOf(settings.defaultDistrict) }
    var occupation by remember { mutableStateOf("") }
    var familySizeText by remember { mutableStateOf("1") }
    var selectedHousingType by remember { mutableStateOf(HousingType.TENANT) }
    var housingStatus by remember { mutableStateOf("مستقر") }
    var displacementDate by remember { mutableStateOf("") }
    var healthStatus by remember { mutableStateOf("سليم") }
    var healthDetails by remember { mutableStateOf("") }
    var lastAidDate by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    // Map of side ("front", "back", "single") -> IdentityDocumentEntity
    val docMap = remember { mutableStateMapOf<String, IdentityDocumentEntity>() }
    var activeCaptureSide by remember { mutableStateOf("front") }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(existingPersonId) {
        if (existingPersonId != null && !loadedOnce) {
            val person = viewModel.repo.searchPeoplePaged(limit = 5000).firstOrNull { it.id == existingPersonId }
            if (person != null) {
                selectedNeighborhoodId = person.neighborhoodId
                selectedSquareId = person.squareId
                fullName = person.fullName
                selectedDocType = DocumentType.fromLabel(person.idType)
                nationalId = person.nationalId
                idIssueDate = person.idIssueDate
                phone = person.phone
                governorate = person.governorate
                district = person.district
                occupation = person.occupation
                familySizeText = person.familySize.toString()
                selectedHousingType = HousingType.fromLabel(person.housingType)
                housingStatus = person.housingStatus
                displacementDate = person.displacementDate
                healthStatus = person.healthStatus
                healthDetails = person.healthDetails
                lastAidDate = person.lastAidDate
                notes = person.notes
            }
            val existingDocs = viewModel.repo.observePersonDocuments(existingPersonId)
            existingDocs.collect { list ->
                if (!loadedOnce) {
                    list.forEach { docMap[it.side] = it }
                    loadedOnce = true
                }
            }
        }
    }

    val neighborhoodSquares = remember(allSquares, selectedNeighborhoodId) {
        if (selectedNeighborhoodId.isBlank()) emptyList()
        else allSquares.filter { it.neighborhoodId == selectedNeighborhoodId }
    }

    // Photo Picker (Zero-permission per Play Policy)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val saved = ImageStorageManager.saveCompressedDocumentImage(context, uri)
                    docMap[activeCaptureSide] = IdentityDocumentEntity(
                        id = SecurityUtils.newUuid(),
                        personId = existingPersonId ?: "",
                        documentType = selectedDocType.arabicLabel,
                        side = activeCaptureSide,
                        localFilePath = saved.absolutePath,
                        fileSizeBytes = saved.sizeBytes,
                        sha256Checksum = saved.sha256
                    )
                    viewModel.showMessage("تم إرفاق وضغط الصورة بنجاح")
                } catch (e: Exception) {
                    viewModel.showMessage(e.message ?: "تعذر حفظ الصورة")
                }
            }
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val camFile = pendingCameraFile
        if (success && camFile != null && camFile.exists()) {
            scope.launch {
                try {
                    val saved = ImageStorageManager.saveCompressedFromFile(context, camFile)
                    docMap[activeCaptureSide] = IdentityDocumentEntity(
                        id = SecurityUtils.newUuid(),
                        personId = existingPersonId ?: "",
                        documentType = selectedDocType.arabicLabel,
                        side = activeCaptureSide,
                        localFilePath = saved.absolutePath,
                        fileSizeBytes = saved.sizeBytes,
                        sha256Checksum = saved.sha256
                    )
                    viewModel.showMessage("تم التقاط وحفظ صورة الوثيقة")
                } catch (e: Exception) {
                    viewModel.showMessage(e.message ?: "تعذر معالجة الصورة")
                }
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val (uri, file) = ImageStorageManager.createCameraCaptureUri(context)
            pendingCameraFile = file
            takePictureLauncher.launch(uri)
        } else {
            viewModel.showMessage("إذن الكاميرا مرفوض — يمكنك اختيار الصورة من المعرض")
        }
    }

    fun launchCameraForSide(side: String) {
        activeCaptureSide = side
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            val (uri, file) = ImageStorageManager.createCameraCaptureUri(context)
            pendingCameraFile = file
            takePictureLauncher.launch(uri)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val selectedNeighName = neighborhoods.firstOrNull { it.id == selectedNeighborhoodId }?.name ?: "اختر الحارة أولاً"
    val selectedSqName = neighborhoodSquares.firstOrNull { it.id == selectedSquareId }?.name ?: "اختر المربع السكني"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Step 1: Neighborhood -> Square Selection
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. الموقع السكني (الحارة ثم المربع)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                DropdownSelectorField(
                    label = "اختر الحارة *",
                    selectedText = selectedNeighName,
                    items = neighborhoods,
                    itemLabel = { it.name },
                    onSelect = { neigh ->
                        selectedNeighborhoodId = neigh.id
                        selectedSquareId = "" // Reset square when neighborhood changes
                        if (neigh.governorate.isNotBlank()) governorate = neigh.governorate
                        if (neigh.district.isNotBlank()) district = neigh.district
                    },
                    testTag = "citizen_neighborhood_dropdown"
                )

                if (selectedNeighborhoodId.isNotEmpty()) {
                    DropdownSelectorField(
                        label = "اختر المربع السكني التابع للحارة *",
                        selectedText = selectedSqName,
                        items = neighborhoodSquares,
                        itemLabel = { "${it.name} (${it.representativeName.ifBlank { "بدون مندوب" }})" },
                        onSelect = { sq -> selectedSquareId = sq.id },
                        testTag = "citizen_square_dropdown"
                    )
                } else {
                    Text(
                        "يرجى اختيار الحارة لعرض المربعات السكنية التابعة لها فقط",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Step 2: Citizen Fields (enabled once neighborhood & square are picked, or during edit)
        val areaReady = selectedNeighborhoodId.isNotEmpty() && selectedSquareId.isNotEmpty()
        if (!areaReady) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Text(
                    "سيظهر نموذج إدخال بيانات المواطن فور اختيار الحارة والمربع السكني أعلاه.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("2. البيانات الأساسية والهوية", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = fullName,
                        onValueChange = { fullName = it },
                        label = { Text("الاسم الكامل كما في الهوية *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("citizen_full_name_input")
                    )

                    DropdownSelectorField(
                        label = "نوع الوثيقة / الهوية",
                        selectedText = "${selectedDocType.arabicLabel} (${selectedDocType.requiredSides.size} صورة)",
                        items = DocumentType.entries,
                        itemLabel = { "${it.arabicLabel} (${if (it.requiredSides.size == 2) "صورتان: الوجه والخلف" else "صورة واحدة"})" },
                        onSelect = { selectedDocType = it },
                        testTag = "citizen_doc_type_dropdown"
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = nationalId,
                            onValueChange = { nationalId = it },
                            label = { Text("رقم الهوية") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("citizen_national_id_input")
                        )
                        OutlinedTextField(
                            value = idIssueDate,
                            onValueChange = { idIssueDate = it },
                            label = { Text("تاريخ الإصدار") },
                            placeholder = { Text("YYYY-MM-DD") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("رقم الهاتف") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("citizen_phone_input")
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = governorate,
                            onValueChange = { governorate = it },
                            label = { Text("المحافظة") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = district,
                            onValueChange = { district = it },
                            label = { Text("المديرية") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Step 3: Family, Housing & Health
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("3. الأسرة والسكن والحالة الصحية", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = occupation,
                            onValueChange = { occupation = it },
                            label = { Text("المهنة") },
                            singleLine = true,
                            modifier = Modifier.weight(1.2f)
                        )
                        OutlinedTextField(
                            value = familySizeText,
                            onValueChange = { familySizeText = it.filter { ch -> ch.isDigit() } },
                            label = { Text("أفراد الأسرة") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(0.8f).testTag("citizen_family_size_input")
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DropdownSelectorField(
                            label = "نوع السكن",
                            selectedText = selectedHousingType.arabicLabel,
                            items = HousingType.entries,
                            itemLabel = { it.arabicLabel },
                            onSelect = { selectedHousingType = it },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = housingStatus,
                            onValueChange = { housingStatus = it },
                            label = { Text("حالة السكن") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = displacementDate,
                            onValueChange = { displacementDate = it },
                            label = { Text("تاريخ النزوح (إن وجد)") },
                            placeholder = { Text("YYYY-MM-DD") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = lastAidDate,
                            onValueChange = { lastAidDate = it },
                            label = { Text("تاريخ آخر مساعدة") },
                            placeholder = { Text("YYYY-MM-DD") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = healthStatus,
                        onValueChange = { healthStatus = it },
                        label = { Text("الحالة الصحية") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = healthDetails,
                        onValueChange = { healthDetails = it },
                        label = { Text("تفاصيل الحالة الصحية") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("ملاحظات إضافية") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Step 4: Document Photo Slots (2 photos for National ID, 1 photo for all others)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "4. صور الوثيقة (${selectedDocType.arabicLabel})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    selectedDocType.requiredSides.forEach { side ->
                        val doc = docMap[side]
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(DocumentType.sideArabicLabel(side), style = MaterialTheme.typography.titleSmall)
                                    if (doc != null) {
                                        Icon(Icons.Filled.CheckCircle, contentDescription = "تم الإرفاق", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                if (doc != null) {
                                    val file = File(doc.localFilePath)
                                    if (file.exists()) {
                                        val bmp = remember(file.absolutePath) { BitmapFactory.decodeFile(file.absolutePath) }
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp.asImageBitmap(),
                                                contentDescription = side,
                                                modifier = Modifier.fillMaxWidth().height(130.dp),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { launchCameraForSide(side) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("التقاط كاميرا")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            activeCaptureSide = side
                                            photoPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("من المعرض")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    val fam = familySizeText.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val docsToSave = selectedDocType.requiredSides.mapNotNull { side ->
                        docMap[side]?.copy(documentType = selectedDocType.arabicLabel)
                    }
                    val draft = PersonEntity(
                        id = existingPersonId ?: SecurityUtils.newUuid(),
                        citizenCode = SecurityUtils.generateCitizenCode(),
                        fullName = fullName,
                        idType = selectedDocType.arabicLabel,
                        nationalId = nationalId,
                        idIssueDate = idIssueDate,
                        phone = phone,
                        governorate = governorate,
                        district = district,
                        neighborhoodId = selectedNeighborhoodId,
                        squareId = selectedSquareId,
                        occupation = occupation,
                        familySize = fam,
                        housingType = selectedHousingType.arabicLabel,
                        housingStatus = housingStatus,
                        displacementDate = displacementDate,
                        healthStatus = healthStatus,
                        healthDetails = healthDetails,
                        lastAidDate = lastAidDate,
                        notes = notes
                    )
                    viewModel.saveCitizen(existingPersonId, draft, docsToSave) { saved ->
                        onSaved(saved.id)
                    }
                },
                enabled = !isBusy && fullName.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("save_citizen_button")
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("حفظ السجل تلقائياً (يعمل دون إنترنت)")
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
