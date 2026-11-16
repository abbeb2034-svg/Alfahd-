package com.example.data.local

import kotlinx.serialization.Serializable

enum class UserRole(val dbValue: String, val arabicLabel: String) {
    ADMIN("admin", "مدير النظام"),
    EMPLOYEE("employee", "موظف إدخال"),
    AQIL("aqil", "عاقل حارة"),
    REPRESENTATIVE("representative", "مندوب مربع");

    companion object {
        fun fromDb(value: String): UserRole =
            entries.firstOrNull { it.dbValue == value } ?: EMPLOYEE
    }
}

enum class DocumentType(
    val arabicLabel: String,
    val requiredSides: List<String>
) {
    NATIONAL_ID("هوية وطنية", listOf("front", "back")),
    PASSPORT("جواز سفر", listOf("single")),
    QUESTIONNAIRE("استبيان", listOf("single")),
    FAMILY_CARD("بطاقة عائلية", listOf("single")),
    SEAT_CARD("بطاقة رقم الجلوس", listOf("single")),
    ID_CARD("البطاقة التعريفية", listOf("single"));

    companion object {
        fun fromLabel(label: String): DocumentType =
            entries.firstOrNull { it.arabicLabel == label } ?: NATIONAL_ID

        fun sideArabicLabel(side: String): String = when (side) {
            "front" -> "الوجه الأمامي"
            "back" -> "الوجه الخلفي"
            else -> "صورة الوثيقة"
        }
    }
}

enum class HousingType(val arabicLabel: String) {
    OWNER("مالك"),
    TENANT("مستأجر"),
    OTHER("أخرى");

    companion object {
        fun fromLabel(label: String): HousingType =
            entries.firstOrNull { it.arabicLabel == label } ?: TENANT
    }
}

enum class SyncStatus(val dbValue: String, val arabicLabel: String) {
    PENDING("Pending", "قيد الانتظار"),
    SYNCING("Syncing", "جاري المزامنة"),
    SYNCED("Synced", "متزامن"),
    FAILED("Failed", "فشل"),
    CONFLICT("Conflict", "تعارض");

    companion object {
        fun fromDb(value: String): SyncStatus =
            entries.firstOrNull { it.dbValue == value } ?: PENDING
    }
}

enum class ReportType(val arabicLabel: String) {
    ALL_CITIZENS("جميع المواطنين"),
    BY_NEIGHBORHOOD("مواطنو حارة معينة"),
    BY_SQUARE("مواطنو مربع معين"),
    BY_REPRESENTATIVE("حسب المندوب"),
    BY_AQIL("حسب عاقل الحارة"),
    BY_HEALTH_STATUS("حسب الحالة الصحية"),
    BY_HOUSING_TYPE("حسب نوع السكن"),
    BY_FAMILY_SIZE("حسب حجم الأسرة"),
    AID_BENEFICIARIES("المستفيدون من المساعدات"),
    AID_DISTRIBUTIONS("توزيع المساعدات"),
    NO_AID_RECEIVED("المواطنون الذين لم يحصلوا على مساعدة"),
    ENTRIES_BY_EMPLOYEE("عمليات الإدخال حسب الموظف"),
    GENERAL_STATISTICS("الإحصائيات العامة")
}

enum class ReportColumn(val key: String, val arabicLabel: String, val defaultSelected: Boolean = true) {
    CITIZEN_CODE("citizen_code", "رقم التعريف", true),
    FULL_NAME("full_name", "الاسم الكامل", true),
    ID_TYPE("id_type", "نوع الهوية", false),
    NATIONAL_ID("national_id", "رقم الهوية", true),
    PHONE("phone", "رقم الهاتف", true),
    NEIGHBORHOOD("neighborhood", "الحارة", true),
    SQUARE("square", "المربع", true),
    OCCUPATION("occupation", "المهنة", false),
    FAMILY_SIZE("family_size", "أفراد الأسرة", true),
    HOUSING_TYPE("housing_type", "نوع السكن", true),
    HOUSING_STATUS("housing_status", "حالة السكن", false),
    HEALTH_STATUS("health_status", "الحالة الصحية", true),
    DISPLACEMENT_DATE("displacement_date", "تاريخ النزوح", false),
    LAST_AID_DATE("last_aid_date", "آخر مساعدة", false),
    CREATED_BY("created_by", "الموظف المدخل", false),
    CREATED_AT("created_at", "تاريخ الإدخال", false)
}

@Serializable
data class UserPermissions(
    val canAddCitizen: Boolean = true,
    val canEditCitizen: Boolean = false,
    val canDeleteCitizen: Boolean = false,
    val canViewReports: Boolean = true,
    val canExport: Boolean = true,
    val canManageAid: Boolean = false,
    val canManageAreas: Boolean = false,
    val canManageUsers: Boolean = false,
    val canBackupRestore: Boolean = false
) {
    companion object {
        fun forRole(role: UserRole): UserPermissions = when (role) {
            UserRole.ADMIN -> UserPermissions(
                canAddCitizen = true,
                canEditCitizen = true,
                canDeleteCitizen = true,
                canViewReports = true,
                canExport = true,
                canManageAid = true,
                canManageAreas = true,
                canManageUsers = true,
                canBackupRestore = true
            )
            UserRole.EMPLOYEE -> UserPermissions(
                canAddCitizen = true,
                canEditCitizen = false,
                canDeleteCitizen = false,
                canViewReports = true,
                canExport = true,
                canManageAid = false,
                canManageAreas = false,
                canManageUsers = false,
                canBackupRestore = false
            )
            UserRole.AQIL, UserRole.REPRESENTATIVE -> UserPermissions(
                canAddCitizen = true,
                canEditCitizen = false,
                canDeleteCitizen = false,
                canViewReports = true,
                canExport = false,
                canManageAid = false,
                canManageAreas = false,
                canManageUsers = false,
                canBackupRestore = false
            )
        }
    }
}

enum class HeaderFontOption(val id: String, val arabicLabel: String) {
    CAIRO("Cairo", "خط القاهرة (Cairo)"),
    AMIRI("Amiri", "الخط الأميري الكلاسيكي (Amiri)"),
    TAJAWAL("Tajawal", "خط تجول (Tajawal)"),
    IBM_PLEX("IBM Plex Sans Arabic", "خط IBM العربي الحديث");

    companion object {
        fun fromId(id: String): HeaderFontOption =
            entries.firstOrNull { it.id == id } ?: CAIRO
    }
}
