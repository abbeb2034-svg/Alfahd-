package com.example.data.remote

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class CloudProfile(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val username: String = "",
    val fullName: String = "",
    val email: String? = null,
    val phone: String? = null,
    val role: String = "employee",
    @get:PropertyName("isActive") @field:PropertyName("isActive")
    val isActive: Boolean = true,
    val canAddCitizen: Boolean = true,
    val canEditCitizen: Boolean = false,
    val canDeleteCitizen: Boolean = false,
    val canViewReports: Boolean = true,
    val canExport: Boolean = true,
    val canManageAid: Boolean = false,
    val canManageAreas: Boolean = false,
    val canManageUsers: Boolean = false,
    val canBackupRestore: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CloudNeighborhood(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val name: String = "",
    val governorate: String? = null,
    val district: String? = null,
    val aqilProfileId: String? = null,
    val aqilName: String? = null,
    val aqilPhone: String? = null,
    val notes: String? = null,
    val createdByName: String? = null,
    @get:PropertyName("isDeleted") @field:PropertyName("isDeleted")
    val isDeleted: Boolean = false,
    val syncVersion: Long = 1L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CloudResidentialSquare(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val neighborhoodId: String = "",
    val name: String = "",
    val squareCode: String? = null,
    val representativeProfileId: String? = null,
    val representativeName: String? = null,
    val representativePhone: String? = null,
    val notes: String? = null,
    val createdByName: String? = null,
    @get:PropertyName("isDeleted") @field:PropertyName("isDeleted")
    val isDeleted: Boolean = false,
    val syncVersion: Long = 1L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CloudRepresentativeHistory(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val profileId: String = "",
    val roleType: String = "representative",
    val neighborhoodId: String = "",
    val squareId: String? = null,
    val fullName: String = "",
    val phone: String? = null,
    @get:PropertyName("isCurrent") @field:PropertyName("isCurrent")
    val isCurrent: Boolean = true,
    val notes: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CloudCitizen(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val citizenCode: String = "",
    val fullName: String = "",
    val idType: String? = null,
    val nationalId: String? = null,
    val idIssueDate: String? = null,
    val phone: String? = null,
    val governorate: String? = null,
    val district: String? = null,
    val neighborhoodId: String = "",
    val squareId: String = "",
    val occupation: String? = null,
    val familySize: Long = 1L,
    val housingType: String? = null,
    val housingStatus: String? = null,
    val displacementDate: String? = null,
    val healthStatus: String? = null,
    val healthDetails: String? = null,
    val lastAidDate: String? = null,
    val notes: String? = null,
    val createdByName: String? = null,
    @get:PropertyName("isDeleted") @field:PropertyName("isDeleted")
    val isDeleted: Boolean = false,
    val syncVersion: Long = 1L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CloudIdentityDocument(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val personId: String = "",
    val documentType: String = "",
    val side: String = "single",
    val remoteStoragePath: String? = null,
    val mimeType: String? = null,
    val fileSizeBytes: Long = 0L,
    val sha256Checksum: String? = null,
    val uploaded: Boolean = false,
    @get:PropertyName("isDeleted") @field:PropertyName("isDeleted")
    val isDeleted: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CloudAidProgram(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val name: String = "",
    val aidType: String = "",
    val donorName: String? = null,
    val distributionDate: String = "",
    val unitLabel: String? = null,
    val defaultQuantity: Double = 1.0,
    val defaultAmount: Double = 0.0,
    val status: String? = null,
    val notes: String? = null,
    val createdByName: String? = null,
    @get:PropertyName("isDeleted") @field:PropertyName("isDeleted")
    val isDeleted: Boolean = false,
    val syncVersion: Long = 1L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CloudAidBeneficiary(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val programId: String = "",
    val personId: String = "",
    val allocatedQuantity: Double = 1.0,
    val allocatedAmount: Double = 0.0,
    @get:PropertyName("isApproved") @field:PropertyName("isApproved")
    val isApproved: Boolean = true,
    val notes: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CloudAidDistribution(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val programId: String = "",
    val beneficiaryId: String? = null,
    val personId: String = "",
    val quantityDelivered: Double = 1.0,
    val amountDelivered: Double = 0.0,
    val receivedStatus: String = "تم الاستلام",
    val responsibleEmployeeName: String? = null,
    val notes: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CloudAppSettings(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val organizationTitle: String = "",
    val headerSubtitle: String? = null,
    val headerRightText: String? = null,
    val headerLeftText: String? = null,
    val headerFontFamily: String = "Cairo",
    val headerFontSizeSp: Double = 13.0,
    val defaultGovernorate: String? = null,
    val defaultDistrict: String? = null,
    val defaultPaperSize: String? = null,
    val defaultOrientation: String? = null,
    val autoSyncEnabled: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CloudAuditLog(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val actorUsername: String = "",
    val actionType: String = "",
    val tableName: String = "",
    val recordId: String? = null,
    val summaryAr: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CloudSyncMetadata(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val deviceId: String = "",
    val entityTable: String = "",
    val recordId: String = "",
    val syncStatus: String = "Synced",
    val retryCount: Long = 0L,
    val lastError: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
