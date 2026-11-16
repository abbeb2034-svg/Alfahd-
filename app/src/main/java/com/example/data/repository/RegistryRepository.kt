package com.example.data.repository

import android.content.Context
import android.provider.Settings
import androidx.room.withTransaction
import com.example.data.local.*
import com.example.data.remote.FirestoreCloudRepository
import com.example.export.CitizenImportField
import com.example.export.ExcelCitizensImporter
import com.example.export.ExcelImportResult
import com.example.util.SecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class RegistryRepository(
    private val context: Context,
    private val db: AppDatabase
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val deviceId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "android-device"
    }

    // Reactive Streams
    val profilesFlow: Flow<List<ProfileEntity>> = db.profileDao().observeAll()
    val neighborhoodsFlow: Flow<List<NeighborhoodEntity>> = db.neighborhoodDao().observeAllActive()
    val squaresFlow: Flow<List<ResidentialSquareEntity>> = db.residentialSquareDao().observeAllActive()
    val repHistoryFlow: Flow<List<RepresentativeHistoryEntity>> = db.representativeHistoryDao().observeAll()
    val peopleFlow: Flow<List<PersonEntity>> = db.personDao().observeAllActive()
    val totalPeopleCountFlow: Flow<Int> = db.personDao().observeTotalCount()
    val totalFamilyMembersFlow: Flow<Int> = db.personDao().observeTotalFamilyMembers()
    val neighborhoodCountFlow: Flow<Int> = db.neighborhoodDao().observeCount()
    val squareCountFlow: Flow<Int> = db.residentialSquareDao().observeCount()
    val aidProgramsFlow: Flow<List<AidProgramEntity>> = db.aidDao().observePrograms()
    val allDistributionsFlow: Flow<List<AidDistributionEntity>> = db.aidDao().observeAllDistributions()
    val settingsFlow: Flow<AppSettingsEntity?> = db.appSettingsDao().observeSettings()
    val auditLogsFlow: Flow<List<AuditLogEntity>> = db.auditLogDao().observeRecent()
    val syncMetadataFlow: Flow<List<SyncMetadataEntity>> = db.syncMetadataDao().observeAll()
    val pendingSyncCountFlow: Flow<Int> = db.syncMetadataDao().observePendingCount()

    fun observeSquaresByNeighborhood(neighborhoodId: String): Flow<List<ResidentialSquareEntity>> =
        db.residentialSquareDao().observeByNeighborhood(neighborhoodId)

    fun observePerson(id: String): Flow<PersonEntity?> = db.personDao().observeById(id)

    fun observePersonDocuments(personId: String): Flow<List<IdentityDocumentEntity>> =
        db.identityDocumentDao().observeForPerson(personId)

    fun observePersonDistributions(personId: String): Flow<List<AidDistributionEntity>> =
        db.aidDao().observeDistributionsForPerson(personId)

    fun observeProgramBeneficiaries(programId: String): Flow<List<AidBeneficiaryEntity>> =
        db.aidDao().observeBeneficiaries(programId)

    fun observeProgramDistributions(programId: String): Flow<List<AidDistributionEntity>> =
        db.aidDao().observeDistributionsForProgram(programId)

    suspend fun initializeDatabase() = withContext(Dispatchers.IO) {
        DatabaseSeeder.ensureSeeded(db)
    }

    // Authentication
    suspend fun authenticate(usernameOrEmail: String, password: String): ProfileEntity =
        withContext(Dispatchers.IO) {
            val cleanUser = usernameOrEmail.trim()
            val profile = db.profileDao().findByUsername(cleanUser)
                ?: db.profileDao().findByEmail(cleanUser)
                ?: throw IllegalArgumentException("اسم المستخدم أو كلمة المرور غير صحيحة")
            if (!profile.isActive) {
                throw IllegalStateException("هذا الحساب معطّل. يرجى مراجعة المدير")
            }
            if (!SecurityUtils.verifyPassword(password, profile.passwordHash)) {
                throw IllegalArgumentException("اسم المستخدم أو كلمة المرور غير صحيحة")
            }
            val updated = profile.copy(lastLoginAt = System.currentTimeMillis())
            db.profileDao().upsert(updated)
            recordAudit(
                actor = updated,
                action = "LOGIN",
                table = "profiles",
                recordId = updated.id,
                summary = "تسجيل دخول المستخدم ${updated.fullName} (${updated.username})"
            )
            updated
        }

    suspend fun loginAsVerifiedCloudAdmin(email: String, displayName: String): ProfileEntity =
        withContext(Dispatchers.IO) {
            val cleanEmail = email.trim()
            if (!cleanEmail.equals(FirestoreCloudRepository.BOOTSTRAP_ADMIN_EMAIL, ignoreCase = true)) {
                throw SecurityException("هذا البريد ليس مدير النظام المعتمد")
            }
            val adminPerms = json.encodeToString(UserPermissions.forRole(UserRole.ADMIN))
            val existing = db.profileDao().findByEmail(cleanEmail)
                ?: db.profileDao().findByUsername("abbeb34")

            val adminProfile = if (existing != null) {
                existing.copy(
                    email = cleanEmail,
                    fullName = existing.fullName.ifBlank { displayName.ifBlank { "مدير النظام الرئيسي" } },
                    role = UserRole.ADMIN.dbValue,
                    permissionsJson = adminPerms,
                    isActive = true,
                    lastLoginAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                ProfileEntity(
                    id = SecurityUtils.newUuid(),
                    username = "abbeb34",
                    fullName = displayName.ifBlank { "مدير النظام الرئيسي" },
                    email = cleanEmail,
                    role = UserRole.ADMIN.dbValue,
                    passwordHash = SecurityUtils.hashPassword("Aa0012365400"),
                    permissionsJson = adminPerms,
                    isActive = true,
                    lastLoginAt = System.currentTimeMillis()
                )
            }
            db.profileDao().upsert(adminProfile)
            recordAudit(
                actor = adminProfile,
                action = "LOGIN",
                table = "profiles",
                recordId = adminProfile.id,
                summary = "دخول مدير النظام الموثق ($cleanEmail)"
            )
            adminProfile
        }

    fun parsePermissions(profile: ProfileEntity): UserPermissions {
        return try {
            if (profile.permissionsJson.isBlank() || profile.permissionsJson == "{}") {
                UserPermissions.forRole(UserRole.fromDb(profile.role))
            } else {
                json.decodeFromString<UserPermissions>(profile.permissionsJson)
            }
        } catch (_: Exception) {
            UserPermissions.forRole(UserRole.fromDb(profile.role))
        }
    }

    // Users Management (Admin or delegated canManageUsers)
    suspend fun saveUser(
        actor: ProfileEntity,
        existingId: String?,
        username: String,
        fullName: String,
        phone: String,
        role: UserRole,
        plainPassword: String?,
        permissions: UserPermissions,
        isActive: Boolean,
        email: String = ""
    ): ProfileEntity = withContext(Dispatchers.IO) {
        val actorIsAdmin = UserRole.fromDb(actor.role) == UserRole.ADMIN
        val actorPerms = parsePermissions(actor)
        if (!actorIsAdmin && !actorPerms.canManageUsers) {
            throw SecurityException("لا تملك صلاحية إدارة المستخدمين وتحديد الصلاحيات")
        }
        val cleanUsername = username.trim()
        val cleanEmail = email.trim()
        require(cleanUsername.isNotEmpty()) { "يرجى إدخال اسم المستخدم" }
        require(fullName.trim().isNotEmpty()) { "يرجى إدخال الاسم الكامل" }

        val duplicate = db.profileDao().findByUsername(cleanUsername)
        if (duplicate != null && duplicate.id != existingId) {
            throw IllegalStateException("اسم المستخدم مسجل مسبقاً")
        }
        if (cleanEmail.isNotEmpty()) {
            val emailDup = db.profileDao().findByEmail(cleanEmail)
            if (emailDup != null && emailDup.id != existingId) {
                throw IllegalStateException("البريد الإلكتروني مسجل لمستخدم آخر")
            }
        }

        val existing = existingId?.let { db.profileDao().findById(it) }
        if (!actorIsAdmin) {
            if (role == UserRole.ADMIN || existing?.role == UserRole.ADMIN.dbValue) {
                throw SecurityException("تعديل أو إنشاء حساب مدير النظام متاح لمدير النظام فقط")
            }
            if (permissions.canManageUsers) {
                throw SecurityException("منح صلاحية إدارة المستخدمين متاح لمدير النظام فقط")
            }
        }

        val passwordHash = when {
            !plainPassword.isNullOrBlank() -> SecurityUtils.hashPassword(plainPassword)
            existing != null -> existing.passwordHash
            else -> throw IllegalArgumentException("يرجى إدخال كلمة المرور للمستخدم الجديد")
        }

        val profile = ProfileEntity(
            id = existing?.id ?: SecurityUtils.newUuid(),
            username = cleanUsername,
            fullName = fullName.trim(),
            email = cleanEmail,
            phone = phone.trim(),
            role = role.dbValue,
            passwordHash = passwordHash,
            permissionsJson = json.encodeToString(permissions),
            isActive = isActive,
            lastLoginAt = existing?.lastLoginAt,
            createdBy = existing?.createdBy ?: actor.id,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            syncStatus = SyncStatus.PENDING.dbValue,
            syncVersion = (existing?.syncVersion ?: 0L) + 1L
        )
        db.profileDao().upsert(profile)
        markSyncPending("profiles", profile.id)
        recordAudit(
            actor = actor,
            action = if (existing == null) "INSERT" else "UPDATE",
            table = "profiles",
            recordId = profile.id,
            summary = if (existing == null) "إضافة مستخدم جديد: ${profile.fullName}" else "تعديل بيانات المستخدم: ${profile.fullName}"
        )
        profile
    }

    // Citizens (People) CRUD + Pagination + Duplicate Prevention
    suspend fun searchPeoplePaged(
        query: String = "",
        neighborhoodId: String = "",
        squareId: String = "",
        housingType: String = "",
        healthStatus: String = "",
        limit: Int = 50,
        offset: Int = 0
    ): List<PersonEntity> = withContext(Dispatchers.IO) {
        db.personDao().searchPaged(
            query = query.trim(),
            neighborhoodId = neighborhoodId,
            squareId = squareId,
            housingType = housingType,
            healthStatus = healthStatus.trim(),
            limit = limit,
            offset = offset
        )
    }

    suspend fun getAllActivePeople(): List<PersonEntity> = withContext(Dispatchers.IO) {
        db.personDao().getAllActiveOnce()
    }

    suspend fun getPeopleWithoutAid(): List<PersonEntity> = withContext(Dispatchers.IO) {
        db.personDao().getCitizensWithoutAid()
    }

    suspend fun saveCitizen(
        actor: ProfileEntity,
        existingId: String?,
        personDraft: PersonEntity,
        documents: List<IdentityDocumentEntity>
    ): PersonEntity = withContext(Dispatchers.IO) {
        val perms = parsePermissions(actor)
        val isUpdate = existingId != null && db.personDao().findById(existingId) != null
        if (isUpdate) {
            if (!perms.canEditCitizen && UserRole.fromDb(actor.role) != UserRole.ADMIN) {
                throw SecurityException("صلاحيات الموظف لا تسمح بتعديل السجلات الموجودة")
            }
        } else {
            if (!perms.canAddCitizen && UserRole.fromDb(actor.role) != UserRole.ADMIN) {
                throw SecurityException("لا تملك صلاحية إضافة مواطن")
            }
        }

        val cleanName = personDraft.fullName.trim()
        val cleanNationalId = personDraft.nationalId.trim()
        require(cleanName.isNotEmpty()) { "الاسم الكامل مطلوب" }
        require(personDraft.neighborhoodId.isNotEmpty()) { "يرجى اختيار الحارة أولاً" }
        require(personDraft.squareId.isNotEmpty()) { "يرجى اختيار المربع السكني" }

        // Prevent accidental duplicate National IDs
        if (cleanNationalId.isNotEmpty()) {
            val dup = db.personDao().findActiveByNationalId(personDraft.idType, cleanNationalId)
            if (dup != null && dup.id != existingId) {
                throw IllegalStateException("رقم الهوية ($cleanNationalId) مسجل مسبقاً باسم: ${dup.fullName}")
            }
        }

        val existing = existingId?.let { db.personDao().findById(it) }
        val personId = existing?.id ?: personDraft.id.ifBlank { SecurityUtils.newUuid() }
        val now = System.currentTimeMillis()
        val frontPhoto = documents.firstOrNull { it.side == "front" || it.side == "single" }?.localFilePath
            ?: existing?.photoLocalPath

        val entity = personDraft.copy(
            id = personId,
            citizenCode = existing?.citizenCode ?: personDraft.citizenCode.ifBlank { SecurityUtils.generateCitizenCode() },
            fullName = cleanName,
            nationalId = cleanNationalId,
            photoLocalPath = frontPhoto,
            createdBy = existing?.createdBy ?: actor.id,
            createdByName = existing?.createdByName?.ifBlank { actor.fullName } ?: actor.fullName,
            updatedBy = if (isUpdate) actor.id else existing?.updatedBy,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            syncStatus = SyncStatus.PENDING.dbValue,
            syncVersion = (existing?.syncVersion ?: 0L) + 1L
        )
        db.personDao().upsert(entity)
        markSyncPending("people", entity.id)

        if (documents.isNotEmpty()) {
            db.identityDocumentDao().deleteAllForPerson(personId)
            val savedDocs = documents.map { doc ->
                doc.copy(
                    id = doc.id.ifBlank { SecurityUtils.newUuid() },
                    personId = personId,
                    createdBy = doc.createdBy ?: actor.id,
                    updatedAt = now,
                    syncStatus = SyncStatus.PENDING.dbValue
                )
            }
            db.identityDocumentDao().upsertAll(savedDocs)
            savedDocs.forEach { markSyncPending("identity_documents", it.id) }
        }

        recordAudit(
            actor = actor,
            action = if (isUpdate) "UPDATE" else "INSERT",
            table = "people",
            recordId = entity.id,
            summary = if (isUpdate) "تعديل بيانات المواطن: ${entity.fullName}" else "إضافة مواطن جديد: ${entity.fullName} (${entity.citizenCode})"
        )
        entity
    }

    suspend fun deleteCitizen(actor: ProfileEntity, personId: String) = withContext(Dispatchers.IO) {
        val perms = parsePermissions(actor)
        if (!perms.canDeleteCitizen && UserRole.fromDb(actor.role) != UserRole.ADMIN) {
            throw SecurityException("صلاحيات الموظف لا تسمح بحذف السجلات الموجودة")
        }
        val person = db.personDao().findById(personId) ?: return@withContext
        db.personDao().softDelete(personId, actor.id)
        markSyncPending("people", personId)
        recordAudit(
            actor = actor,
            action = "DELETE",
            table = "people",
            recordId = personId,
            summary = "حذف سجل المواطن: ${person.fullName}"
        )
    }

    suspend fun importCitizensFromExcel(
        actor: ProfileEntity,
        rows: List<List<String>>,
        headerRowIndex: Int,
        mapping: Map<CitizenImportField, Int?>,
        defaultNeighborhoodId: String,
        defaultSquareId: String,
        createMissingAreas: Boolean = true,
        skipDuplicateIds: Boolean = true,
        updateExistingOnDuplicateId: Boolean = false
    ): ExcelImportResult = withContext(Dispatchers.IO) {
        val perms = parsePermissions(actor)
        if (!perms.canAddCitizen && UserRole.fromDb(actor.role) != UserRole.ADMIN) {
            throw SecurityException("لا تملك صلاحية إضافة أو استيراد المواطنين")
        }
        val nameCol = mapping[CitizenImportField.FULL_NAME]
            ?: throw IllegalArgumentException("يجب تحديد العمود المقابل لـ (الاسم الكامل) في رؤوس الجدول")
        require(headerRowIndex in rows.indices) { "رقم صف رؤوس الجدول غير صالح" }

        val settings = db.appSettingsDao().getSettingsOnce()
        val defaultGov = settings?.defaultGovernorate?.ifBlank { "عدن" } ?: "عدن"
        val defaultDist = settings?.defaultDistrict?.ifBlank { "المنصورة" } ?: "المنصورة"

        var importedCount = 0
        var updatedCount = 0
        var skippedDuplicates = 0
        var skippedInvalid = 0
        var createdAreasCount = 0
        val errors = mutableListOf<String>()

        fun addError(msg: String) {
            if (errors.size < 25) errors += msg
        }

        fun cell(row: List<String>, field: CitizenImportField): String {
            val col = mapping[field] ?: return ""
            return row.getOrElse(col) { "" }.trim()
        }

        db.withTransaction {
            val neighborhoodsByNorm = db.neighborhoodDao().getAllActiveOnce()
                .associateBy { ExcelCitizensImporter.normalizeText(it.name) }
                .toMutableMap()
            val neighborhoodsById = neighborhoodsByNorm.values.associateBy { it.id }.toMutableMap()

            val allSquares = db.residentialSquareDao().getAllActiveOnce().toMutableList()
            val squaresByNeigh = allSquares.groupBy { it.neighborhoodId }
                .mapValues { it.value.toMutableList() }
                .toMutableMap()

            val existingPeople = db.personDao().getAllActiveOnce()
            val existingByNationalKey = existingPeople
                .filter { it.nationalId.isNotBlank() }
                .associateBy { "${it.idType}::${it.nationalId.trim()}" }
                .toMutableMap()
            val existingCodes = existingPeople.map { it.citizenCode }.toMutableSet()

            val peopleToInsert = mutableListOf<PersonEntity>()
            val now = System.currentTimeMillis()

            for (rIdx in (headerRowIndex + 1) until rows.size) {
                val row = rows[rIdx]
                if (row.all { it.isBlank() }) continue

                val fullName = row.getOrElse(nameCol) { "" }.trim()
                if (fullName.isEmpty()) {
                    skippedInvalid++
                    addError("صف ${rIdx + 1}: الاسم الكامل فارغ")
                    continue
                }

                val gov = cell(row, CitizenImportField.GOVERNORATE).ifBlank { defaultGov }
                val dist = cell(row, CitizenImportField.DISTRICT).ifBlank { defaultDist }

                // Resolve neighborhood
                val neighNameRaw = cell(row, CitizenImportField.NEIGHBORHOOD)
                var targetNeigh: NeighborhoodEntity? = if (neighNameRaw.isNotEmpty()) {
                    val norm = ExcelCitizensImporter.normalizeText(neighNameRaw)
                    neighborhoodsByNorm[norm] ?: if (createMissingAreas) {
                        val createdN = NeighborhoodEntity(
                            id = SecurityUtils.newUuid(),
                            name = neighNameRaw,
                            governorate = gov,
                            district = dist,
                            createdBy = actor.id,
                            createdByName = actor.fullName,
                            createdAt = now,
                            updatedAt = now
                        )
                        db.neighborhoodDao().upsert(createdN)
                        markSyncPending("neighborhoods", createdN.id)
                        neighborhoodsByNorm[norm] = createdN
                        neighborhoodsById[createdN.id] = createdN
                        createdAreasCount++
                        createdN
                    } else {
                        neighborhoodsById[defaultNeighborhoodId]
                    }
                } else {
                    neighborhoodsById[defaultNeighborhoodId] ?: neighborhoodsById.values.firstOrNull()
                }

                if (targetNeigh == null) {
                    skippedInvalid++
                    addError("صف ${rIdx + 1}: تعذر تحديد الحارة للمواطن ($fullName)")
                    continue
                }

                // Resolve square inside targetNeigh
                val squareNameRaw = cell(row, CitizenImportField.SQUARE)
                val neighSquares = squaresByNeigh.getOrPut(targetNeigh.id) { mutableListOf() }
                var targetSquare: ResidentialSquareEntity? = if (squareNameRaw.isNotEmpty()) {
                    val normSq = ExcelCitizensImporter.normalizeText(squareNameRaw)
                    neighSquares.firstOrNull { ExcelCitizensImporter.normalizeText(it.name) == normSq }
                        ?: if (createMissingAreas) {
                            val createdSq = ResidentialSquareEntity(
                                id = SecurityUtils.newUuid(),
                                neighborhoodId = targetNeigh.id,
                                name = squareNameRaw,
                                squareCode = "SQ-${neighSquares.size + 1}",
                                createdBy = actor.id,
                                createdByName = actor.fullName,
                                createdAt = now,
                                updatedAt = now
                            )
                            db.residentialSquareDao().upsert(createdSq)
                            markSyncPending("residential_squares", createdSq.id)
                            neighSquares += createdSq
                            createdAreasCount++
                            createdSq
                        } else {
                            neighSquares.firstOrNull { it.id == defaultSquareId } ?: neighSquares.firstOrNull()
                        }
                } else {
                    neighSquares.firstOrNull { it.id == defaultSquareId } ?: neighSquares.firstOrNull()
                }

                if (targetSquare == null && createMissingAreas) {
                    val fallbackSq = ResidentialSquareEntity(
                        id = SecurityUtils.newUuid(),
                        neighborhoodId = targetNeigh.id,
                        name = "مربع (1)",
                        squareCode = "SQ-1",
                        createdBy = actor.id,
                        createdByName = actor.fullName,
                        createdAt = now,
                        updatedAt = now
                    )
                    db.residentialSquareDao().upsert(fallbackSq)
                    markSyncPending("residential_squares", fallbackSq.id)
                    neighSquares += fallbackSq
                    createdAreasCount++
                    targetSquare = fallbackSq
                }

                if (targetSquare == null) {
                    skippedInvalid++
                    addError("صف ${rIdx + 1}: لا يوجد مربع سكني في الحارة (${targetNeigh.name})")
                    continue
                }

                val rawIdType = cell(row, CitizenImportField.ID_TYPE)
                val idType = if (rawIdType.isNotEmpty()) {
                    DocumentType.entries.firstOrNull {
                        ExcelCitizensImporter.normalizeText(it.arabicLabel) == ExcelCitizensImporter.normalizeText(rawIdType)
                    }?.arabicLabel ?: rawIdType
                } else {
                    DocumentType.NATIONAL_ID.arabicLabel
                }
                val nationalId = cell(row, CitizenImportField.NATIONAL_ID)
                val idIssueDate = ExcelCitizensImporter.formatExcelDateIfSerial(cell(row, CitizenImportField.ID_ISSUE_DATE))
                val displacementDate = ExcelCitizensImporter.formatExcelDateIfSerial(cell(row, CitizenImportField.DISPLACEMENT_DATE))
                val lastAidDate = ExcelCitizensImporter.formatExcelDateIfSerial(cell(row, CitizenImportField.LAST_AID_DATE))

                val natKey = "$idType::$nationalId"
                val existingMatch = if (nationalId.isNotEmpty()) existingByNationalKey[natKey] else null
                if (existingMatch != null) {
                    if (updateExistingOnDuplicateId && (perms.canEditCitizen || UserRole.fromDb(actor.role) == UserRole.ADMIN)) {
                        val rawFamily = cell(row, CitizenImportField.FAMILY_SIZE)
                            .filter { it.isDigit() }
                            .toIntOrNull()
                            ?.coerceIn(1, 100) ?: existingMatch.familySize
                        val rawHousing = cell(row, CitizenImportField.HOUSING_TYPE)
                        val housingType = when {
                            rawHousing.contains("ملك") || rawHousing.contains("مالك") -> HousingType.OWNER.arabicLabel
                            rawHousing.contains("ايجار") || rawHousing.contains("مستأجر") || rawHousing.contains("مؤجر") -> HousingType.TENANT.arabicLabel
                            rawHousing.isNotBlank() -> rawHousing
                            else -> existingMatch.housingType
                        }
                        val updatedPerson = existingMatch.copy(
                            fullName = fullName,
                            idIssueDate = idIssueDate.ifBlank { existingMatch.idIssueDate },
                            phone = cell(row, CitizenImportField.PHONE).ifBlank { existingMatch.phone },
                            governorate = gov,
                            district = dist,
                            neighborhoodId = targetNeigh.id,
                            squareId = targetSquare.id,
                            occupation = cell(row, CitizenImportField.OCCUPATION).ifBlank { existingMatch.occupation },
                            familySize = rawFamily,
                            housingType = housingType,
                            housingStatus = cell(row, CitizenImportField.HOUSING_STATUS).ifBlank { existingMatch.housingStatus },
                            displacementDate = displacementDate.ifBlank { existingMatch.displacementDate },
                            healthStatus = cell(row, CitizenImportField.HEALTH_STATUS).ifBlank { existingMatch.healthStatus },
                            healthDetails = cell(row, CitizenImportField.HEALTH_DETAILS).ifBlank { existingMatch.healthDetails },
                            lastAidDate = lastAidDate.ifBlank { existingMatch.lastAidDate },
                            notes = cell(row, CitizenImportField.NOTES).ifBlank { existingMatch.notes },
                            updatedBy = actor.id,
                            updatedAt = now,
                            syncStatus = SyncStatus.PENDING.dbValue,
                            syncVersion = existingMatch.syncVersion + 1L
                        )
                        existingByNationalKey[natKey] = updatedPerson
                        peopleToInsert += updatedPerson
                        updatedCount++
                        continue
                    } else if (skipDuplicateIds) {
                        skippedDuplicates++
                        addError("صف ${rIdx + 1}: رقم الهوية ($nationalId) مسجل مسبقاً")
                        continue
                    }
                }

                var code = cell(row, CitizenImportField.CITIZEN_CODE)
                if (code.isBlank() || code in existingCodes) {
                    do {
                        code = SecurityUtils.generateCitizenCode()
                    } while (code in existingCodes)
                }
                existingCodes += code

                val rawFamily = cell(row, CitizenImportField.FAMILY_SIZE)
                    .filter { it.isDigit() }
                    .toIntOrNull()
                    ?.coerceIn(1, 100) ?: 1

                val rawHousing = cell(row, CitizenImportField.HOUSING_TYPE)
                val housingType = when {
                    rawHousing.contains("ملك") || rawHousing.contains("مالك") -> HousingType.OWNER.arabicLabel
                    rawHousing.contains("ايجار") || rawHousing.contains("مستأجر") || rawHousing.contains("مؤجر") -> HousingType.TENANT.arabicLabel
                    rawHousing.isNotBlank() -> rawHousing
                    else -> HousingType.TENANT.arabicLabel
                }

                val person = PersonEntity(
                    id = SecurityUtils.newUuid(),
                    citizenCode = code,
                    fullName = fullName,
                    idType = idType,
                    nationalId = nationalId,
                    idIssueDate = idIssueDate,
                    phone = cell(row, CitizenImportField.PHONE),
                    governorate = gov,
                    district = dist,
                    neighborhoodId = targetNeigh.id,
                    squareId = targetSquare.id,
                    occupation = cell(row, CitizenImportField.OCCUPATION),
                    familySize = rawFamily,
                    housingType = housingType,
                    housingStatus = cell(row, CitizenImportField.HOUSING_STATUS).ifBlank { "مستقر" },
                    displacementDate = displacementDate,
                    healthStatus = cell(row, CitizenImportField.HEALTH_STATUS).ifBlank { "سليم" },
                    healthDetails = cell(row, CitizenImportField.HEALTH_DETAILS),
                    lastAidDate = lastAidDate,
                    notes = cell(row, CitizenImportField.NOTES),
                    createdBy = actor.id,
                    createdByName = actor.fullName,
                    createdAt = now,
                    updatedAt = now,
                    syncStatus = SyncStatus.PENDING.dbValue
                )
                if (nationalId.isNotEmpty()) {
                    existingByNationalKey[natKey] = person
                }
                peopleToInsert += person
                importedCount++
            }

            if (peopleToInsert.isNotEmpty()) {
                peopleToInsert.chunked(200).forEach { batch ->
                    db.personDao().upsertAll(batch)
                }
                peopleToInsert.forEach { p ->
                    markSyncPending("people", p.id)
                }
            }
        }

        recordAudit(
            actor = actor,
            action = "IMPORT",
            table = "people",
            recordId = "excel_batch_${System.currentTimeMillis()}",
            summary = "استيراد ملف إكسل: إدخال $importedCount، تحديث $updatedCount، تجاهل مكرر: $skippedDuplicates، أخطاء: $skippedInvalid"
        )

        ExcelImportResult(
            importedCount = importedCount,
            skippedDuplicates = skippedDuplicates,
            skippedInvalid = skippedInvalid,
            createdAreasCount = createdAreasCount,
            sampleErrors = errors,
            updatedCount = updatedCount
        )
    }

    // Neighborhoods & Residential Squares & Representative History
    suspend fun saveNeighborhood(
        actor: ProfileEntity,
        existingId: String?,
        name: String,
        governorate: String,
        district: String,
        aqilProfile: ProfileEntity?,
        aqilNameFallback: String,
        aqilPhoneFallback: String,
        notes: String
    ): NeighborhoodEntity = withContext(Dispatchers.IO) {
        requireAdminOrAreaManager(actor)
        require(name.trim().isNotEmpty()) { "اسم الحارة مطلوب" }
        val existing = existingId?.let { db.neighborhoodDao().findById(it) }
        val resolvedName = aqilProfile?.fullName ?: aqilNameFallback.trim()
        val resolvedPhone = aqilProfile?.phone?.ifBlank { aqilPhoneFallback } ?: aqilPhoneFallback.trim()

        val entity = NeighborhoodEntity(
            id = existing?.id ?: SecurityUtils.newUuid(),
            name = name.trim(),
            governorate = governorate.trim(),
            district = district.trim(),
            aqilProfileId = aqilProfile?.id,
            aqilName = resolvedName,
            aqilPhone = resolvedPhone,
            notes = notes.trim(),
            createdBy = existing?.createdBy ?: actor.id,
            createdByName = existing?.createdByName?.ifBlank { actor.fullName } ?: actor.fullName,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            syncStatus = SyncStatus.PENDING.dbValue,
            syncVersion = (existing?.syncVersion ?: 0L) + 1L
        )
        db.neighborhoodDao().upsert(entity)
        markSyncPending("neighborhoods", entity.id)

        // Preserve historical aqil assignment when changed
        if (aqilProfile != null && existing?.aqilProfileId != aqilProfile.id) {
            db.representativeHistoryDao().closeCurrentAssignment("aqil", entity.id, null)
            val hist = RepresentativeHistoryEntity(
                id = SecurityUtils.newUuid(),
                profileId = aqilProfile.id,
                roleType = "aqil",
                neighborhoodId = entity.id,
                squareId = null,
                fullName = aqilProfile.fullName,
                phone = resolvedPhone,
                isCurrent = true,
                assignedBy = actor.id,
                notes = "تعيين عاقل الحارة: ${entity.name}"
            )
            db.representativeHistoryDao().insert(hist)
            markSyncPending("representatives", hist.id)
        }

        recordAudit(
            actor = actor,
            action = if (existing == null) "INSERT" else "UPDATE",
            table = "neighborhoods",
            recordId = entity.id,
            summary = if (existing == null) "إضافة حارة جديدة: ${entity.name}" else "تحديث الحارة: ${entity.name}"
        )
        entity
    }

    suspend fun saveResidentialSquare(
        actor: ProfileEntity,
        existingId: String?,
        neighborhoodId: String,
        name: String,
        squareCode: String,
        repProfile: ProfileEntity?,
        repNameFallback: String,
        repPhoneFallback: String,
        notes: String
    ): ResidentialSquareEntity = withContext(Dispatchers.IO) {
        requireAdminOrAreaManager(actor)
        require(neighborhoodId.isNotEmpty()) { "يرجى اختيار الحارة" }
        require(name.trim().isNotEmpty()) { "اسم المربع السكني مطلوب" }

        val existing = existingId?.let { db.residentialSquareDao().findById(it) }
        val resolvedName = repProfile?.fullName ?: repNameFallback.trim()
        val resolvedPhone = repProfile?.phone?.ifBlank { repPhoneFallback } ?: repPhoneFallback.trim()

        val entity = ResidentialSquareEntity(
            id = existing?.id ?: SecurityUtils.newUuid(),
            neighborhoodId = neighborhoodId,
            name = name.trim(),
            squareCode = squareCode.trim(),
            representativeProfileId = repProfile?.id,
            representativeName = resolvedName,
            representativePhone = resolvedPhone,
            notes = notes.trim(),
            createdBy = existing?.createdBy ?: actor.id,
            createdByName = existing?.createdByName?.ifBlank { actor.fullName } ?: actor.fullName,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            syncStatus = SyncStatus.PENDING.dbValue,
            syncVersion = (existing?.syncVersion ?: 0L) + 1L
        )
        db.residentialSquareDao().upsert(entity)
        markSyncPending("residential_squares", entity.id)

        // Preserve historical representative assignment without losing past records
        if (repProfile != null && existing?.representativeProfileId != repProfile.id) {
            db.representativeHistoryDao().closeCurrentAssignment("representative", neighborhoodId, entity.id)
            val hist = RepresentativeHistoryEntity(
                id = SecurityUtils.newUuid(),
                profileId = repProfile.id,
                roleType = "representative",
                neighborhoodId = neighborhoodId,
                squareId = entity.id,
                fullName = repProfile.fullName,
                phone = resolvedPhone,
                isCurrent = true,
                assignedBy = actor.id,
                notes = "تعيين مندوب المربع: ${entity.name}"
            )
            db.representativeHistoryDao().insert(hist)
            markSyncPending("representatives", hist.id)
        }

        recordAudit(
            actor = actor,
            action = if (existing == null) "INSERT" else "UPDATE",
            table = "residential_squares",
            recordId = entity.id,
            summary = if (existing == null) "إضافة مربع سكني: ${entity.name}" else "تحديث المربع السكني: ${entity.name}"
        )
        entity
    }

    // Aid Programs, Beneficiaries, and Duplicate-Safe Distributions
    suspend fun saveAidProgram(
        actor: ProfileEntity,
        program: AidProgramEntity
    ): AidProgramEntity = withContext(Dispatchers.IO) {
        val perms = parsePermissions(actor)
        if (!perms.canManageAid && UserRole.fromDb(actor.role) != UserRole.ADMIN) {
            throw SecurityException("لا تملك صلاحية إدارة برامج المساعدات")
        }
        require(program.name.trim().isNotEmpty()) { "اسم برنامج المساعدة مطلوب" }
        val saved = program.copy(
            id = program.id.ifBlank { SecurityUtils.newUuid() },
            name = program.name.trim(),
            createdBy = program.createdBy ?: actor.id,
            createdByName = program.createdByName.ifBlank { actor.fullName },
            updatedAt = System.currentTimeMillis(),
            syncStatus = SyncStatus.PENDING.dbValue
        )
        db.aidDao().upsertProgram(saved)
        markSyncPending("aid_programs", saved.id)
        recordAudit(actor, "INSERT", "aid_programs", saved.id, "حفظ برنامج مساعدة: ${saved.name}")
        saved
    }

    suspend fun addBeneficiariesToProgram(
        actor: ProfileEntity,
        programId: String,
        personIds: List<String>,
        quantity: Double,
        amount: Double
    ): Int = withContext(Dispatchers.IO) {
        val perms = parsePermissions(actor)
        if (!perms.canManageAid && UserRole.fromDb(actor.role) != UserRole.ADMIN) {
            throw SecurityException("لا تملك صلاحية إضافة مستفيدين")
        }
        var addedCount = 0
        personIds.forEach { pid ->
            val ben = AidBeneficiaryEntity(
                id = SecurityUtils.newUuid(),
                programId = programId,
                personId = pid,
                allocatedQuantity = quantity,
                allocatedAmount = amount,
                isApproved = true,
                createdBy = actor.id
            )
            val rowId = db.aidDao().insertBeneficiaryIgnore(ben)
            if (rowId != -1L) {
                addedCount++
                markSyncPending("aid_beneficiaries", ben.id)
            }
        }
        if (addedCount > 0) {
            recordAudit(actor, "INSERT", "aid_beneficiaries", programId, "إضافة $addedCount مستفيد إلى برنامج المساعدة")
        }
        addedCount
    }

    suspend fun recordAidDistribution(
        actor: ProfileEntity,
        programId: String,
        personId: String,
        beneficiaryId: String?,
        quantity: Double,
        amount: Double,
        status: String,
        notes: String
    ): AidDistributionEntity = withContext(Dispatchers.IO) {
        val existing = db.aidDao().findDistribution(programId, personId)
        if (existing != null) {
            throw IllegalStateException("تم تسجيل استلام هذه المساعدة مسبقاً لهذا المواطن لمنع التكرار")
        }
        val dist = AidDistributionEntity(
            id = SecurityUtils.newUuid(),
            programId = programId,
            beneficiaryId = beneficiaryId,
            personId = personId,
            quantityDelivered = quantity,
            amountDelivered = amount,
            receivedStatus = status,
            receivedAt = System.currentTimeMillis(),
            responsibleEmployeeId = actor.id,
            responsibleEmployeeName = actor.fullName,
            notes = notes.trim()
        )
        db.aidDao().insertDistributionStrict(dist)
        if (status == "تم الاستلام") {
            db.personDao().updateLastAidDate(personId, SecurityUtils.todayDate())
            markSyncPending("people", personId)
        }
        markSyncPending("aid_distributions", dist.id)
        recordAudit(actor, "INSERT", "aid_distributions", dist.id, "تسجيل تسليم مساعدة ($status) بكمية $quantity")
        dist
    }

    // Settings
    suspend fun saveSettings(actor: ProfileEntity, settings: AppSettingsEntity) =
        withContext(Dispatchers.IO) {
            requireAdmin(actor, "تعديل إعدادات التطبيق")
            val updated = settings.copy(
                id = "global",
                updatedBy = actor.id,
                updatedAt = System.currentTimeMillis(),
                syncStatus = SyncStatus.PENDING.dbValue
            )
            db.appSettingsDao().upsert(updated)
            markSyncPending("app_settings", "global")
            recordAudit(actor, "UPDATE", "app_settings", "global", "تحديث إعدادات التطبيق والترويسة الرسمية")
        }

    suspend fun recordExportAudit(actor: ProfileEntity, format: String, reportTitle: String) =
        withContext(Dispatchers.IO) {
            recordAudit(actor, "EXPORT", "reports", format, "تصدير $reportTitle بصيغة $format")
        }

    suspend fun recordAudit(
        actor: ProfileEntity?,
        action: String,
        table: String,
        recordId: String,
        summary: String
    ) {
        db.auditLogDao().insert(
            AuditLogEntity(
                id = SecurityUtils.newUuid(),
                actorProfileId = actor?.id,
                actorUsername = actor?.username ?: "system",
                actionType = action,
                tableName = table,
                recordId = recordId,
                summaryAr = summary
            )
        )
    }

    private suspend fun markSyncPending(table: String, recordId: String) {
        val existing = db.syncMetadataDao().find(table, recordId)
        val item = SyncMetadataEntity(
            id = existing?.id ?: SecurityUtils.newUuid(),
            deviceId = deviceId,
            entityTable = table,
            recordId = recordId,
            syncStatus = SyncStatus.PENDING.dbValue,
            retryCount = existing?.retryCount ?: 0,
            localUpdatedAt = System.currentTimeMillis(),
            serverSyncedAt = existing?.serverSyncedAt
        )
        db.syncMetadataDao().upsert(item)
    }

    // Real Supabase PostgREST & Storage Synchronization
    suspend fun syncPendingWithSupabase(actor: ProfileEntity?): SyncSummary =
        withContext(Dispatchers.IO) {
            val settings = db.appSettingsDao().getSettingsOnce()
                ?: return@withContext SyncSummary(0, 0, 0, "لا توجد إعدادات")
            val baseUrl = settings.supabaseUrl.trim().trimEnd('/')
            val anonKey = settings.supabaseAnonKey.trim()
            if (baseUrl.isEmpty() || anonKey.isEmpty()) {
                return@withContext SyncSummary(
                    synced = 0,
                    failed = 0,
                    pending = db.syncMetadataDao().getPendingOrFailed().size,
                    message = "العمل محلياً دون اتصال (لم يتم ضبط رابط Supabase في الإعدادات)"
                )
            }

            val pendingList = db.syncMetadataDao().getPendingOrFailed()
            var synced = 0
            var failed = 0

            for (item in pendingList) {
                db.syncMetadataDao().updateStatus(item.id, SyncStatus.SYNCING.dbValue, null, null)
                try {
                    val payload = buildSupabasePayload(item.entityTable, item.recordId, baseUrl, anonKey)
                    if (payload != null) {
                        val req = Request.Builder()
                            .url("$baseUrl/rest/v1/${item.entityTable}")
                            .addHeader("apikey", anonKey)
                            .addHeader("Authorization", "Bearer $anonKey")
                            .addHeader("Content-Type", "application/json")
                            .addHeader("Prefer", "resolution=merge-duplicates")
                            .post(payload.toString().toRequestBody("application/json".toMediaType()))
                            .build()

                        httpClient.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                db.syncMetadataDao().updateStatus(
                                    item.id,
                                    SyncStatus.SYNCED.dbValue,
                                    System.currentTimeMillis(),
                                    null
                                )
                                synced++
                            } else if (resp.code == 409) {
                                db.syncMetadataDao().upsert(
                                    item.copy(
                                        syncStatus = SyncStatus.CONFLICT.dbValue,
                                        lastError = "تعارض في البيانات (409) - تم حفظ النسخة المحلية",
                                        conflictPayloadJson = payload.toString()
                                    )
                                )
                                failed++
                            } else {
                                db.syncMetadataDao().upsert(
                                    item.copy(
                                        syncStatus = SyncStatus.FAILED.dbValue,
                                        retryCount = item.retryCount + 1,
                                        lastError = "HTTP ${resp.code}"
                                    )
                                )
                                failed++
                            }
                        }
                    } else {
                        db.syncMetadataDao().updateStatus(item.id, SyncStatus.SYNCED.dbValue, System.currentTimeMillis(), null)
                        synced++
                    }
                } catch (e: Exception) {
                    db.syncMetadataDao().upsert(
                        item.copy(
                            syncStatus = SyncStatus.FAILED.dbValue,
                            retryCount = item.retryCount + 1,
                            lastError = e.message ?: "خطأ في الشبكة"
                        )
                    )
                    failed++
                }
            }

            recordAudit(actor, "SYNC", "sync_metadata", deviceId, "مزامنة السجلات: نجح $synced، تعذر $failed")
            SyncSummary(
                synced = synced,
                failed = failed,
                pending = db.syncMetadataDao().getPendingOrFailed().size,
                message = "اكتملت المزامنة: تم رفع $synced سجل"
            )
        }

    private suspend fun buildSupabasePayload(
        table: String,
        recordId: String,
        baseUrl: String,
        anonKey: String
    ): JSONObject? {
        return when (table) {
            "neighborhoods" -> db.neighborhoodDao().findById(recordId)?.let { n ->
                JSONObject().apply {
                    put("id", n.id)
                    put("name", n.name)
                    put("governorate", n.governorate)
                    put("district", n.district)
                    put("aqil_name", n.aqilName)
                    put("aqil_phone", n.aqilPhone)
                    put("notes", n.notes)
                    put("is_deleted", n.isDeleted)
                    put("sync_version", n.syncVersion)
                }
            }
            "residential_squares" -> db.residentialSquareDao().findById(recordId)?.let { s ->
                JSONObject().apply {
                    put("id", s.id)
                    put("neighborhood_id", s.neighborhoodId)
                    put("name", s.name)
                    put("square_code", s.squareCode)
                    put("representative_name", s.representativeName)
                    put("representative_phone", s.representativePhone)
                    put("notes", s.notes)
                    put("is_deleted", s.isDeleted)
                    put("sync_version", s.syncVersion)
                }
            }
            "people" -> db.personDao().findById(recordId)?.let { p ->
                JSONObject().apply {
                    put("id", p.id)
                    put("citizen_code", p.citizenCode)
                    put("full_name", p.fullName)
                    put("id_type", p.idType)
                    put("national_id", p.nationalId)
                    put("phone", p.phone)
                    put("governorate", p.governorate)
                    put("district", p.district)
                    put("neighborhood_id", p.neighborhoodId)
                    put("square_id", p.squareId)
                    put("occupation", p.occupation)
                    put("family_size", p.familySize)
                    put("housing_type", p.housingType)
                    put("housing_status", p.housingStatus)
                    put("health_status", p.healthStatus)
                    put("health_details", p.healthDetails)
                    put("notes", p.notes)
                    put("created_by_name", p.createdByName)
                    put("is_deleted", p.isDeleted)
                    put("sync_version", p.syncVersion)
                }
            }
            "identity_documents" -> {
                val doc = db.identityDocumentDao().getAllRaw().firstOrNull { it.id == recordId } ?: return null
                val file = File(doc.localFilePath)
                var storagePath = doc.remoteStoragePath ?: "${doc.personId}/${file.name}"
                if (file.exists() && !doc.uploaded) {
                    val uploadReq = Request.Builder()
                        .url("$baseUrl/storage/v1/object/identity-documents/$storagePath")
                        .addHeader("apikey", anonKey)
                        .addHeader("Authorization", "Bearer $anonKey")
                        .addHeader("x-upsert", "true")
                        .post(file.asRequestBody(doc.mimeType.toMediaType()))
                        .build()
                    httpClient.newCall(uploadReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            db.identityDocumentDao().upsert(doc.copy(uploaded = true, remoteStoragePath = storagePath))
                        }
                    }
                }
                JSONObject().apply {
                    put("id", doc.id)
                    put("person_id", doc.personId)
                    put("document_type", doc.documentType)
                    put("side", doc.side)
                    put("storage_path", storagePath)
                    put("mime_type", doc.mimeType)
                    put("file_size_bytes", doc.fileSizeBytes)
                    put("sha256_checksum", doc.sha256Checksum)
                }
            }
            "aid_programs" -> db.aidDao().getAllProgramsRaw().firstOrNull { it.id == recordId }?.let { a ->
                JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                    put("aid_type", a.aidType)
                    put("donor_name", a.donorName)
                    put("distribution_date", a.distributionDate)
                    put("unit_label", a.unitLabel)
                    put("default_quantity", a.defaultQuantity)
                    put("default_amount", a.defaultAmount)
                    put("notes", a.notes)
                }
            }
            "aid_distributions" -> db.aidDao().getAllDistributionsRaw().firstOrNull { it.id == recordId }?.let { d ->
                JSONObject().apply {
                    put("id", d.id)
                    put("program_id", d.programId)
                    put("person_id", d.personId)
                    put("quantity_delivered", d.quantityDelivered)
                    put("amount_delivered", d.amountDelivered)
                    put("received_status", d.receivedStatus)
                    put("responsible_employee_name", d.responsibleEmployeeName)
                    put("notes", d.notes)
                }
            }
            else -> null
        }
    }

    private fun requireAdmin(actor: ProfileEntity, operationName: String) {
        if (UserRole.fromDb(actor.role) != UserRole.ADMIN) {
            throw SecurityException("عملية ($operationName) متاحة لمدير النظام فقط")
        }
    }

    private fun requireAdminOrAreaManager(actor: ProfileEntity) {
        val perms = parsePermissions(actor)
        if (!perms.canManageAreas && UserRole.fromDb(actor.role) != UserRole.ADMIN) {
            throw SecurityException("لا تملك صلاحية إدارة الحارات والمربعات")
        }
    }
}

data class SyncSummary(
    val synced: Int,
    val failed: Int,
    val pending: Int,
    val message: String
)
