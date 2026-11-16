package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "profiles",
    indices = [
        Index(value = ["username"], unique = true),
        Index(value = ["role"])
    ]
)
data class ProfileEntity(
    @PrimaryKey val id: String,
    val username: String,
    val fullName: String,
    val email: String = "",
    val phone: String = "",
    val role: String = UserRole.EMPLOYEE.dbValue,
    val passwordHash: String,
    val permissionsJson: String = "{}",
    val isActive: Boolean = true,
    val lastLoginAt: Long? = null,
    val createdBy: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: String = SyncStatus.PENDING.dbValue,
    val syncVersion: Long = 1L
)

@Serializable
@Entity(
    tableName = "neighborhoods",
    indices = [
        Index(value = ["name"]),
        Index(value = ["aqilProfileId"])
    ]
)
data class NeighborhoodEntity(
    @PrimaryKey val id: String,
    val name: String,
    val governorate: String = "",
    val district: String = "",
    val aqilProfileId: String? = null,
    val aqilName: String = "",
    val aqilPhone: String = "",
    val notes: String = "",
    val createdBy: String? = null,
    val createdByName: String = "",
    val isDeleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: String = SyncStatus.PENDING.dbValue,
    val syncVersion: Long = 1L
)

@Serializable
@Entity(
    tableName = "residential_squares",
    foreignKeys = [
        ForeignKey(
            entity = NeighborhoodEntity::class,
            parentColumns = ["id"],
            childColumns = ["neighborhoodId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["neighborhoodId"]),
        Index(value = ["representativeProfileId"])
    ]
)
data class ResidentialSquareEntity(
    @PrimaryKey val id: String,
    val neighborhoodId: String,
    val name: String,
    val squareCode: String = "",
    val representativeProfileId: String? = null,
    val representativeName: String = "",
    val representativePhone: String = "",
    val notes: String = "",
    val createdBy: String? = null,
    val createdByName: String = "",
    val isDeleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: String = SyncStatus.PENDING.dbValue,
    val syncVersion: Long = 1L
)

@Serializable
@Entity(
    tableName = "representatives",
    indices = [
        Index(value = ["profileId"]),
        Index(value = ["neighborhoodId"]),
        Index(value = ["squareId"]),
        Index(value = ["isCurrent", "roleType"])
    ]
)
data class RepresentativeHistoryEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val roleType: String, // "aqil" or "representative"
    val neighborhoodId: String,
    val squareId: String? = null,
    val fullName: String,
    val phone: String = "",
    val isCurrent: Boolean = true,
    val assignedAt: Long = System.currentTimeMillis(),
    val unassignedAt: Long? = null,
    val assignedBy: String? = null,
    val notes: String = "",
    val syncStatus: String = SyncStatus.PENDING.dbValue,
    val syncVersion: Long = 1L
)

@Serializable
@Entity(
    tableName = "people",
    indices = [
        Index(value = ["citizenCode"], unique = true),
        Index(value = ["fullName"]),
        Index(value = ["nationalId"]),
        Index(value = ["phone"]),
        Index(value = ["neighborhoodId", "squareId"]),
        Index(value = ["healthStatus"]),
        Index(value = ["housingType"]),
        Index(value = ["createdBy"]),
        Index(value = ["createdAt"])
    ]
)
data class PersonEntity(
    @PrimaryKey val id: String,
    val citizenCode: String,
    val fullName: String,
    val idType: String = DocumentType.NATIONAL_ID.arabicLabel,
    val nationalId: String,
    val idIssueDate: String = "",
    val phone: String = "",
    val governorate: String = "",
    val district: String = "",
    val neighborhoodId: String,
    val squareId: String,
    val occupation: String = "",
    val familySize: Int = 1,
    val housingType: String = HousingType.TENANT.arabicLabel,
    val housingStatus: String = "مستقر",
    val displacementDate: String = "",
    val healthStatus: String = "سليم",
    val healthDetails: String = "",
    val lastAidDate: String = "",
    val notes: String = "",
    val photoLocalPath: String? = null,
    val createdBy: String? = null,
    val createdByName: String = "",
    val updatedBy: String? = null,
    val isDeleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: String = SyncStatus.PENDING.dbValue,
    val syncVersion: Long = 1L
)

@Serializable
@Entity(
    tableName = "identity_documents",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["personId"]),
        Index(value = ["personId", "documentType", "side"], unique = true)
    ]
)
data class IdentityDocumentEntity(
    @PrimaryKey val id: String,
    val personId: String,
    val documentType: String,
    val side: String = "single", // front, back, single
    val localFilePath: String,
    val remoteStoragePath: String? = null,
    val mimeType: String = "image/jpeg",
    val fileSizeBytes: Long = 0L,
    val sha256Checksum: String = "",
    val uploaded: Boolean = false,
    val createdBy: String? = null,
    val isDeleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: String = SyncStatus.PENDING.dbValue
)

