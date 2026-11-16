package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.*
import com.example.data.repository.RegistryRepository
import com.example.data.sync.SyncWorker
import com.example.export.*
import com.example.util.ImageStorageManager
import com.example.util.SecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DashboardMetrics(
    val totalPeople: Int = 0,
    val totalFamilyMembers: Int = 0,
    val totalNeighborhoods: Int = 0,
    val totalSquares: Int = 0,
    val pendingSyncCount: Int = 0
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    val localDb: AppDatabase get() = db
    val repo = RegistryRepository(application, db)

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _currentUser = MutableStateFlow<ProfileEntity?>(null)
    val currentUser: StateFlow<ProfileEntity?> = _currentUser.asStateFlow()

    val currentPermissions: StateFlow<UserPermissions> = _currentUser.map { user ->
        user?.let { repo.parsePermissions(it) } ?: UserPermissions()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UserPermissions())

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    // Core reactive lists
    val profiles: StateFlow<List<ProfileEntity>> = repo.profilesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val neighborhoods: StateFlow<List<NeighborhoodEntity>> = repo.neighborhoodsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val squares: StateFlow<List<ResidentialSquareEntity>> = repo.squaresFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val repHistory: StateFlow<List<RepresentativeHistoryEntity>> = repo.repHistoryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val aidPrograms: StateFlow<List<AidProgramEntity>> = repo.aidProgramsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allDistributions: StateFlow<List<AidDistributionEntity>> = repo.allDistributionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<AppSettingsEntity> = repo.settingsFlow
        .map { it ?: AppSettingsEntity() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettingsEntity())

    val auditLogs: StateFlow<List<AuditLogEntity>> = repo.auditLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val syncMetadata: StateFlow<List<SyncMetadataEntity>> = repo.syncMetadataFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val metrics: StateFlow<DashboardMetrics> = combine(
        repo.totalPeopleCountFlow,
        repo.totalFamilyMembersFlow,
        repo.neighborhoodCountFlow,
        repo.squareCountFlow,
        repo.pendingSyncCountFlow
    ) { people, family, neigh, sq, pending ->
        DashboardMetrics(people, family, neigh, sq, pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardMetrics())

    // Paginated Citizens Search State (supports 4,000+ rows)
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filterNeighborhoodId = MutableStateFlow("")
    val filterNeighborhoodId: StateFlow<String> = _filterNeighborhoodId.asStateFlow()

    private val _filterSquareId = MutableStateFlow("")
    val filterSquareId: StateFlow<String> = _filterSquareId.asStateFlow()

    private val _filterHousingType = MutableStateFlow("")
    val filterHousingType: StateFlow<String> = _filterHousingType.asStateFlow()

    private val _filterHealthStatus = MutableStateFlow("")
    val filterHealthStatus: StateFlow<String> = _filterHealthStatus.asStateFlow()

    private val _pagedCitizens = MutableStateFlow<List<PersonEntity>>(emptyList())
    val pagedCitizens: StateFlow<List<PersonEntity>> = _pagedCitizens.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _hasMorePages = MutableStateFlow(false)
    val hasMorePages: StateFlow<Boolean> = _hasMorePages.asStateFlow()

    private val pageSize = 50

    // Backup inspection state
    private val _pendingRestoreInspection = MutableStateFlow<BackupInspectionResult?>(null)
    val pendingRestoreInspection: StateFlow<BackupInspectionResult?> = _pendingRestoreInspection.asStateFlow()

    init {
        viewModelScope.launch {
            repo.initializeDatabase()
            SyncWorker.schedulePeriodic(getApplication())
            SyncWorker.scheduleImmediateOnConnect(getApplication())
            _isInitialized.value = true
            refreshCitizensPage(reset = true)
        }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun showMessage(msg: String) {
        _snackbarMessage.value = msg
    }

    fun clearLoginError() {
        _loginError.value = null
    }

    fun login(username: String, password: String) {
        viewModelScope.launch {
            _isBusy.value = true
            _loginError.value = null
            try {
                val profile = repo.authenticate(username, password)
                _currentUser.value = profile
                refreshCitizensPage(reset = true)
            } catch (e: Exception) {
                _loginError.value = e.message ?: "فشل تسجيل الدخول"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun loginAsVerifiedCloudAdmin(email: String, displayName: String) {
        viewModelScope.launch {
            _isBusy.value = true
            _loginError.value = null
            try {
                val profile = repo.loginAsVerifiedCloudAdmin(email, displayName)
                _currentUser.value = profile
                refreshCitizensPage(reset = true)
            } catch (e: Exception) {
                _loginError.value = e.message ?: "تعذر الدخول كمدير النظام"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun logout() {
        val actor = _currentUser.value
        _currentUser.value = null
        viewModelScope.launch {
            if (actor != null) {
                repo.recordAudit(actor, "LOGOUT", "profiles", actor.id, "تسجيل خروج ${actor.fullName}")
            }
        }
    }

    fun updateSearchFilters(
        query: String = _searchQuery.value,
        neighborhoodId: String = _filterNeighborhoodId.value,
        squareId: String = _filterSquareId.value,
        housingType: String = _filterHousingType.value,
        healthStatus: String = _filterHealthStatus.value
    ) {
        _searchQuery.value = query
        _filterNeighborhoodId.value = neighborhoodId
        _filterSquareId.value = if (neighborhoodId != _filterNeighborhoodId.value) "" else squareId
        _filterHousingType.value = housingType
        _filterHealthStatus.value = healthStatus
        refreshCitizensPage(reset = true)
    }

    fun refreshCitizensPage(reset: Boolean = false) {
        viewModelScope.launch {
            val page = if (reset) 0 else _currentPage.value
            val offset = page * pageSize
            val list = repo.searchPeoplePaged(
                query = _searchQuery.value,
                neighborhoodId = _filterNeighborhoodId.value,
                squareId = _filterSquareId.value,
                housingType = _filterHousingType.value,
                healthStatus = _filterHealthStatus.value,
                limit = pageSize + 1,
                offset = offset
            )
            _hasMorePages.value = list.size > pageSize
            val pageItems = list.take(pageSize)
            _pagedCitizens.value = if (reset) pageItems else (_pagedCitizens.value + pageItems)
            _currentPage.value = page
        }
    }

    fun loadNextCitizensPage() {
        if (!_hasMorePages.value) return
        _currentPage.value += 1
        refreshCitizensPage(reset = false)
    }

    fun saveCitizen(
        existingId: String?,
        personDraft: PersonEntity,
        documents: List<IdentityDocumentEntity>,
        onSuccess: (PersonEntity) -> Unit
    ) {
        val actor = _currentUser.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val saved = repo.saveCitizen(actor, existingId, personDraft, documents)
                SyncWorker.scheduleImmediateOnConnect(getApplication())
                refreshCitizensPage(reset = true)
                _snackbarMessage.value = "تم حفظ سجل المواطن بنجاح (${saved.citizenCode})"
                onSuccess(saved)
            } catch (e: Exception) {
                _snackbarMessage.value = e.message ?: "تعذر حفظ بيانات المواطن"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun deleteCitizen(personId: String, onDeleted: () -> Unit) {
        val actor = _currentUser.value ?: return
        viewModelScope.launch {
            try {
                repo.deleteCitizen(actor, personId)
                SyncWorker.scheduleImmediateOnConnect(getApplication())
                refreshCitizensPage(reset = true)
                _snackbarMessage.value = "تم حذف السجل وتسجيل العملية في السجل"
                onDeleted()
            } catch (e: Exception) {
                _snackbarMessage.value = e.message ?: "غير مصرح بالحذف"
            }
        }
    }

    fun importCitizensFromExcel(
        rows: List<List<String>>,
        headerRowIndex: Int,
        mapping: Map<CitizenImportField, Int?>,
        defaultNeighborhoodId: String,
        defaultSquareId: String,
        createMissingAreas: Boolean,
        skipDuplicateIds: Boolean,
        updateExistingOnDuplicateId: Boolean = false,
        onResult: (ExcelImportResult) -> Unit
    ) {
        val actor = _currentUser.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val result = repo.importCitizensFromExcel(
                    actor = actor,
                    rows = rows,
                    headerRowIndex = headerRowIndex,
                    mapping = mapping,
                    defaultNeighborhoodId = defaultNeighborhoodId,
                    defaultSquareId = defaultSquareId,
                    createMissingAreas = createMissingAreas,
                    skipDuplicateIds = skipDuplicateIds,
                    updateExistingOnDuplicateId = updateExistingOnDuplicateId
                )
                SyncWorker.scheduleImmediateOnConnect(getApplication())
                refreshCitizensPage(reset = true)
                _snackbarMessage.value = buildString {
                    append("تم استيراد ${result.importedCount} مواطن")
                    if (result.updatedCount > 0) append(" وتحديث ${result.updatedCount}")
                }
                onResult(result)
            } catch (e: Exception) {
                _snackbarMessage.value = e.message ?: "تعذر استيراد ملف إكسل"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun saveNeighborhood(
        existingId: String?,
        name: String,
        governorate: String,
        district: String,
        aqilProfile: ProfileEntity?,
        aqilName: String,
        aqilPhone: String,
        notes: String,
        onDone: () -> Unit
    ) {
        val actor = _currentUser.value ?: return
        viewModelScope.launch {
            try {
                repo.saveNeighborhood(actor, existingId, name, governorate, district, aqilProfile, aqilName, aqilPhone, notes)
                SyncWorker.scheduleImmediateOnConnect(getApplication())
                _snackbarMessage.value = "تم حفظ بيانات الحارة بنجاح"
                onDone()
            } catch (e: Exception) {
                _snackbarMessage.value = e.message ?: "تعذر حفظ الحارة"
            }
        }
    }

    fun saveSquare(
        existingId: String?,
        neighborhoodId: String,
        name: String,
        code: String,
        repProfile: ProfileEntity?,
        repName: String,
        repPhone: String,
        notes: String,
        onDone: () -> Unit
    ) {
        val actor = _currentUser.value ?: return
        viewModelScope.launch {
            try {
                repo.saveResidentialSquare(actor, existingId, neighborhoodId, name, code, repProfile, repName, repPhone, notes)
                SyncWorker.scheduleImmediateOnConnect(getApplication())
                _snackbarMessage.value = "تم حفظ بيانات المربع السكني"
                onDone()
            } catch (e: Exception) {
                _snackbarMessage.value = e.message ?: "تعذر حفظ المربع"
            }
        }
    }

    fun saveUser(
        existingId: String?,
        username: String,
        fullName: String,
        phone: String,
        role: UserRole,
        password: String?,
        permissions: UserPermissions,
        isActive: Boolean,
        email: String = "",
        onPersisted: (ProfileEntity) -> Unit = {},
        onDone: () -> Unit
    ) {
        val actor = _currentUser.value ?: return
        viewModelScope.launch {
            try {
                val saved = repo.saveUser(actor, existingId, username, fullName, phone, role, password, permissions, isActive, email)
                SyncWorker.scheduleImmediateOnConnect(getApplication())
                onPersisted(saved)
                _snackbarMessage.value = "تم حفظ حساب المستخدم والصلاحيات"
                onDone()
            } catch (e: Exception) {
                _snackbarMessage.value = e.message ?: "تعذر حفظ المستخدم"
            }
        }
    }

    fun saveAidProgram(program: AidProgramEntity, onDone: () -> Unit) {
        val actor = _currentUser.value ?: return
        viewModelScope.launch {
            try {
                repo.saveAidProgram(actor, program)
                SyncWorker.scheduleImmediateOnConnect(getApplication())
                _snackbarMessage.value = "تم حفظ برنامج المساعدة"
                onDone()
            } catch (e: Exception) {
                _snackbarMessage.value = e.message ?: "تعذر حفظ المساعدة"
            }
        }
    }

    fun addBeneficiaries(programId: String, personIds: List<String>, qty: Double, amount: Double) {
        val actor = _currentUser.value ?: return
        viewModelScope.launch {
            try {
                val added = repo.addBeneficiariesToProgram(actor, programId, personIds, qty, amount)
                SyncWorker.scheduleImmediateOnConnect(getApplication())
                _snackbarMessage.value = if (added > 0) "تم اعتماد $added مستفيد جديد" else "المستفيدون المختارون مضافون مسبقاً"
            } catch (e: Exception) {
                _snackbarMessage.value = e.message ?: "تعذر إضافة المستفيدين"
            }
        }
    }

    fun recordDistribution(
        programId: String,
        personId: String,
        beneficiaryId: String?,
        qty: Double,
        amount: Double,
        status: String,
        notes: String
    ) {
        val actor = _currentUser.value ?: return
        viewModelScope.launch {
            try {
                repo.recordAidDistribution(actor, programId, personId, beneficiaryId, qty, amount, status, notes)
                SyncWorker.scheduleImmediateOnConnect(getApplication())
                refreshCitizensPage(reset = true)
                _snackbarMessage.value = "تم تسجيل تسليم المساعدة بنجاح"
            } catch (e: Exception) {
                _snackbarMessage.value = e.message ?: "تعذر التسجيل"
            }
        }
    }

    fun saveSettings(updated: AppSettingsEntity) {
        val actor = _currentUser.value ?: return
        viewModelScope.launch {
            try {
                repo.saveSettings(actor, updated)
                _snackbarMessage.value = "تم حفظ الإعدادات وتطبيق التغييرات على المستندات الجديدة"
            } catch (e: Exception) {
                _snackbarMessage.value = e.message ?: "تعذر حفظ الإعدادات"
            }
        }
    }

    fun updateBrandingImage(uri: Uri, isHeader: Boolean) {
        val actor = _currentUser.value ?: return
        viewModelScope.launch {
            try {
                val saved = ImageStorageManager.saveCompressedDocumentImage(
                    getApplication(),
                    uri,
                    subFolder = "branding",
                    filePrefix = if (isHeader) "header" else "eagle"
                )
                val current = settings.value
                val next = if (isHeader) {
                    current.copy(officialHeaderLocalPath = saved.absolutePath)
                } else {
                    current.copy(eagleEmblemLocalPath = saved.absolutePath)
                }
                repo.saveSettings(actor, next)
                _snackbarMessage.value = if (isHeader) "تم تحديث صورة الترويسة الرسمية" else "تم تحديث شعار النسر"
            } catch (e: Exception) {
                _snackbarMessage.value = e.message ?: "تعذر تحديث الصورة"
            }
        }
    }

    fun syncNow() {
        val actor = _currentUser.value
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val summary = repo.syncPendingWithSupabase(actor)
                _snackbarMessage.value = summary.message
            } finally {
                _isBusy.value = false
            }
        }
    }

    // Report Building & Export (13 report types)
    suspend fun buildReportData(
        reportType: ReportType,
        selectedColumns: List<ReportColumn>,
        filterNeighborhoodId: String = "",
        filterSquareId: String = "",
        filterText: String = "",
        minFamilySize: Int = 1
    ): ReportTableData = withContext(Dispatchers.IO) {
        val allPeople = repo.getAllActivePeople()
        val neighMap = neighborhoods.value.associateBy { it.id }
        val sqMap = squares.value.associateBy { it.id }
        val allDists = allDistributions.value
        val programsMap = aidPrograms.value.associateBy { it.id }

        fun citizenRow(p: PersonEntity): List<String> = selectedColumns.map { col ->
            val n = neighMap[p.neighborhoodId]
            val s = sqMap[p.squareId]
            when (col) {
                ReportColumn.CITIZEN_CODE -> p.citizenCode
                ReportColumn.FULL_NAME -> p.fullName
                ReportColumn.ID_TYPE -> p.idType
                ReportColumn.NATIONAL_ID -> p.nationalId
                ReportColumn.PHONE -> p.phone
                ReportColumn.NEIGHBORHOOD -> n?.name ?: ""
                ReportColumn.SQUARE -> s?.name ?: ""
                ReportColumn.OCCUPATION -> p.occupation
                ReportColumn.FAMILY_SIZE -> p.familySize.toString()
                ReportColumn.HOUSING_TYPE -> p.housingType
                ReportColumn.HOUSING_STATUS -> p.housingStatus
                ReportColumn.HEALTH_STATUS -> p.healthStatus
                ReportColumn.DISPLACEMENT_DATE -> p.displacementDate
                ReportColumn.LAST_AID_DATE -> p.lastAidDate
                ReportColumn.CREATED_BY -> p.createdByName
                ReportColumn.CREATED_AT -> SecurityUtils.formatArabicDateTime(p.createdAt)
            }
        }

        val colHeaders = selectedColumns.map { it.arabicLabel }

        return@withContext when (reportType) {
            ReportType.ALL_CITIZENS -> ReportTableData(
                title = "كشف جميع المواطنين المسجلين",
                subtitle = "الإجمالي: ${allPeople.size} مواطن",
                headers = colHeaders,
                rows = allPeople.map(::citizenRow)
            )
            ReportType.BY_NEIGHBORHOOD -> {
                val filtered = if (filterNeighborhoodId.isNotEmpty()) allPeople.filter { it.neighborhoodId == filterNeighborhoodId } else allPeople
                val nName = neighMap[filterNeighborhoodId]?.name ?: "كل الحارات"
                ReportTableData("كشف مواطني حارة: $nName", "العدد: ${filtered.size}", colHeaders, filtered.map(::citizenRow))
            }
            ReportType.BY_SQUARE -> {
                val filtered = if (filterSquareId.isNotEmpty()) allPeople.filter { it.squareId == filterSquareId } else allPeople
                val sName = sqMap[filterSquareId]?.name ?: "كل المربعات"
                ReportTableData("كشف مواطني مربع: $sName", "العدد: ${filtered.size}", colHeaders, filtered.map(::citizenRow))
            }
            ReportType.BY_REPRESENTATIVE -> {
                val q = filterText.trim()
                val matchingSquares = sqMap.values.filter { q.isEmpty() || it.representativeName.contains(q, true) }.map { it.id }.toSet()
                val filtered = allPeople.filter { it.squareId in matchingSquares }
                ReportTableData("كشف المواطنين حسب المندوب", if (q.isEmpty()) "جميع المندوبين" else "المندوب: $q", colHeaders, filtered.map(::citizenRow))
            }
            ReportType.BY_AQIL -> {
                val q = filterText.trim()
                val matchingNeighs = neighMap.values.filter { q.isEmpty() || it.aqilName.contains(q, true) }.map { it.id }.toSet()
                val filtered = allPeople.filter { it.neighborhoodId in matchingNeighs }
                ReportTableData("كشف المواطنين حسب عاقل الحارة", if (q.isEmpty()) "جميع العقال" else "العاقل: $q", colHeaders, filtered.map(::citizenRow))
            }
            ReportType.BY_HEALTH_STATUS -> {
                val q = filterText.trim()
                val filtered = if (q.isEmpty()) allPeople.filter { it.healthStatus != "سليم" } else allPeople.filter { it.healthStatus.contains(q, true) }
                ReportTableData("كشف المواطنين حسب الحالة الصحية", "الحالة: ${q.ifBlank { "الحالات المرضية والخاصة" }}", colHeaders, filtered.map(::citizenRow))
            }
            ReportType.BY_HOUSING_TYPE -> {
                val q = filterText.trim()
                val filtered = if (q.isEmpty()) allPeople else allPeople.filter { it.housingType == q }
                ReportTableData("كشف المواطنين حسب نوع السكن", "النوع: ${q.ifBlank { "الكل" }}", colHeaders, filtered.map(::citizenRow))
            }
            ReportType.BY_FAMILY_SIZE -> {
                val filtered = allPeople.filter { it.familySize >= minFamilySize }.sortedByDescending { it.familySize }
                ReportTableData("كشف المواطنين حسب حجم الأسرة", "عدد الأفراد >= $minFamilySize", colHeaders, filtered.map(::citizenRow))
            }
            ReportType.AID_BENEFICIARIES -> {
                val peopleMap = allPeople.associateBy { it.id }
                val rows = db.aidDao().getAllBeneficiariesRaw().mapNotNull { ben ->
                    val p = peopleMap[ben.personId] ?: return@mapNotNull null
                    val prog = programsMap[ben.programId]
                    listOf(
                        p.citizenCode,
                        p.fullName,
                        p.nationalId,
                        neighMap[p.neighborhoodId]?.name ?: "",
                        prog?.name ?: "",
                        ben.allocatedQuantity.toString(),
                        ben.allocatedAmount.toString()
                    )
                }
                ReportTableData(
                    title = "كشف المستفيدين المعتمدين من المساعدات",
                    subtitle = "العدد: ${rows.size}",
                    headers = listOf("رقم التعريف", "الاسم الكامل", "رقم الهوية", "الحارة", "البرنامج", "الكمية", "المبلغ"),
                    rows = rows
                )
            }
            ReportType.AID_DISTRIBUTIONS -> {
                val peopleMap = allPeople.associateBy { it.id }
                val rows = allDists.mapNotNull { dist ->
                    val p = peopleMap[dist.personId] ?: return@mapNotNull null
                    val prog = programsMap[dist.programId]
                    listOf(
                        p.citizenCode,
                        p.fullName,
                        prog?.name ?: "",
                        dist.quantityDelivered.toString(),
                        dist.receivedStatus,
                        dist.responsibleEmployeeName,
                        SecurityUtils.formatArabicDateTime(dist.receivedAt)
                    )
                }
                ReportTableData(
                    title = "كشف توزيع واستلام المساعدات",
                    subtitle = "العمليات: ${rows.size}",
                    headers = listOf("رقم التعريف", "المواطن", "البرنامج", "الكمية", "الحالة", "الموظف", "التاريخ"),
                    rows = rows
                )
            }
            ReportType.NO_AID_RECEIVED -> {
                val withoutAid = allPeople.filter { it.lastAidDate.isBlank() }
                ReportTableData("المواطنون الذين لم يحصلوا على مساعدة", "العدد: ${withoutAid.size}", colHeaders, withoutAid.map(::citizenRow))
            }
            ReportType.ENTRIES_BY_EMPLOYEE -> {
                val q = filterText.trim()
                val filtered = if (q.isEmpty()) allPeople else allPeople.filter { it.createdByName.contains(q, true) }
                ReportTableData("كشف عمليات الإدخال حسب الموظف", "الموظف: ${q.ifBlank { "جميع الموظفين" }}", colHeaders, filtered.map(::citizenRow))
            }
            ReportType.GENERAL_STATISTICS -> {
                val owners = allPeople.count { it.housingType == "مالك" }
                val tenants = allPeople.count { it.housingType == "مستأجر" }
                val sick = allPeople.count { it.healthStatus != "سليم" }
                val noAid = allPeople.count { it.lastAidDate.isBlank() }
                val totalFam = allPeople.sumOf { it.familySize }
                val statRows = listOf(
                    listOf("إجمالي المواطنين النشطين", allPeople.size.toString()),
                    listOf("إجمالي أفراد الأسر", totalFam.toString()),
                    listOf("عدد الحارات المسجلة", neighMap.size.toString()),
                    listOf("عدد المربعات السكنية", sqMap.size.toString()),
                    listOf("السكن: مالك", owners.toString()),
                    listOf("السكن: مستأجر", tenants.toString()),
                    listOf("الحالات المرضية / الخاصة", sick.toString()),
                    listOf("مواطنون لم يحصلوا على مساعدة", noAid.toString()),
                    listOf("برامج المساعدات", programsMap.size.toString()),
                    listOf("عمليات التسليم المنفذة", allDists.size.toString())
                )
                ReportTableData(
                    title = "تقرير الإحصائيات العامة للنظام",
                    subtitle = "ملخص إحصائي شامل",
                    headers = listOf("المؤشر الإحصائي", "القيمة"),
                    rows = statRows
                )
            }
        }
    }

    fun exportReportPdf(
        report: ReportTableData,
        landscape: Boolean,
        onReady: (File) -> Unit
    ) {
        val actor = _currentUser.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val file = withContext(Dispatchers.IO) {
                    MultiPageReportPdfGenerator.generateReportPdf(getApplication(), report, settings.value, landscape)
                }
                repo.recordExportAudit(actor, "PDF", report.title)
                onReady(file)
            } catch (e: Exception) {
                _snackbarMessage.value = "فشل تصدير PDF: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun exportReportExcel(
        report: ReportTableData,
        landscape: Boolean,
        onReady: (File) -> Unit
    ) {
        val actor = _currentUser.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val file = withContext(Dispatchers.IO) {
                    ExcelXlsxGenerator.generateXlsx(getApplication(), report, settings.value, landscape)
                }
                repo.recordExportAudit(actor, "Excel (.xlsx)", report.title)
                onReady(file)
            } catch (e: Exception) {
                _snackbarMessage.value = "فشل تصدير Excel: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun exportBackup(onReady: (File) -> Unit) {
        val actor = _currentUser.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val file = BackupManager.exportBackupZip(getApplication(), db, actor)
                _snackbarMessage.value = "تم إنشاء النسخة الاحتياطية بنجاح"
                onReady(file)
            } catch (e: Exception) {
                _snackbarMessage.value = e.message ?: "تعذر إنشاء النسخة الاحتياطية"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun inspectBackupForRestore(uri: Uri) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val result = BackupManager.inspectAndVerifyBackup(getApplication(), uri)
                _pendingRestoreInspection.value = result
            } catch (e: Exception) {
                _snackbarMessage.value = e.message ?: "فشل التحقق من النسخة الاحتياطية"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun dismissRestoreInspection() {
        _pendingRestoreInspection.value?.extractedImagesDir?.deleteRecursively()
        _pendingRestoreInspection.value = null
    }

    fun confirmRestoreBackup() {
        val actor = _currentUser.value ?: return
        val inspection = _pendingRestoreInspection.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                BackupManager.restoreVerifiedBackup(getApplication(), db, actor, inspection)
                _pendingRestoreInspection.value = null
                refreshCitizensPage(reset = true)
                _snackbarMessage.value = "تمت استعادة النسخة الاحتياطية بنجاح والتحقق من سلامتها"
            } catch (e: Exception) {
                _snackbarMessage.value = e.message ?: "تعذرت الاستعادة"
            } finally {
                _isBusy.value = false
            }
        }
    }
}
