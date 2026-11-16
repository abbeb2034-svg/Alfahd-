package com.example

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.repository.RegistryRepository
import com.example.export.*
import com.example.util.SecurityUtils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SijilAlFahdComprehensiveTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: RegistryRepository

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RegistryRepository(context, db)
        DatabaseSeeder.ensureSeeded(db)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `authentication and password hashing work securely`() = runBlocking {
        val admin = repo.authenticate("abbeb34", "Aa0012365400")
        assertEquals(UserRole.ADMIN.dbValue, admin.role)
        assertTrue(admin.passwordHash.startsWith("pbkdf2:"))

        val emp = repo.authenticate("emp1", "Emp@123")
        assertEquals(UserRole.EMPLOYEE.dbValue, emp.role)

        try {
            repo.authenticate("admin", "WrongPassword")
            fail("Expected IllegalArgumentException for bad password")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun `employee can add citizen but cannot edit or delete existing citizens`() = runBlocking {
        val admin = repo.authenticate("abbeb34", "Aa0012365400")
        val emp = repo.authenticate("emp1", "Emp@123")
        val neigh = db.neighborhoodDao().getAllActiveOnce().first()
        val sq = db.residentialSquareDao().getAllActiveOnce().first { it.neighborhoodId == neigh.id }

        val created = repo.saveCitizen(
            actor = emp,
            existingId = null,
            personDraft = PersonEntity(
                id = SecurityUtils.newUuid(),
                citizenCode = "FAHD-T001",
                fullName = "عبدالله محمد السالمي",
                idType = DocumentType.NATIONAL_ID.arabicLabel,
                nationalId = "0101020203",
                neighborhoodId = neigh.id,
                squareId = sq.id,
                familySize = 5
            ),
            documents = emptyList()
        )
        assertEquals("موظف السجل الميداني", created.createdByName)

        // Employee blocked from updating
        try {
            repo.saveCitizen(emp, created.id, created.copy(fullName = "تعديل غير مصرح"), emptyList())
            fail("Employee must not be allowed to update existing citizen")
        } catch (_: SecurityException) {
            // Expected
        }

        // Employee blocked from deleting
        try {
            repo.deleteCitizen(emp, created.id)
            fail("Employee must not be allowed to delete citizen")
        } catch (_: SecurityException) {
            // Expected
        }

        // Admin CAN update
        val updated = repo.saveCitizen(admin, created.id, created.copy(fullName = "عبدالله محمد السالمي المعدل"), emptyList())
        assertEquals("عبدالله محمد السالمي المعدل", updated.fullName)
    }

    @Test
    fun `duplicate national id is prevented`() = runBlocking {
        val admin = repo.authenticate("abbeb34", "Aa0012365400")
        val neigh = db.neighborhoodDao().getAllActiveOnce().first()
        val sq = db.residentialSquareDao().getAllActiveOnce().first { it.neighborhoodId == neigh.id }

        repo.saveCitizen(
            actor = admin,
            existingId = null,
            personDraft = PersonEntity(
                id = SecurityUtils.newUuid(),
                citizenCode = "FAHD-D1",
                fullName = "سعيد علي أحمد",
                idType = DocumentType.NATIONAL_ID.arabicLabel,
                nationalId = "9988776655",
                neighborhoodId = neigh.id,
                squareId = sq.id
            ),
            documents = emptyList()
        )

        try {
            repo.saveCitizen(
                actor = admin,
                existingId = null,
                personDraft = PersonEntity(
                    id = SecurityUtils.newUuid(),
                    citizenCode = "FAHD-D2",
                    fullName = "مواطن مكرر",
                    idType = DocumentType.NATIONAL_ID.arabicLabel,
                    nationalId = "9988776655",
                    neighborhoodId = neigh.id,
                    squareId = sq.id
                ),
                documents = emptyList()
            )
            fail("Duplicate national ID should throw IllegalStateException")
        } catch (_: IllegalStateException) {
            // Expected
        }
    }

    @Test
    fun `changing representative preserves historical assignment`() = runBlocking {
        val admin = repo.authenticate("abbeb34", "Aa0012365400")
        val neigh = db.neighborhoodDao().getAllActiveOnce().first()
        val sq = db.residentialSquareDao().getAllActiveOnce().first { it.neighborhoodId == neigh.id }

        val rep1 = repo.saveUser(admin, null, "rep_one", "المندوب الأول", "771001001", UserRole.REPRESENTATIVE, "Pass123", UserPermissions.forRole(UserRole.REPRESENTATIVE), true)
        val rep2 = repo.saveUser(admin, null, "rep_two", "المندوب الثاني", "772002002", UserRole.REPRESENTATIVE, "Pass123", UserPermissions.forRole(UserRole.REPRESENTATIVE), true)

        repo.saveResidentialSquare(admin, sq.id, neigh.id, sq.name, sq.squareCode, rep1, "", "", "")
        repo.saveResidentialSquare(admin, sq.id, neigh.id, sq.name, sq.squareCode, rep2, "", "", "")

        val history = db.representativeHistoryDao().getAllRaw().filter { it.squareId == sq.id }
        assertEquals(2, history.size)
        assertEquals(1, history.count { it.isCurrent && it.profileId == rep2.id })
        assertEquals(1, history.count { !it.isCurrent && it.profileId == rep1.id && it.unassignedAt != null })
    }

    @Test
    fun `aid distribution prevents accidental duplicate receipt`() = runBlocking {
        val admin = repo.authenticate("abbeb34", "Aa0012365400")
        val neigh = db.neighborhoodDao().getAllActiveOnce().first()
        val sq = db.residentialSquareDao().getAllActiveOnce().first { it.neighborhoodId == neigh.id }

        val citizen = repo.saveCitizen(
            admin, null,
            PersonEntity(
                id = SecurityUtils.newUuid(),
                citizenCode = "FAHD-AID1",
                fullName = "خالد ناصر العتيقي",
                nationalId = "44556677",
                neighborhoodId = neigh.id,
                squareId = sq.id
            ),
            emptyList()
        )
        val prog = repo.saveAidProgram(
            admin,
            AidProgramEntity(
                id = SecurityUtils.newUuid(),
                name = "سلة رمضان الغذائية",
                aidType = "غذائية",
                donorName = "مركز الإغاثة",
                distributionDate = "2026-11-15"
            )
        )
        repo.addBeneficiariesToProgram(admin, prog.id, listOf(citizen.id), 1.0, 0.0)
        repo.recordAidDistribution(admin, prog.id, citizen.id, null, 1.0, 0.0, "تم الاستلام", "")

        try {
            repo.recordAidDistribution(admin, prog.id, citizen.id, null, 1.0, 0.0, "تم الاستلام", "")
            fail("Duplicate aid distribution must be prevented")
        } catch (_: IllegalStateException) {
            // Expected
        }
    }

    @Test
    fun `export pdf multi-page, xlsx with Print Titles, id card, form, and backup zip`() = runBlocking {
        val admin = repo.authenticate("abbeb34", "Aa0012365400")
        val neigh = db.neighborhoodDao().getAllActiveOnce().first()
        val sq = db.residentialSquareDao().getAllActiveOnce().first { it.neighborhoodId == neigh.id }
        val settings = db.appSettingsDao().getSettingsOnce()

        val rows = (1..65).map { idx ->
            listOf("FAHD-$idx", "المواطن التجريبي رقم $idx", "10000$idx", "77000$idx", neigh.name, sq.name, "4", "مستأجر", "سليم")
        }
        val report = ReportTableData(
            title = "اختبار التقرير متعدد الصفحات",
            subtitle = "65 مواطناً",
            headers = listOf("رقم التعريف", "الاسم الكامل", "رقم الهوية", "الهاتف", "الحارة", "المربع", "الأسرة", "السكن", "الصحة"),
            rows = rows
        )

        // 1. Multi-page PDF pagination chunks (verifies 65 rows span multiple pages without splitting rows)
        val chunks = MultiPageReportPdfGenerator.computePageChunks(context, report, settings, landscape = false)
        assertTrue("Expected multi-page split for 65 rows, got ${chunks.size}", chunks.size >= 2)
        assertEquals(65, chunks.sumOf { it.rows.size })

        // 2. Genuine XLSX with RTL, Print Titles, Print Area & embedded header PNG
        val xlsxFile = ExcelXlsxGenerator.generateXlsx(context, report, settings, landscape = true)
        assertTrue(xlsxFile.exists() && xlsxFile.length() > 1000L)
        ZipFile(xlsxFile).use { zip ->
            assertNotNull(zip.getEntry("xl/media/header.png"))
            val workbookXml = zip.getInputStream(zip.getEntry("xl/workbook.xml")).bufferedReader().readText()
            assertTrue(workbookXml.contains("_xlnm.Print_Titles"))
            assertTrue(workbookXml.contains("_xlnm.Print_Area"))
            val sheetXml = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).bufferedReader().readText()
            assertTrue(sheetXml.contains("rightToLeft=\"1\""))
            assertTrue(sheetXml.contains("orientation=\"landscape\""))
            assertTrue(sheetXml.contains("المواطن التجريبي رقم 65"))
        }

        // 3. QR Code (internal ID only) & ID Card PNG
        val citizen = repo.saveCitizen(
            admin, null,
            PersonEntity(
                id = SecurityUtils.newUuid(),
                citizenCode = "FAHD-CARD1",
                fullName = "ياسر صالح القباطي",
                nationalId = "88990011",
                neighborhoodId = neigh.id,
                squareId = sq.id
            ),
            emptyList()
        )
        val qrBmp = QrCodeGenerator.generate(citizen.id, 240)
        assertEquals(240, qrBmp.width)
        val cardPng = CitizenCardGenerator.exportCardPng(context, citizen, neigh.name, sq.name, settings)
        assertTrue(cardPng.exists() && cardPng.length() > 500L)

        // 3b. Dual emblem assets (without Basmala for ID card, with Basmala for header) + custom font header
        val noBasmala = OfficialAssetsHelper.loadEagleEmblemBitmap(context, settings)
        val withBasmala = OfficialAssetsHelper.loadEagleWithBasmalaBitmap(context)
        assertTrue(noBasmala.width > 100 && noBasmala.height > 80)
        assertTrue(withBasmala.width > 100 && withBasmala.height > 80)
        val customHeaderBmp = OfficialAssetsHelper.loadOrRenderOfficialHeaderBitmap(
            context,
            (settings ?: AppSettingsEntity()).copy(
                headerRightText = "الجمهورية اليمنية\nمكتب الشؤون الاجتماعية",
                headerLeftText = "سجل الفهد\nكشف رسمي",
                headerFontFamily = "Amiri",
                headerFontSizeSp = 16f
            )
        )
        assertTrue(customHeaderBmp.width == 1600 && customHeaderBmp.height >= 220)

        // 4. Backup ZIP & SHA-256 verification
        val backupZip = BackupManager.exportBackupZip(context, db, admin)
        assertTrue(backupZip.exists())
        val inspection = BackupManager.inspectAndVerifyBackup(context, android.net.Uri.fromFile(backupZip))
        assertEquals(1, inspection.manifest.peopleCount)
    }

    @Test
    fun `4000 citizens scale insertion and indexed pagination performance`() = runBlocking {
        val neigh = db.neighborhoodDao().getAllActiveOnce().first()
        val sq = db.residentialSquareDao().getAllActiveOnce().first { it.neighborhoodId == neigh.id }

        val bulk = (1..4000).map { i ->
            PersonEntity(
                id = SecurityUtils.newUuid(),
                citizenCode = "FAHD-BULK-$i",
                fullName = "المواطن رقم $i",
                nationalId = "ID-$i",
                phone = "77${1000000 + i}",
                neighborhoodId = neigh.id,
                squareId = sq.id,
                familySize = (i % 7) + 1
            )
        }
        db.personDao().upsertAll(bulk)
        assertEquals(4000, db.personDao().countActive())

        val page0 = repo.searchPeoplePaged(limit = 50, offset = 0)
        assertEquals(50, page0.size)

        val specific = repo.searchPeoplePaged(query = "3999", limit = 50, offset = 0)
        assertTrue(specific.any { it.citizenCode == "FAHD-BULK-3999" })
    }

    @Test
    fun `abbeb2031 admin can manage users with granular permissions and authenticate by email`() = runBlocking {
        // Verified Google Admin login creates/updates local admin linked to abbeb2031@gmail.com
        val cloudAdmin = repo.loginAsVerifiedCloudAdmin("abbeb2031@gmail.com", "مدير النظام الرئيسي")
        assertEquals(UserRole.ADMIN.dbValue, cloudAdmin.role)
        assertEquals("abbeb2031@gmail.com", cloudAdmin.email)

        // Admin can also authenticate with email + password
        val byEmail = repo.authenticate("abbeb2031@gmail.com", "Aa0012365400")
        assertEquals(cloudAdmin.id, byEmail.id)

        // Admin creates a user with custom granular permissions
        val customPerms = UserPermissions(
            canAddCitizen = true,
            canEditCitizen = true,
            canDeleteCitizen = false,
            canViewReports = true,
            canExport = false,
            canManageAid = true,
            canManageAreas = true,
            canManageUsers = true,
            canBackupRestore = false
        )
        val delegatedUser = repo.saveUser(
            actor = cloudAdmin,
            existingId = null,
            username = "supervisor_user",
            fullName = "مشرف ميداني",
            phone = "775001122",
            role = UserRole.EMPLOYEE,
            plainPassword = "Sup@1234",
            permissions = customPerms,
            isActive = true,
            email = "supervisor@sijil.test"
        )
        val parsed = repo.parsePermissions(delegatedUser)
        assertTrue(parsed.canEditCitizen)
        assertTrue(parsed.canManageUsers)
        assertFalse(parsed.canDeleteCitizen)
        assertFalse(parsed.canBackupRestore)

        // Delegated user with canManageUsers CAN create regular employees
        val subEmployee = repo.saveUser(
            actor = delegatedUser,
            existingId = null,
            username = "sub_emp",
            fullName = "موظف فرعي",
            phone = "775009988",
            role = UserRole.EMPLOYEE,
            plainPassword = "Sub@1234",
            permissions = UserPermissions.forRole(UserRole.EMPLOYEE),
            isActive = true
        )
        assertEquals("sub_emp", subEmployee.username)

        // Delegated non-admin user CANNOT escalate someone to ADMIN
        try {
            repo.saveUser(
                actor = delegatedUser,
                existingId = subEmployee.id,
                username = "sub_emp",
                fullName = "محاولة ترقية",
                phone = "",
                role = UserRole.ADMIN,
                plainPassword = null,
                permissions = UserPermissions.forRole(UserRole.ADMIN),
                isActive = true
            )
            fail("Non-admin must not promote user to ADMIN")
        } catch (_: SecurityException) {
            // Expected
        }
    }

    @Test
    fun `excel importer parses xlsx with custom header row selection, column mapping, and imports citizens`() = runBlocking {
        val admin = repo.authenticate("abbeb34", "Aa0012365400")
        val neigh = db.neighborhoodDao().getAllActiveOnce().first()
        val sq = db.residentialSquareDao().getAllActiveOnce().first { it.neighborhoodId == neigh.id }

        // Build an authentic multi-row .xlsx file using ExcelXlsxGenerator (headers on row 5 / 0-based index 4)
        val exportReport = ReportTableData(
            title = "كشف استيراد تجريبي",
            subtitle = "بيانات المواطنين",
            headers = listOf("الاسم الكامل", "رقم الهوية", "رقم الهاتف", "الحارة", "المربع", "عدد أفراد الأسرة", "نوع السكن"),
            rows = listOf(
                listOf("فهد سالم العامري", "10203040", "771234567", neigh.name, sq.name, "6", "مالك"),
                listOf("سعيد ناصر اليافعي", "50607080", "772345678", "حارة النصر الجديدة", "مربع النصر أ", "4", "مستأجر"),
                listOf("سعيد ناصر اليافعي مكرر", "50607080", "772345679", neigh.name, sq.name, "3", "مستأجر"), // Duplicate ID
                listOf("", "99999999", "770000000", neigh.name, sq.name, "2", "مالك") // Missing fullName -> skippedInvalid
            )
        )
        val xlsxFile = ExcelXlsxGenerator.generateXlsx(context, exportReport, db.appSettingsDao().getSettingsOnce(), landscape = false)
        val workbook = xlsxFile.inputStream().use { ExcelCitizensImporter.parseStream(it, xlsxFile.name) }

        assertEquals(1, workbook.sheets.size)
        val sheet = workbook.sheets.first()
        val detectedHeaderIdx = ExcelCitizensImporter.suggestHeaderRowIndex(sheet.rows)
        assertTrue("Expected header row >= 4, got $detectedHeaderIdx", detectedHeaderIdx >= 4)
        assertTrue(sheet.rows[detectedHeaderIdx].contains("الاسم الكامل"))

        val mapping = ExcelCitizensImporter.suggestColumnMapping(sheet.rows[detectedHeaderIdx])
        assertNotNull(mapping[CitizenImportField.FULL_NAME])
        assertNotNull(mapping[CitizenImportField.NATIONAL_ID])
        assertNotNull(mapping[CitizenImportField.PHONE])
        assertNotNull(mapping[CitizenImportField.NEIGHBORHOOD])
        assertNotNull(mapping[CitizenImportField.SQUARE])
        assertNotNull(mapping[CitizenImportField.FAMILY_SIZE])

        val importResult = repo.importCitizensFromExcel(
            actor = admin,
            rows = sheet.rows,
            headerRowIndex = detectedHeaderIdx,
            mapping = mapping,
            defaultNeighborhoodId = neigh.id,
            defaultSquareId = sq.id,
            createMissingAreas = true,
            skipDuplicateIds = true
        )

        assertEquals(2, importResult.importedCount)
        assertEquals(1, importResult.skippedDuplicates)
        assertEquals(1, importResult.skippedInvalid)
        assertEquals(2, importResult.createdAreasCount) // "حارة النصر الجديدة" + "مربع النصر أ"

        val allCitizens = db.personDao().getAllActiveOnce()
        val fahd = allCitizens.firstOrNull { it.nationalId == "10203040" }
        assertNotNull(fahd)
        assertEquals("فهد سالم العامري", fahd!!.fullName)
        assertEquals(6, fahd.familySize)
        assertEquals(HousingType.OWNER.arabicLabel, fahd.housingType)

        val saeed = allCitizens.firstOrNull { it.nationalId == "50607080" }
        assertNotNull(saeed)
        assertEquals("سعيد ناصر اليافعي", saeed!!.fullName)
    }

    @Test
    fun `excel importer handles serial dates, scientific notation, bidirectional mapping, and updates existing citizens`() = runBlocking {
        val admin = repo.authenticate("abbeb34", "Aa0012365400")
        val neigh = db.neighborhoodDao().getAllActiveOnce().first()
        val sq = db.residentialSquareDao().getAllActiveOnce().first { it.neighborhoodId == neigh.id }

        // 1. Seed existing citizen with matching nationalId
        val initialCitizen = repo.saveCitizen(
            actor = admin,
            existingId = null,
            personDraft = PersonEntity(
                id = SecurityUtils.newUuid(),
                citizenCode = "FAHD-UPD-1",
                fullName = "أحمد صالح القديم",
                idType = DocumentType.NATIONAL_ID.arabicLabel,
                nationalId = "1010101010",
                idIssueDate = "2020-01-01",
                phone = "770000001",
                occupation = "عامل",
                neighborhoodId = neigh.id,
                squareId = sq.id,
                familySize = 3
            ),
            documents = emptyList()
        )
        assertEquals(1, initialCitizen.syncVersion)

        // 2. Verify Excel serial date conversion (45321 -> 2024-01-30)
        assertEquals("2024-01-30", ExcelCitizensImporter.formatExcelDateIfSerial("45321"))
        assertEquals("2023-05-10", ExcelCitizensImporter.formatExcelDateIfSerial("2023-05-10"))

        // 3. Build custom .xlsx with title rows + shared strings + scientific notation phone + Excel serial date
        val xlsxBytes = java.io.ByteArrayOutputStream().use { baos ->
            java.util.zip.ZipOutputStream(baos).use { zos ->
                fun putEntry(path: String, content: String) {
                    zos.putNextEntry(java.util.zip.ZipEntry(path))
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                putEntry(
                    "[Content_Types].xml",
                    """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/></Types>"""
                )
                putEntry(
                    "xl/workbook.xml",
                    """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="كشف المواطنين" sheetId="1" r:id="rId1"/></sheets></workbook>"""
                )
                putEntry(
                    "xl/_rels/workbook.xml.rels",
                    """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="/xl/worksheets/sheet1.xml"/></Relationships>"""
                )
                putEntry(
                    "xl/sharedStrings.xml",
                    """<?xml version="1.0" encoding="UTF-8"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="9" uniqueCount="9">
                        <si><t>عنوان الكشف</t></si>
                        <si><t>الاسم الكامل</t></si>
                        <si><t>رقم الهوية</t></si>
                        <si><t>رقم الهاتف</t></si>
                        <si><t>تاريخ إصدار الهوية</t></si>
                        <si><t>المهنة</t></si>
                        <si><t>أحمد صالح المحدث</t></si>
                        <si><t>مهندس مدني</t></si>
                        <si><t>1010101010</t></si>
                    </sst>""".trimIndent()
                )
                putEntry(
                    "xl/worksheets/sheet1.xml",
                    """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                        <row r="1"><c r="A1" t="s"><v>0</v></c></row>
                        <row r="2">
                            <c r="A2" t="s"><v>1</v></c>
                            <c r="B2" t="s"><v>2</v></c>
                            <c r="C2" t="s"><v>3</v></c>
                            <c r="D2" t="s"><v>4</v></c>
                            <c r="E2" t="s"><v>5</v></c>
                        </row>
                        <row r="3">
                            <c r="A3" t="s"><v>6</v></c>
                            <c r="B3" t="s"><v>8</v></c>
                            <c r="C3"><v>7.71234567E8</v></c>
                            <c r="D3"><v>45321</v></c>
                            <c r="E3" t="s"><v>7</v></c>
                        </row>
                    </sheetData></worksheet>""".trimIndent()
                )
            }
            baos.toByteArray()
        }

        val workbook = java.io.ByteArrayInputStream(xlsxBytes).use {
            ExcelCitizensImporter.parseStream(it, "custom.xlsx")
        }
        val sheet = workbook.sheets.first()
        assertEquals("كشف المواطنين", sheet.name)

        // Row 1 (0-based index 1) is the header row
        val headerIdx = ExcelCitizensImporter.suggestHeaderRowIndex(sheet.rows)
        assertEquals(1, headerIdx)

        // Check scientific notation phone parsing in data row
        assertEquals("771234567", sheet.rows[2][2])

        // Bidirectional mapping verification
        val colToField = ExcelCitizensImporter.suggestColumnToFieldMapping(sheet.rows[headerIdx])
        assertEquals(CitizenImportField.FULL_NAME, colToField[0])
        assertEquals(CitizenImportField.NATIONAL_ID, colToField[1])
        assertEquals(CitizenImportField.PHONE, colToField[2])
        assertEquals(CitizenImportField.ID_ISSUE_DATE, colToField[3])
        assertEquals(CitizenImportField.OCCUPATION, colToField[4])

        val fieldToCol = ExcelCitizensImporter.toFieldToColumnMapping(colToField)
        assertEquals(3, fieldToCol[CitizenImportField.ID_ISSUE_DATE])

        // 4. Import with updateExistingOnDuplicateId = true
        val result = repo.importCitizensFromExcel(
            actor = admin,
            rows = sheet.rows,
            headerRowIndex = headerIdx,
            mapping = fieldToCol,
            defaultNeighborhoodId = neigh.id,
            defaultSquareId = sq.id,
            createMissingAreas = true,
            skipDuplicateIds = true,
            updateExistingOnDuplicateId = true
        )

        assertEquals(0, result.importedCount)
        assertEquals(1, result.updatedCount)
        assertEquals(0, result.skippedDuplicates)

        val refreshed = db.personDao().findById(initialCitizen.id)
        assertNotNull(refreshed)
        assertEquals("أحمد صالح المحدث", refreshed!!.fullName)
        assertEquals("771234567", refreshed.phone)
        assertEquals("2024-01-30", refreshed.idIssueDate)
        assertEquals("مهندس مدني", refreshed.occupation)
        assertEquals(2, refreshed.syncVersion)
    }
}