@Serializable
@Entity(
    tableName = "aid_programs",
    indices = [
        Index(value = ["distributionDate"]),
        Index(value = ["status"])
    ]
)
data class AidProgramEntity(
    @PrimaryKey val id: String,
    val name: String,
    val aidType: String,
    val donorName: String = "",
    val distributionDate: String,
    val targetNeighborhoodIdsJson: String = "[]",
    val targetSquareIdsJson: String = "[]",
    val unitLabel: String = "سلة غذائية",
    val defaultQuantity: Double = 1.0,
    val defaultAmount: Double = 0.0,
    val status: String = "نشط",
    val notes: String = "",
    val createdBy: String? = null,
    val createdByName: String = "",
    val isDeleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: String = SyncStatus.PENDING.dbValue,
    val syncVersion: Long = 1L
)

@Serializable
@Entity(
    tableName = "aid_beneficiaries",
    foreignKeys = [
        ForeignKey(
            entity = AidProgramEntity::class,
            parentColumns = ["id"],
            childColumns = ["programId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["programId"]),
        Index(value = ["personId"]),
        Index(value = ["programId", "personId"], unique = true)
    ]
)
data class AidBeneficiaryEntity(
    @PrimaryKey val id: String,
    val programId: String,
    val personId: String,
    val allocatedQuantity: Double = 1.0,
    val allocatedAmount: Double = 0.0,
    val isApproved: Boolean = true,
    val notes: String = "",
    val createdBy: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: String = SyncStatus.PENDING.dbValue
)

@Serializable
@Entity(
    tableName = "aid_distributions",
    foreignKeys = [
        ForeignKey(
            entity = AidProgramEntity::class,
            parentColumns = ["id"],
            childColumns = ["programId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["programId"]),
        Index(value = ["personId"]),
        Index(value = ["programId", "personId"], unique = true)
    ]
)
data class AidDistributionEntity(
    @PrimaryKey val id: String,
    val programId: String,
    val beneficiaryId: String? = null,
    val personId: String,
    val quantityDelivered: Double = 1.0,
    val amountDelivered: Double = 0.0,
    val receivedStatus: String = "تم الاستلام",
    val receivedAt: Long = System.currentTimeMillis(),
    val responsibleEmployeeId: String? = null,
    val responsibleEmployeeName: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: String = SyncStatus.PENDING.dbValue
)

@Serializable
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: String = "global",
    val organizationTitle: String = "سجل الفهد الميداني",
    val headerSubtitle: String = "نظام حصر المواطنين وإدارة المساعدات الإنسانية",
    val headerRightText: String = "الجمهورية اليمنية\nمحافظة عدن - مديرية المنصورة\nسجل الفهد الميداني",
    val headerLeftText: String = "نظام سجل الفهد\nإدارة السكان والمساعدات\nالوثائق الرسمية",
    val headerFontFamily: String = "Cairo",
    val headerFontSizeSp: Float = 13f,
    val officialHeaderLocalPath: String? = null,
    val eagleEmblemLocalPath: String? = null,
    val defaultGovernorate: String = "عدن",
    val defaultDistrict: String = "المنصورة",
    val defaultPaperSize: String = "A4",
    val defaultOrientation: String = "portrait",
    val supabaseUrl: String = "",
    val supabaseAnonKey: String = "",
    val autoSyncEnabled: Boolean = true,
    val updatedBy: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: String = SyncStatus.PENDING.dbValue
)

@Serializable
@Entity(
    tableName = "audit_logs",
    indices = [
        Index(value = ["actorProfileId"]),
        Index(value = ["tableName", "recordId"]),
        Index(value = ["createdAt"])
    ]
)
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val actorProfileId: String? = null,
    val actorUsername: String,
    val actionType: String,
    val tableName: String,
    val recordId: String = "",
    val summaryAr: String,
    val oldDataJson: String? = null,
    val newDataJson: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(
    tableName = "sync_metadata",
    indices = [
        Index(value = ["syncStatus"]),
        Index(value = ["entityTable", "recordId"], unique = true)
    ]
)
data class SyncMetadataEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val entityTable: String,
    val recordId: String,
    val syncStatus: String = SyncStatus.PENDING.dbValue,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val localUpdatedAt: Long = System.currentTimeMillis(),
    val serverSyncedAt: Long? = null,
    val conflictPayloadJson: String? = null
)
