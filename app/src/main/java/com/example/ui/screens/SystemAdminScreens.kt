package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.HeaderFontOption
import com.example.export.OfficialAssetsHelper
import com.example.ui.components.DropdownSelectorField
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.OfficialHeaderBanner
import com.example.ui.components.SyncStatusBadge
import com.example.ui.viewmodel.MainViewModel
import com.example.util.SecurityUtils
import kotlin.math.roundToInt

@Composable
fun BackupRestoreScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val pendingInspection by viewModel.pendingRestoreInspection.collectAsStateWithLifecycle()

    val openBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.inspectBackupForRestore(uri)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("النسخ الاحتياطي الكامل (خاص بالمدير)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "يتضمن ملف النسخة الاحتياطية جميع الجداول الـ 12 والعلاقات وصور الهويات والمرفقات وبصمة التحقق SHA-256.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = {
                        viewModel.exportBackup { file ->
                            OfficialAssetsHelper.shareFile(context, file, "application/zip", "حفظ ومشاركة النسخة الاحتياطية")
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth().testTag("export_backup_button")
                ) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("إنشاء وتنزيل نسخة احتياطية (.zip)")
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("استعادة نسخة احتياطية والتحقق من سلامتها", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "سيتم فحص التوقيع الرقمي SHA-256 للنسخة أولاً، ثم طلب موافقتك الصريحة قبل استبدال البيانات الحالية.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = { openBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth().testTag("pick_restore_backup_button")
                ) {
                    Icon(Icons.Filled.Restore, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("اختيار ملف نسخة احتياطية للاستعادة")
                }
            }
        }
    }

    pendingInspection?.let { inspection ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissRestoreInspection() },
            title = { Text("تأكيد موافقة المدير قبل الاستعادة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("تم التحقق من سلامة النسخة بنجاح (SHA-256 مطابق).", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("تاريخ النسخة: ${inspection.manifest.createdAtIso}")
                    Text("عدد المواطنين: ${inspection.manifest.peopleCount}")
                    Text("عدد صور الوثائق: ${inspection.manifest.documentsCount}")
                    Text("عدد الحارات: ${inspection.manifest.neighborhoodsCount}")
                    Text(
                        "تحذير: الاستعادة ستستبدل البيانات المحلية الحالية ببيانات هذه النسخة.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmRestoreBackup() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("أوافق، استبدال واستعادة") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissRestoreInspection() }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    isAdmin: Boolean,
    onCloudSync: (() -> Unit)? = null
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val syncList by viewModel.syncMetadata.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    var orgTitle by remember(settings.organizationTitle) { mutableStateOf(settings.organizationTitle) }
    var subTitle by remember(settings.headerSubtitle) { mutableStateOf(settings.headerSubtitle) }
    var headerRightText by remember(settings.headerRightText) { mutableStateOf(settings.headerRightText) }
    var headerLeftText by remember(settings.headerLeftText) { mutableStateOf(settings.headerLeftText) }
    var headerFontFamily by remember(settings.headerFontFamily) { mutableStateOf(settings.headerFontFamily) }
    var headerFontSizeSp by remember(settings.headerFontSizeSp) { mutableStateOf(settings.headerFontSizeSp.coerceIn(10f, 22f)) }
    var gov by remember(settings.defaultGovernorate) { mutableStateOf(settings.defaultGovernorate) }
    var dist by remember(settings.defaultDistrict) { mutableStateOf(settings.defaultDistrict) }
    var landscapeDefault by remember(settings.defaultOrientation) { mutableStateOf(settings.defaultOrientation == "landscape") }
    var supabaseUrl by remember(settings.supabaseUrl) { mutableStateOf(settings.supabaseUrl) }
    var supabaseAnonKey by remember(settings.supabaseAnonKey) { mutableStateOf(settings.supabaseAnonKey) }
    var autoSync by remember(settings.autoSyncEnabled) { mutableStateOf(settings.autoSyncEnabled) }

    val previewSettings = remember(
        settings,
        orgTitle,
        subTitle,
        headerRightText,
        headerLeftText,
        headerFontFamily,
        headerFontSizeSp,
        gov,
        dist,
        landscapeDefault,
        supabaseUrl,
        supabaseAnonKey,
        autoSync
    ) {
        settings.copy(
            organizationTitle = orgTitle,
            headerSubtitle = subTitle,
            headerRightText = headerRightText,
            headerLeftText = headerLeftText,
            headerFontFamily = headerFontFamily,
            headerFontSizeSp = headerFontSizeSp,
            defaultGovernorate = gov,
            defaultDistrict = dist,
            defaultOrientation = if (landscapeDefault) "landscape" else "portrait",
            supabaseUrl = supabaseUrl,
            supabaseAnonKey = supabaseAnonKey,
            autoSyncEnabled = autoSync
        )
    }

    val pickHeaderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) viewModel.updateBrandingImage(uri, isHeader = true) }

    val pickEagleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) viewModel.updateBrandingImage(uri, isHeader = false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "معاينة الترويسة أعلى الصفحة (يمين الشعار • الشعار بالبسملة • يسار الشعار)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                OfficialHeaderBanner(settings = previewSettings)
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("إعدادات الترويسة الرسمية والخط", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = headerRightText,
                        onValueChange = { headerRightText = it },
                        label = { Text("الكتابة يمين الشعار (أعلى الصفحة)") },
                        enabled = isAdmin,
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("header_right_text_input")
                    )
                    OutlinedTextField(
                        value = headerLeftText,
                        onValueChange = { headerLeftText = it },
                        label = { Text("الكتابة يسار الشعار (أعلى الصفحة)") },
                        enabled = isAdmin,
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("header_left_text_input")
                    )

                    if (isAdmin) {
                        DropdownSelectorField(
                            label = "نوع خط الترويسة",
                            selectedText = HeaderFontOption.fromId(headerFontFamily).arabicLabel,
                            items = HeaderFontOption.entries,
                            itemLabel = { it.arabicLabel },
                            onSelect = { headerFontFamily = it.id },
                            testTag = "header_font_dropdown"
                        )
                    } else {
                        OutlinedTextField(
                            value = HeaderFontOption.fromId(headerFontFamily).arabicLabel,
                            onValueChange = {},
                            readOnly = true,
                            enabled = false,
                            label = { Text("نوع خط الترويسة") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "حجم خط الترويسة: ${headerFontSizeSp.roundToInt()} نقطة",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Slider(
                            value = headerFontSizeSp,
                            onValueChange = { if (isAdmin) headerFontSizeSp = it.roundToInt().toFloat() },
                            valueRange = 10f..22f,
                            steps = 11,
                            enabled = isAdmin,
                            modifier = Modifier.fillMaxWidth().testTag("header_font_size_slider")
                        )
                    }

                    HorizontalDivider()

                    OutlinedTextField(
                        value = orgTitle,
                        onValueChange = { orgTitle = it },
                        label = { Text("عنوان الجهة المختصر") },
                        enabled = isAdmin,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = subTitle,
                        onValueChange = { subTitle = it },
                        label = { Text("الوصف الفرعي") },
                        enabled = isAdmin,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = gov, onValueChange = { gov = it }, label = { Text("المحافظة الافتراضية") }, enabled = isAdmin, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = dist, onValueChange = { dist = it }, label = { Text("المديرية الافتراضية") }, enabled = isAdmin, singleLine = true, modifier = Modifier.weight(1f))
                    }

                    if (isAdmin) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { pickHeaderLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                modifier = Modifier.weight(1f)
                            ) { Text("تغيير صورة الترويسة") }
                            OutlinedButton(
                                onClick = { pickEagleLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                modifier = Modifier.weight(1f)
                            ) { Text("تغيير شعار البطاقة") }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("الطباعة الأفقية افتراضياً")
                        Switch(checked = landscapeDefault, onCheckedChange = { if (isAdmin) landscapeDefault = it })
                    }

                    if (isAdmin) {
                        Button(
                            onClick = { viewModel.saveSettings(previewSettings) },
                            modifier = Modifier.fillMaxWidth().testTag("save_header_settings_button")
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("حفظ الترويسة وحجم ونوع الخط")
                        }
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("ربط المزامنة السحابية (Firebase + Supabase)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "المزامنة السحابية عبر Firebase Firestore مفعّلة للحساب المصرح به. يمكنك أيضاً ضبط رابط Supabase ومفتاح Anon العام عند الحاجة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = supabaseUrl,
                        onValueChange = { supabaseUrl = it },
                        label = { Text("Supabase Project URL") },
                        placeholder = { Text("https://xxxx.supabase.co") },
                        enabled = isAdmin,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = supabaseAnonKey,
                        onValueChange = { supabaseAnonKey = it },
                        label = { Text("Supabase Anon Key") },
                        enabled = isAdmin,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("مزامنة تلقائية عند عودة الإنترنت")
                        Switch(checked = autoSync, onCheckedChange = { if (isAdmin) autoSync = it })
                    }

                    if (isAdmin) {
                        Button(
                            onClick = { viewModel.saveSettings(previewSettings) },
                            modifier = Modifier.fillMaxWidth().testTag("save_settings_button")
                        ) {
                            Text("حفظ جميع الإعدادات وتطبيقها فوراً")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            onCloudSync?.invoke() ?: viewModel.syncNow()
                        },
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth().testTag("manual_sync_button")
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("تشغيل المزامنة الآن (${syncList.count { it.syncStatus != "Synced" }} معلق)")
                    }
                }
            }
        }

        // Sync metadata queue viewer
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("حالة طابور المزامنة (${syncList.size})", style = MaterialTheme.typography.titleSmall)
                    if (syncList.isEmpty()) {
                        Text("لا توجد عمليات مزامنة مسجلة بعد.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        syncList.take(20).forEach { s ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${s.entityTable} (${s.recordId.take(8)}...)", style = MaterialTheme.typography.bodySmall)
                                SyncStatusBadge(s.syncStatus)
                            }
                        }
                    }
                }
            }
        }

        // Built-in Arabic User Guide
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("دليل الاستخدام السريع", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text("1. يعمل التطبيق دون إنترنت بشكل كامل ويحفظ السجلات والصور محلياً فورياً.", style = MaterialTheme.typography.bodySmall)
                    Text("2. عند إضافة مواطن، اختر الحارة أولاً لتظهر المربعات التابعة لها فقط.", style = MaterialTheme.typography.bodySmall)
                    Text("3. الهوية الوطنية تتطلب صورتين (الوجه والخلف)، وبقية الوثائق تتطلب صورة واحدة.", style = MaterialTheme.typography.bodySmall)
                    Text("4. تصدر الكشوفات بصيغتي PDF وExcel (.xlsx) بترويسة وعناوين أعمدة متكررة في كل صفحة.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun AuditLogsScreen(viewModel: MainViewModel) {
    val logs by viewModel.auditLogs.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (logs.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Filled.History,
                    title = "لا توجد عمليات مسجلة",
                    description = "تُسجّل هنا جميع عمليات الدخول والإضافة والتعديل والحذف والتصدير والنسخ الاحتياطي"
                )
            }
        }
        items(logs, key = { it.id }) { log ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(log.actionType, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(SecurityUtils.formatArabicDateTime(log.createdAt), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(log.summaryAr, style = MaterialTheme.typography.bodyMedium)
                    Text("المستخدم: ${log.actorUsername} • الجدول: ${log.tableName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
