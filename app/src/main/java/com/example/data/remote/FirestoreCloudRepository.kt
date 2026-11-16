package com.example.data.remote

import android.content.Context
import com.example.R
import com.example.data.local.*
import com.example.data.repository.SyncSummary
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class FirestoreCloudRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val jsonParser = Json { ignoreUnknownKeys = true }

    companion object {
        const val BOOTSTRAP_ADMIN_EMAIL = "abbeb2031@gmail.com"
        const val COL_PROFILES = "profiles"
        const val COL_NEIGHBORHOODS = "neighborhoods"
        const val COL_RESIDENTIAL_SQUARES = "residential_squares"
        const val COL_REPRESENTATIVES = "representatives"
        const val COL_PEOPLE = "people"
        const val COL_IDENTITY_DOCUMENTS = "identity_documents"
        const val COL_AID_PROGRAMS = "aid_programs"
        const val COL_AID_BENEFICIARIES = "aid_beneficiaries"
        const val COL_AID_DISTRIBUTIONS = "aid_distributions"
        const val COL_APP_SETTINGS = "app_settings"
        const val COL_AUDIT_LOGS = "audit_logs"
        const val COL_SYNC_METADATA = "sync_metadata"
    }

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("يجب تسجيل الدخول بحساب Google قبل المزامنة مع السحابة")
    }

    private fun isCurrentBootstrapAdmin(): Boolean {
        val user = auth.currentUser ?: return false
        return user.isEmailVerified && user.email == BOOTSTRAP_ADMIN_EMAIL
    }

    private fun scopedQuery(collectionName: String, uid: String): Query {
        val col = db.collection(collectionName)
        return if (isCurrentBootstrapAdmin()) col else col.whereEqualTo("ownerUid", uid)
    }

    private suspend fun docExistsInScope(collectionName: String, docId: String, uid: String): Boolean {
        val snap = scopedQuery(collectionName, uid)
            .get()
            .await()
        return snap.documents.any { it.id == docId }
    }

    private inline fun <reified T> observeCollection(collectionName: String): Flow<List<T>> = flow {
        val uid = requireUserId()
        emitAll(
            scopedQuery(collectionName, uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        T::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, collectionName)
                    }
                    throw error
                }
        )
    }

    fun observeProfiles(): Flow<List<CloudProfile>> = observeCollection(COL_PROFILES)
    fun observeNeighborhoods(): Flow<List<CloudNeighborhood>> = observeCollection(COL_NEIGHBORHOODS)
    fun observeSquares(): Flow<List<CloudResidentialSquare>> = observeCollection(COL_RESIDENTIAL_SQUARES)
    fun observeRepresentatives(): Flow<List<CloudRepresentativeHistory>> = observeCollection(COL_REPRESENTATIVES)
    fun observeCitizens(): Flow<List<CloudCitizen>> = observeCollection(COL_PEOPLE)
    fun observeIdentityDocuments(): Flow<List<CloudIdentityDocument>> = observeCollection(COL_IDENTITY_DOCUMENTS)
    fun observeAidPrograms(): Flow<List<CloudAidProgram>> = observeCollection(COL_AID_PROGRAMS)
    fun observeAidBeneficiaries(): Flow<List<CloudAidBeneficiary>> = observeCollection(COL_AID_BENEFICIARIES)
    fun observeAidDistributions(): Flow<List<CloudAidDistribution>> = observeCollection(COL_AID_DISTRIBUTIONS)
    fun observeAuditLogs(): Flow<List<CloudAuditLog>> = observeCollection(COL_AUDIT_LOGS)
    fun observeSyncMetadata(): Flow<List<CloudSyncMetadata>> = observeCollection(COL_SYNC_METADATA)

    fun observeSettings(): Flow<CloudAppSettings?> = flow {
        val uid = requireUserId()
        val docRef = db.collection(COL_APP_SETTINGS).document("global")
        emitAll(
            docRef.snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(
                            CloudAppSettings::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else null
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, docRef.path)
                    }
                    throw error
                }
        )
    }

    suspend fun ensureUserProfile(
        displayName: String,
        email: String,
        username: String? = null
    ) {
        val uid = requireUserId()
        val docRef = db.collection(COL_PROFILES).document(uid)
        val safeName = displayName.trim().ifEmpty { "مستخدم سجل الفهد" }.take(150)
        val safeEmail = email.trim().take(160)
        val derivedUsername = (username?.trim()?.ifEmpty { null }
            ?: safeEmail.substringBefore("@").ifEmpty { "user_$uid" })
            .padEnd(2, '_')
            .take(64)

        try {
            val existing = docRef.get().await()
            if (existing.exists()) {
                val updates = mapOf<String, Any>(
                    "fullName" to safeName,
                    "email" to safeEmail,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            } else {
                val bootstrapAdmin = isCurrentBootstrapAdmin()
                val role = if (bootstrapAdmin) UserRole.ADMIN.dbValue else UserRole.EMPLOYEE.dbValue
                val payload = mapOf<String, Any>(
                    "ownerUid" to uid,
                    "username" to derivedUsername,
                    "fullName" to safeName,
                    "email" to safeEmail,
                    "phone" to "",
                    "role" to role,
                    "isActive" to true,
                    "canAddCitizen" to true,
                    "canEditCitizen" to bootstrapAdmin,
                    "canDeleteCitizen" to bootstrapAdmin,
                    "canViewReports" to true,
                    "canExport" to true,
                    "canManageAid" to bootstrapAdmin,
                    "canManageAreas" to bootstrapAdmin,
                    "canManageUsers" to bootstrapAdmin,
                    "canBackupRestore" to bootstrapAdmin,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun upsertProfile(entity: ProfileEntity, email: String = "") {
        val callerUid = requireUserId()
        val docRef = db.collection(COL_PROFILES).document(entity.id)
        val perms = runCatching {
            jsonParser.decodeFromString<UserPermissions>(entity.permissionsJson)
        }.getOrElse {
            UserPermissions.forRole(UserRole.fromDb(entity.role))
        }
        val safeRole = UserRole.fromDb(entity.role).dbValue
        val safeUsername = entity.username.trim().ifEmpty { "user_${entity.id}" }.padEnd(2, '_').take(64)
        val safeFullName = entity.fullName.trim().ifEmpty { safeUsername }.take(150)

        try {
            val existing = docRef.get().await()
            if (existing.exists()) {
                val updates = mutableMapOf<String, Any>(
                    "username" to safeUsername,
                    "fullName" to safeFullName,
                    "phone" to entity.phone.take(40),
                    "role" to safeRole,
                    "isActive" to entity.isActive,
                    "canAddCitizen" to perms.canAddCitizen,
                    "canEditCitizen" to perms.canEditCitizen,
                    "canDeleteCitizen" to perms.canDeleteCitizen,
                    "canViewReports" to perms.canViewReports,
                    "canExport" to perms.canExport,
                    "canManageAid" to perms.canManageAid,
                    "canManageAreas" to perms.canManageAreas,
                    "canManageUsers" to perms.canManageUsers,
                    "canBackupRestore" to perms.canBackupRestore,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (email.isNotBlank()) updates["email"] = email.trim().take(160)
                docRef.update(updates).await()
            } else {
                val payload = mapOf<String, Any>(
                    "ownerUid" to callerUid,
                    "username" to safeUsername,
                    "fullName" to safeFullName,
                    "email" to email.trim().take(160),
                    "phone" to entity.phone.take(40),
                    "role" to safeRole,
                    "isActive" to entity.isActive,
                    "canAddCitizen" to perms.canAddCitizen,
                    "canEditCitizen" to perms.canEditCitizen,
                    "canDeleteCitizen" to perms.canDeleteCitizen,
                    "canViewReports" to perms.canViewReports,
                    "canExport" to perms.canExport,
                    "canManageAid" to perms.canManageAid,
                    "canManageAreas" to perms.canManageAreas,
                    "canManageUsers" to perms.canManageUsers,
                    "canBackupRestore" to perms.canBackupRestore,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun upsertNeighborhood(entity: NeighborhoodEntity) {
        val uid = requireUserId()
        val docRef = db.collection(COL_NEIGHBORHOODS).document(entity.id)
        try {
            val exists = docExistsInScope(COL_NEIGHBORHOODS, entity.id, uid)
            if (exists) {
                val updates = mutableMapOf<String, Any>(
                    "name" to entity.name.ifBlank { "حارة" }.take(150),
                    "governorate" to entity.governorate.take(100),
                    "district" to entity.district.take(100),
                    "aqilName" to entity.aqilName.take(150),
                    "aqilPhone" to entity.aqilPhone.take(40),
                    "notes" to entity.notes.take(500),
                    "isDeleted" to entity.isDeleted,
                    "syncVersion" to entity.syncVersion.coerceAtLeast(1L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                entity.aqilProfileId?.takeIf { it.isNotBlank() }?.let {
                    updates["aqilProfileId"] = it.take(128)
                }
                docRef.update(updates).await()
            } else {
                val payload = mutableMapOf<String, Any>(
                    "ownerUid" to uid,
                    "name" to entity.name.ifBlank { "حارة" }.take(150),
                    "governorate" to entity.governorate.take(100),
                    "district" to entity.district.take(100),
                    "aqilName" to entity.aqilName.take(150),
                    "aqilPhone" to entity.aqilPhone.take(40),
                    "notes" to entity.notes.take(500),
                    "createdByName" to entity.createdByName.take(150),
                    "isDeleted" to entity.isDeleted,
                    "syncVersion" to entity.syncVersion.coerceAtLeast(1L),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                entity.aqilProfileId?.takeIf { it.isNotBlank() }?.let {
                    payload["aqilProfileId"] = it.take(128)
                }
                docRef.set(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun upsertSquare(entity: ResidentialSquareEntity) {
        val uid = requireUserId()
        val docRef = db.collection(COL_RESIDENTIAL_SQUARES).document(entity.id)
        try {
            val exists = docExistsInScope(COL_RESIDENTIAL_SQUARES, entity.id, uid)
            if (exists) {
                val updates = mutableMapOf<String, Any>(
                    "neighborhoodId" to entity.neighborhoodId,
                    "name" to entity.name.ifBlank { "مربع" }.take(150),
                    "squareCode" to entity.squareCode.take(60),
                    "representativeName" to entity.representativeName.take(150),
                    "representativePhone" to entity.representativePhone.take(40),
                    "notes" to entity.notes.take(500),
                    "isDeleted" to entity.isDeleted,
                    "syncVersion" to entity.syncVersion.coerceAtLeast(1L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                entity.representativeProfileId?.takeIf { it.isNotBlank() }?.let {
                    updates["representativeProfileId"] = it.take(128)
                }
                docRef.update(updates).await()
            } else {
                val payload = mutableMapOf<String, Any>(
                    "ownerUid" to uid,
                    "neighborhoodId" to entity.neighborhoodId,
                    "name" to entity.name.ifBlank { "مربع" }.take(150),
                    "squareCode" to entity.squareCode.take(60),
                    "representativeName" to entity.representativeName.take(150),
                    "representativePhone" to entity.representativePhone.take(40),
                    "notes" to entity.notes.take(500),
                    "createdByName" to entity.createdByName.take(150),
                    "isDeleted" to entity.isDeleted,
                    "syncVersion" to entity.syncVersion.coerceAtLeast(1L),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                entity.representativeProfileId?.takeIf { it.isNotBlank() }?.let {
                    payload["representativeProfileId"] = it.take(128)
                }
                docRef.set(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun upsertRepresentativeHistory(entity: RepresentativeHistoryEntity) {
        val uid = requireUserId()
        val docRef = db.collection(COL_REPRESENTATIVES).document(entity.id)
        val safeRoleType = if (entity.roleType == "aqil") "aqil" else "representative"
        try {
            val exists = docExistsInScope(COL_REPRESENTATIVES, entity.id, uid)
            if (exists) {
                val updates = mapOf<String, Any>(
                    "fullName" to entity.fullName.ifBlank { "مندوب" }.take(150),
                    "phone" to entity.phone.take(40),
                    "isCurrent" to entity.isCurrent,
                    "notes" to entity.notes.take(500),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            } else {
                val payload = mutableMapOf<String, Any>(
                    "ownerUid" to uid,
                    "profileId" to entity.profileId,
                    "roleType" to safeRoleType,
                    "neighborhoodId" to entity.neighborhoodId,
                    "fullName" to entity.fullName.ifBlank { "مندوب" }.take(150),
                    "phone" to entity.phone.take(40),
                    "isCurrent" to entity.isCurrent,
                    "notes" to entity.notes.take(500),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                entity.squareId?.takeIf { it.isNotBlank() }?.let {
                    payload["squareId"] = it.take(128)
                }
                docRef.set(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun upsertCitizen(entity: PersonEntity) {
        val uid = requireUserId()
        val docRef = db.collection(COL_PEOPLE).document(entity.id)
        try {
            val exists = docExistsInScope(COL_PEOPLE, entity.id, uid)
            val safeFamilySize = entity.familySize.coerceIn(1, 100).toLong()
            if (exists) {
                val updates = mapOf<String, Any>(
                    "citizenCode" to entity.citizenCode.ifBlank { entity.id.take(16) }.take(64),
                    "fullName" to entity.fullName.ifBlank { "مواطن" }.take(200),
                    "idType" to entity.idType.take(60),
                    "nationalId" to entity.nationalId.take(64),
                    "idIssueDate" to entity.idIssueDate.take(40),
                    "phone" to entity.phone.take(40),
                    "governorate" to entity.governorate.take(100),
                    "district" to entity.district.take(100),
                    "neighborhoodId" to entity.neighborhoodId,
                    "squareId" to entity.squareId,
                    "occupation" to entity.occupation.take(120),
                    "familySize" to safeFamilySize,
                    "housingType" to entity.housingType.take(60),
                    "housingStatus" to entity.housingStatus.take(60),
                    "displacementDate" to entity.displacementDate.take(40),
                    "healthStatus" to entity.healthStatus.take(80),
                    "healthDetails" to entity.healthDetails.take(300),
                    "lastAidDate" to entity.lastAidDate.take(40),
                    "notes" to entity.notes.take(1000),
                    "createdByName" to entity.createdByName.take(150),
                    "isDeleted" to entity.isDeleted,
                    "syncVersion" to entity.syncVersion.coerceAtLeast(1L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            } else {
                val payload = mapOf<String, Any>(
                    "ownerUid" to uid,
                    "citizenCode" to entity.citizenCode.ifBlank { entity.id.take(16) }.take(64),
                    "fullName" to entity.fullName.ifBlank { "مواطن" }.take(200),
                    "idType" to entity.idType.take(60),
                    "nationalId" to entity.nationalId.take(64),
                    "idIssueDate" to entity.idIssueDate.take(40),
                    "phone" to entity.phone.take(40),
                    "governorate" to entity.governorate.take(100),
                    "district" to entity.district.take(100),
                    "neighborhoodId" to entity.neighborhoodId,
                    "squareId" to entity.squareId,
                    "occupation" to entity.occupation.take(120),
                    "familySize" to safeFamilySize,
                    "housingType" to entity.housingType.take(60),
                    "housingStatus" to entity.housingStatus.take(60),
                    "displacementDate" to entity.displacementDate.take(40),
                    "healthStatus" to entity.healthStatus.take(80),
                    "healthDetails" to entity.healthDetails.take(300),
                    "lastAidDate" to entity.lastAidDate.take(40),
                    "notes" to entity.notes.take(1000),
                    "createdByName" to entity.createdByName.take(150),
                    "isDeleted" to entity.isDeleted,
                    "syncVersion" to entity.syncVersion.coerceAtLeast(1L),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun getCitizen(personId: String): CloudCitizen? {
        requireUserId()
        val docRef = db.collection(COL_PEOPLE).document(personId)
        return try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                snap.toObject(
                    CloudCitizen::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            } else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            throw e
        }
    }

    suspend fun upsertIdentityDocument(entity: IdentityDocumentEntity) {
        val uid = requireUserId()
        val docRef = db.collection(COL_IDENTITY_DOCUMENTS).document(entity.id)
        val safeSide = if (entity.side in listOf("front", "back", "single")) entity.side else "single"
        try {
            val exists = docExistsInScope(COL_IDENTITY_DOCUMENTS, entity.id, uid)
            if (exists) {
                val updates = mutableMapOf<String, Any>(
                    "documentType" to entity.documentType.ifBlank { "وثيقة" }.take(80),
                    "side" to safeSide,
                    "mimeType" to entity.mimeType.take(80),
                    "fileSizeBytes" to entity.fileSizeBytes.coerceAtLeast(0L),
                    "sha256Checksum" to entity.sha256Checksum.take(128),
                    "uploaded" to entity.uploaded,
                    "isDeleted" to entity.isDeleted,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                entity.remoteStoragePath?.let { updates["remoteStoragePath"] = it.take(300) }
                docRef.update(updates).await()
            } else {
                val payload = mutableMapOf<String, Any>(
                    "ownerUid" to uid,
                    "personId" to entity.personId,
                    "documentType" to entity.documentType.ifBlank { "وثيقة" }.take(80),
                    "side" to safeSide,
                    "mimeType" to entity.mimeType.take(80),
                    "fileSizeBytes" to entity.fileSizeBytes.coerceAtLeast(0L),
                    "sha256Checksum" to entity.sha256Checksum.take(128),
                    "uploaded" to entity.uploaded,
                    "isDeleted" to entity.isDeleted,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                entity.remoteStoragePath?.let { payload["remoteStoragePath"] = it.take(300) }
                docRef.set(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun upsertAidProgram(entity: AidProgramEntity) {
        val uid = requireUserId()
        val docRef = db.collection(COL_AID_PROGRAMS).document(entity.id)
        try {
            val exists = docExistsInScope(COL_AID_PROGRAMS, entity.id, uid)
            if (exists) {
                val updates = mapOf<String, Any>(
                    "name" to entity.name.ifBlank { "برنامج مساعدة" }.take(200),
                    "aidType" to entity.aidType.ifBlank { "عيني" }.take(80),
                    "donorName" to entity.donorName.take(150),
                    "distributionDate" to entity.distributionDate.ifBlank { "2026-01-01" }.take(40),
                    "unitLabel" to entity.unitLabel.take(60),
                    "defaultQuantity" to entity.defaultQuantity.coerceAtLeast(0.0),
                    "defaultAmount" to entity.defaultAmount.coerceAtLeast(0.0),
                    "status" to entity.status.take(60),
                    "notes" to entity.notes.take(500),
                    "isDeleted" to entity.isDeleted,
                    "syncVersion" to entity.syncVersion.coerceAtLeast(1L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            } else {
                val payload = mapOf<String, Any>(
                    "ownerUid" to uid,
                    "name" to entity.name.ifBlank { "برنامج مساعدة" }.take(200),
                    "aidType" to entity.aidType.ifBlank { "عيني" }.take(80),
                    "donorName" to entity.donorName.take(150),
                    "distributionDate" to entity.distributionDate.ifBlank { "2026-01-01" }.take(40),
                    "unitLabel" to entity.unitLabel.take(60),
                    "defaultQuantity" to entity.defaultQuantity.coerceAtLeast(0.0),
                    "defaultAmount" to entity.defaultAmount.coerceAtLeast(0.0),
                    "status" to entity.status.take(60),
                    "notes" to entity.notes.take(500),
                    "createdByName" to entity.createdByName.take(150),
                    "isDeleted" to entity.isDeleted,
                    "syncVersion" to entity.syncVersion.coerceAtLeast(1L),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun upsertAidBeneficiary(entity: AidBeneficiaryEntity) {
        val uid = requireUserId()
        val docRef = db.collection(COL_AID_BENEFICIARIES).document(entity.id)
        try {
            val exists = docExistsInScope(COL_AID_BENEFICIARIES, entity.id, uid)
            if (exists) {
                val updates = mapOf<String, Any>(
                    "allocatedQuantity" to entity.allocatedQuantity.coerceAtLeast(0.0),
                    "allocatedAmount" to entity.allocatedAmount.coerceAtLeast(0.0),
                    "isApproved" to entity.isApproved,
                    "notes" to entity.notes.take(500),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            } else {
                val payload = mapOf<String, Any>(
                    "ownerUid" to uid,
                    "programId" to entity.programId,
                    "personId" to entity.personId,
                    "allocatedQuantity" to entity.allocatedQuantity.coerceAtLeast(0.0),
                    "allocatedAmount" to entity.allocatedAmount.coerceAtLeast(0.0),
                    "isApproved" to entity.isApproved,
                    "notes" to entity.notes.take(500),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun upsertAidDistribution(entity: AidDistributionEntity) {
        val uid = requireUserId()
        val docRef = db.collection(COL_AID_DISTRIBUTIONS).document(entity.id)
        try {
            val exists = docExistsInScope(COL_AID_DISTRIBUTIONS, entity.id, uid)
            if (exists) {
                val updates = mapOf<String, Any>(
                    "quantityDelivered" to entity.quantityDelivered.coerceAtLeast(0.0),
                    "amountDelivered" to entity.amountDelivered.coerceAtLeast(0.0),
                    "receivedStatus" to entity.receivedStatus.ifBlank { "تم الاستلام" }.take(60),
                    "responsibleEmployeeName" to entity.responsibleEmployeeName.take(150),
                    "notes" to entity.notes.take(500),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            } else {
                val payload = mutableMapOf<String, Any>(
                    "ownerUid" to uid,
                    "programId" to entity.programId,
                    "personId" to entity.personId,
                    "quantityDelivered" to entity.quantityDelivered.coerceAtLeast(0.0),
                    "amountDelivered" to entity.amountDelivered.coerceAtLeast(0.0),
                    "receivedStatus" to entity.receivedStatus.ifBlank { "تم الاستلام" }.take(60),
                    "responsibleEmployeeName" to entity.responsibleEmployeeName.take(150),
                    "notes" to entity.notes.take(500),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                entity.beneficiaryId?.takeIf { it.isNotBlank() }?.let {
                    payload["beneficiaryId"] = it.take(128)
                }
                docRef.set(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun upsertSettings(entity: AppSettingsEntity) {
        val uid = requireUserId()
        val docRef = db.collection(COL_APP_SETTINGS).document("global")
        try {
            val exists = docExistsInScope(COL_APP_SETTINGS, "global", uid)
            val orgTitle = entity.organizationTitle.ifBlank { "سجل الفهد الميداني" }.take(200)
            val fontFamily = entity.headerFontFamily.ifBlank { "Cairo" }.take(60)
            val fontSize = entity.headerFontSizeSp.toDouble().coerceIn(8.0, 32.0)
            if (exists) {
                val updates = mapOf<String, Any>(
                    "organizationTitle" to orgTitle,
                    "headerSubtitle" to entity.headerSubtitle.take(250),
                    "headerRightText" to entity.headerRightText.take(400),
                    "headerLeftText" to entity.headerLeftText.take(400),
                    "headerFontFamily" to fontFamily,
                    "headerFontSizeSp" to fontSize,
                    "defaultGovernorate" to entity.defaultGovernorate.take(100),
                    "defaultDistrict" to entity.defaultDistrict.take(100),
                    "defaultPaperSize" to entity.defaultPaperSize.ifBlank { "A4" }.take(20),
                    "defaultOrientation" to entity.defaultOrientation.ifBlank { "portrait" }.take(20),
                    "autoSyncEnabled" to entity.autoSyncEnabled,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            } else {
                val payload = mapOf<String, Any>(
                    "ownerUid" to uid,
                    "organizationTitle" to orgTitle,
                    "headerSubtitle" to entity.headerSubtitle.take(250),
                    "headerRightText" to entity.headerRightText.take(400),
                    "headerLeftText" to entity.headerLeftText.take(400),
                    "headerFontFamily" to fontFamily,
                    "headerFontSizeSp" to fontSize,
                    "defaultGovernorate" to entity.defaultGovernorate.take(100),
                    "defaultDistrict" to entity.defaultDistrict.take(100),
                    "defaultPaperSize" to entity.defaultPaperSize.ifBlank { "A4" }.take(20),
                    "defaultOrientation" to entity.defaultOrientation.ifBlank { "portrait" }.take(20),
                    "autoSyncEnabled" to entity.autoSyncEnabled,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun appendAuditLog(entity: AuditLogEntity) {
        val uid = requireUserId()
        val docRef = db.collection(COL_AUDIT_LOGS).document(entity.id)
        try {
            if (!docExistsInScope(COL_AUDIT_LOGS, entity.id, uid)) {
                val payload = mapOf<String, Any>(
                    "ownerUid" to uid,
                    "actorUsername" to entity.actorUsername.ifBlank { "system" }.take(80),
                    "actionType" to entity.actionType.ifBlank { "SYNC" }.take(40),
                    "tableName" to entity.tableName.ifBlank { "general" }.take(60),
                    "recordId" to entity.recordId.take(128),
                    "summaryAr" to entity.summaryAr.ifBlank { "عملية نظام" }.take(500),
                    "createdAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }

    suspend fun upsertSyncMetadata(entity: SyncMetadataEntity) {
        val uid = requireUserId()
        val docRef = db.collection(COL_SYNC_METADATA).document(entity.id)
        val validStatuses = setOf("Pending", "Syncing", "Synced", "Failed", "Conflict")
        val safeStatus = if (entity.syncStatus in validStatuses) entity.syncStatus else "Pending"
        val safeRetry = entity.retryCount.coerceIn(0, 1000).toLong()
        try {
            val exists = docExistsInScope(COL_SYNC_METADATA, entity.id, uid)
            if (exists) {
                val updates = mutableMapOf<String, Any>(
                    "deviceId" to entity.deviceId.ifBlank { "android" }.take(128),
                    "syncStatus" to safeStatus,
                    "retryCount" to safeRetry,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                entity.lastError?.let { updates["lastError"] = it.take(300) }
                docRef.update(updates).await()
            } else {
                val payload = mutableMapOf<String, Any>(
                    "ownerUid" to uid,
                    "deviceId" to entity.deviceId.ifBlank { "android" }.take(128),
                    "entityTable" to entity.entityTable.ifBlank { "people" }.take(60),
                    "recordId" to entity.recordId.ifBlank { entity.id }.take(128),
                    "syncStatus" to safeStatus,
                    "retryCount" to safeRetry,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                entity.lastError?.let { payload["lastError"] = it.take(300) }
                docRef.set(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun syncLocalDatabase(localDb: AppDatabase): SyncSummary = withContext(Dispatchers.IO) {
        val user = auth.currentUser
            ?: return@withContext SyncSummary(
                synced = 0,
                failed = 0,
                pending = localDb.syncMetadataDao().getPendingOrFailed().size,
                message = "يرجى تسجيل الدخول بحساب Google لتفعيل المزامنة مع Firestore"
            )

        ensureUserProfile(user.displayName ?: "مستخدم سجل الفهد", user.email ?: "")

        val profileSnap = db.collection(COL_PROFILES).document(user.uid).get().await()
        val isActive = profileSnap.getBoolean("isActive") ?: false
        val isAdmin = isCurrentBootstrapAdmin() ||
            (isActive && profileSnap.getString("role") == UserRole.ADMIN.dbValue)
        val canManageAreas = isAdmin || (isActive && (profileSnap.getBoolean("canManageAreas") ?: false))
        val canManageAid = isAdmin || (isActive && (profileSnap.getBoolean("canManageAid") ?: false))
        val canAddCitizen = isAdmin || (isActive && (profileSnap.getBoolean("canAddCitizen") ?: false))
        val canEditCitizen = isAdmin || (isActive && (profileSnap.getBoolean("canEditCitizen") ?: false))

        var synced = 0
        var failed = 0

        // 1. profiles (Admin only for full directory)
        if (isAdmin) {
            for (prof in localDb.profileDao().getAllOnce()) {
                if (prof.id == user.uid) continue
                try {
                    upsertProfile(prof, prof.email)
                    synced++
                } catch (_: Exception) {
                    failed++
                }
            }
        }

        // 2. neighborhoods
        if (canManageAreas) {
            for (n in localDb.neighborhoodDao().getAllRaw()) {
                try {
                    upsertNeighborhood(n)
                    synced++
                } catch (_: Exception) {
                    failed++
                }
            }

            // 3. residential_squares (depends on neighborhoods)
            for (s in localDb.residentialSquareDao().getAllRaw()) {
                try {
                    upsertSquare(s)
                    synced++
                } catch (_: Exception) {
                    failed++
                }
            }

            // 4. representatives (depends on neighborhoods)
            for (rep in localDb.representativeHistoryDao().getAllRaw()) {
                try {
                    upsertRepresentativeHistory(rep)
                    synced++
                } catch (_: Exception) {
                    failed++
                }
            }
        }

        // 5. people (depends on neighborhoods + residential_squares)
        if (canAddCitizen || canEditCitizen) {
            for (p in localDb.personDao().getAllRaw()) {
                try {
                    val existsCloud = docExistsInScope(COL_PEOPLE, p.id, user.uid)
                    if ((!existsCloud && canAddCitizen) || (existsCloud && canEditCitizen)) {
                        upsertCitizen(p)
                        synced++
                    }
                } catch (_: Exception) {
                    failed++
                }
            }

            // 6. identity_documents (depends on people; updates reserved for Admin)
            for (doc in localDb.identityDocumentDao().getAllRaw()) {
                try {
                    val existsCloud = docExistsInScope(COL_IDENTITY_DOCUMENTS, doc.id, user.uid)
                    if ((!existsCloud && canAddCitizen) || (existsCloud && isAdmin)) {
                        upsertIdentityDocument(doc)
                        synced++
                    }
                } catch (_: Exception) {
                    failed++
                }
            }
        }

        // 7-9. aid_programs, aid_beneficiaries, aid_distributions
        if (canManageAid) {
            for (prog in localDb.aidDao().getAllProgramsRaw()) {
                try {
                    upsertAidProgram(prog)
                    synced++
                } catch (_: Exception) {
                    failed++
                }
            }

            for (ben in localDb.aidDao().getAllBeneficiariesRaw()) {
                try {
                    upsertAidBeneficiary(ben)
                    synced++
                } catch (_: Exception) {
                    failed++
                }
            }

            for (dist in localDb.aidDao().getAllDistributionsRaw()) {
                try {
                    upsertAidDistribution(dist)
                    synced++
                } catch (_: Exception) {
                    failed++
                }
            }
        }

        // 10. app_settings (Admin only)
        if (isAdmin) {
            localDb.appSettingsDao().getSettingsOnce()?.let { s ->
                try {
                    upsertSettings(s)
                    synced++
                } catch (_: Exception) {
                    failed++
                }
            }
        }

        // 11. audit_logs (append-only)
        for (log in localDb.auditLogDao().getAllRaw().take(200)) {
            try {
                appendAuditLog(log)
                synced++
            } catch (_: Exception) {
                failed++
            }
        }

        if (failed == 0) {
            val pending = localDb.syncMetadataDao().getPendingOrFailed()
            val now = System.currentTimeMillis()
            pending.forEach { item ->
                localDb.syncMetadataDao().updateStatus(
                    item.id,
                    SyncStatus.SYNCED.dbValue,
                    now,
                    null
                )
            }
        }

        // 12. sync_metadata
        for (meta in localDb.syncMetadataDao().getAllRaw()) {
            try {
                upsertSyncMetadata(meta)
                synced++
            } catch (_: Exception) {
                failed++
            }
        }

        SyncSummary(
            synced = synced,
            failed = failed,
            pending = localDb.syncMetadataDao().getPendingOrFailed().size,
            message = "اكتملت مزامنة Firebase Firestore: تم رفع $synced سجل" +
                if (failed > 0) " (تعذر $failed)" else ""
        )
    }
}
