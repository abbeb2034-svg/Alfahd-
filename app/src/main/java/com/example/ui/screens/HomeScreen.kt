package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.AppSettingsEntity
import com.example.data.local.ProfileEntity
import com.example.data.local.UserPermissions
import com.example.data.local.UserRole
import com.example.ui.components.KpiStatCard
import com.example.ui.components.OfficialHeaderBanner
import com.example.ui.theme.GoldSecondary
import com.example.ui.theme.SlateTertiary
import com.example.ui.theme.StatusPending
import com.example.ui.theme.StatusSynced
import com.example.ui.viewmodel.DashboardMetrics

private data class HomeModuleItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val tag: String,
    val onClick: () -> Unit
)

@Composable
fun HomeScreen(
    user: ProfileEntity,
    permissions: UserPermissions,
    settings: AppSettingsEntity,
    metrics: DashboardMetrics,
    googleEmail: String? = null,
    onNavigateCitizens: () -> Unit,
    onAddCitizen: () -> Unit,
    onImportExcel: () -> Unit = {},
    onNavigateSearch: () -> Unit,
    onNavigateNeighborhoods: () -> Unit,
    onNavigateAqils: () -> Unit,
    onNavigateReps: () -> Unit,
    onNavigateUsers: () -> Unit,
    onNavigateAid: () -> Unit,
    onNavigateReports: () -> Unit,
    onNavigateBackup: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigateAuditLogs: () -> Unit,
    onSyncNow: () -> Unit,
    onLogout: () -> Unit,
    onGoogleSignOut: () -> Unit = {}
) {
    val isAdmin = UserRole.fromDb(user.role) == UserRole.ADMIN

    val primaryModules = buildList {
        add(HomeModuleItem("إدارة المواطنين", "عرض السجلات والبطاقات والاستمارات", Icons.Filled.People, "mod_citizens", onNavigateCitizens))
        if (permissions.canAddCitizen || isAdmin) {
            add(HomeModuleItem("إضافة مواطن جديد", "إدخال بيانات المواطن وصور الهوية", Icons.Filled.PersonAdd, "mod_add_citizen", onAddCitizen))
            add(HomeModuleItem("استيراد من إكسل", "رفع ملف إكسل وتحديد رؤوس الجدول", Icons.Filled.UploadFile, "mod_import_excel", onImportExcel))
        }
        add(HomeModuleItem("البحث والتصفية", "بحث متقدم بالاسم والهوية والحارة", Icons.Filled.Search, "mod_search", onNavigateSearch))
        add(HomeModuleItem("الكشوفات والتقارير", "13 تقريراً مع PDF و Excel مسطر", Icons.Filled.Assessment, "mod_reports", onNavigateReports))
        add(HomeModuleItem("المساعدات والإعانات", "البرامج والمستفيدون والتوزيع", Icons.Filled.VolunteerActivism, "mod_aid", onNavigateAid))
        add(HomeModuleItem("الحارات والمربعات", "إدارة الحارات والمربعات السكنية", Icons.Filled.LocationCity, "mod_areas", onNavigateNeighborhoods))
        add(HomeModuleItem("عقال الحارات", "بيانات العقال والتعيينات", Icons.Filled.AdminPanelSettings, "mod_aqils", onNavigateAqils))
        add(HomeModuleItem("مندوبو المربعات", "المندوبون وسجل التغييرات", Icons.Filled.Badge, "mod_reps", onNavigateReps))
        if (isAdmin || permissions.canManageUsers) {
            add(HomeModuleItem("المستخدمون والصلاحيات", "حسابات المديرين والموظفين", Icons.Filled.SupervisorAccount, "mod_users", onNavigateUsers))
        }
        if (isAdmin || permissions.canBackupRestore) {
            add(HomeModuleItem("النسخ الاحتياطي", "تصدير واستعادة النسخة الكاملة", Icons.Filled.Backup, "mod_backup", onNavigateBackup))
        }
        add(HomeModuleItem("سجل العمليات", "متابعة الإدخال والتعديل والتصدير", Icons.Filled.History, "mod_audit", onNavigateAuditLogs))
        add(HomeModuleItem("إعدادات التطبيق", "الترويسة والشعار والمزامنة السحابية", Icons.Filled.Settings, "mod_settings", onNavigateSettings))
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            OfficialHeaderBanner(settings = settings)
        }

        // Welcome & Role Strip
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "مرحباً، ${user.fullName}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "الصلاحية: ${UserRole.fromDb(user.role).arabicLabel} • يعمل دون إنترنت تلقائياً",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalIconButton(
                            onClick = onLogout,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("home_logout_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "تسجيل الخروج")
                        }
                    }
                    if (!googleEmail.isNullOrBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "حساب المزامنة: $googleEmail",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            TextButton(onClick = onGoogleSignOut) {
                                Text("تبديل حساب Google", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        // KPIs 2x2 Grid
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    KpiStatCard(
                        title = "إجمالي المواطنين",
                        value = metrics.totalPeople.toString(),
                        icon = Icons.Filled.People,
                        accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateCitizens
                    )
                    KpiStatCard(
                        title = "إجمالي أفراد الأسر",
                        value = metrics.totalFamilyMembers.toString(),
                        icon = Icons.Filled.FamilyRestroom,
                        accent = GoldSecondary,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    KpiStatCard(
                        title = "الحارات / المربعات",
                        value = "${metrics.totalNeighborhoods} / ${metrics.totalSquares}",
                        icon = Icons.Filled.Map,
                        accent = SlateTertiary,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateNeighborhoods
                    )
                    KpiStatCard(
                        title = "بانتظار المزامنة",
                        value = metrics.pendingSyncCount.toString(),
                        icon = if (metrics.pendingSyncCount == 0) Icons.Filled.CloudDone else Icons.Filled.CloudSync,
                        accent = if (metrics.pendingSyncCount == 0) StatusSynced else StatusPending,
                        modifier = Modifier.weight(1f),
                        onClick = onSyncNow
                    )
                }
            }
        }

        item {
            Text(
                text = "وحدات النظام والخدمات",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // 2-Column Module Grid
        items(primaryModules.chunked(2).size) { rowIndex ->
            val pair = primaryModules.chunked(2)[rowIndex]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                pair.forEach { mod ->
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = mod.onClick)
                            .testTag(mod.tag),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = mod.icon,
                                contentDescription = mod.title,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = mod.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = mod.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                minLines = 2,
                                maxLines = 2
                            )
                        }
                    }
                }
                if (pair.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
