package com.example.data.remote

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.local.*
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FirestoreCloudRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private fun randomId(prefix: String): String =
        "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(12)}"

    @Test
    fun verifiedBootstrapAdminCanManageAllCollectionsAndHeaderSettings() = runBlocking {
        val adminUid = signInVerifiedUser(FirestoreCloudRepository.BOOTSTRAP_ADMIN_EMAIL)
        val repo = FirestoreCloudRepository(firestore, auth)
        val neighId = randomId("neigh")
        val squareId = randomId("sq")
        val personId = randomId("cit")
        val programId = randomId("prog")
        val distId = randomId("dist")
        val logId = randomId("log")

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.ensureUserProfile("مدير النظام", FirestoreCloudRepository.BOOTSTRAP_ADMIN_EMAIL, "abbeb34")
            repo.upsertNeighborhood(
                NeighborhoodEntity(
                    id = neighId,
                    name = "حارة الفهد",
                    governorate = "عدن",
                    district = "المنصورة"
                )
            )
            repo.upsertSquare(
                ResidentialSquareEntity(
                    id = squareId,
                    neighborhoodId = neighId,
                    name = "المربع الأول"
                )
            )
            repo.upsertCitizen(
                PersonEntity(
                    id = personId,
                    citizenCode = "FAHD-ADM1",
                    fullName = "عبدالله صالح المحمدي",
                    nationalId = "11223344",
                    neighborhoodId = neighId,
                    squareId = squareId,
                    familySize = 4
                )
            )
            repo.upsertAidProgram(
                AidProgramEntity(
                    id = programId,
                    name = "برنامج الإغاثة الطارئة",
                    aidType = "غذائية",
                    distributionDate = "2026-04-01"
                )
            )
            repo.upsertAidDistribution(
                AidDistributionEntity(
                    id = distId,
                    programId = programId,
                    personId = personId,
                    quantityDelivered = 1.0,
                    receivedStatus = "تم الاستلام"
                )
            )
            repo.upsertSettings(
                AppSettingsEntity(
                    organizationTitle = "سجل الفهد الميداني",
                    headerRightText = "الجمهورية اليمنية\nعدن - المنصورة",
                    headerLeftText = "كشوفات واستمارات",
                    headerFontFamily = "Amiri",
                    headerFontSizeSp = 16f
                )
            )
            repo.appendAuditLog(
                AuditLogEntity(
                    id = logId,
                    actorUsername = "abbeb34",
                    actionType = "SYNC",
                    tableName = "people",
                    recordId = personId,
                    summaryAr = "مزامنة اختبار مدير النظام"
                )
            )
        }

        val fetched = withTimeout(DEFAULT_TIMEOUT_MS) { repo.getCitizen(personId) }
        assertNotNull(fetched)
        assertEquals(adminUid, fetched!!.ownerUid)
        assertEquals(personId, fetched.id)
        assertEquals("عبدالله صالح المحمدي", fetched.fullName)
        assertFalse(fetched.isDeleted)

        val observedSettings = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeSettings().first { it != null }
        }
        assertEquals("Amiri", observedSettings!!.headerFontFamily)
        assertEquals(16.0, observedSettings.headerFontSizeSp, 0.01)

        val customUserId = randomId("emp_prof")
        val customPerms = UserPermissions(
            canAddCitizen = true,
            canEditCitizen = true,
            canDeleteCitizen = false,
            canViewReports = true,
            canExport = true,
            canManageAid = true,
            canManageAreas = false,
            canManageUsers = false,
            canBackupRestore = false
        )
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertProfile(
                ProfileEntity(
                    id = customUserId,
                    username = "field_sup",
                    fullName = "مشرف ميداني",
                    email = "sup@sijil.test",
                    phone = "775554443",
                    role = UserRole.EMPLOYEE.dbValue,
                    passwordHash = "",
                    permissionsJson = kotlinx.serialization.json.Json.encodeToString(UserPermissions.serializer(), customPerms),
                    isActive = true
                ),
                email = "sup@sijil.test"
            )
        }
        val createdProfSnap = withTimeout(DEFAULT_TIMEOUT_MS) {
            firestore.collection(FirestoreCloudRepository.COL_PROFILES).document(customUserId).get().await()
        }
        assertTrue(createdProfSnap.exists())
        assertEquals("sup@sijil.test", createdProfSnap.getString("email"))
        assertEquals(true, createdProfSnap.getBoolean("canEditCitizen"))
        assertEquals(true, createdProfSnap.getBoolean("canManageAid"))
        assertEquals(false, createdProfSnap.getBoolean("canDeleteCitizen"))

        val observedCitizens = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeCitizens().first { list -> list.any { it.id == personId } }
        }
        assertTrue(observedCitizens.any { it.id == personId })
    }

    @Test
    fun employeeCanAddAndObserveOwnCitizenWithinExistingAreaButCannotEditAreasOrUpdateCitizen() = runBlocking {
        // Seed valid parent neighborhood & square using Bootstrap Admin
        signInVerifiedUser(FirestoreCloudRepository.BOOTSTRAP_ADMIN_EMAIL)
        val adminRepo = FirestoreCloudRepository(firestore, auth)
        val neighId = randomId("neigh_emp")
        val squareId = randomId("sq_emp")
        withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.ensureUserProfile("مدير النظام", FirestoreCloudRepository.BOOTSTRAP_ADMIN_EMAIL)
            adminRepo.upsertNeighborhood(NeighborhoodEntity(id = neighId, name = "حارة الموظف"))
            adminRepo.upsertSquare(ResidentialSquareEntity(id = squareId, neighborhoodId = neighId, name = "مربع الموظف"))
        }

        // Switch to standard employee
        val empUid = signInTestUser("emp1@sijil.test")
        val empRepo = FirestoreCloudRepository(firestore, auth)
        val personId = randomId("cit_emp")

        withTimeout(DEFAULT_TIMEOUT_MS) {
            empRepo.ensureUserProfile("موظف الميدان", "emp1@sijil.test")
        }

        // Employee CAN create a new citizen under existing area
        withTimeout(DEFAULT_TIMEOUT_MS) {
            empRepo.upsertCitizen(
                PersonEntity(
                    id = personId,
                    citizenCode = "FAHD-EMP1",
                    fullName = "ناصر سعيد العامري",
                    nationalId = "99887766",
                    neighborhoodId = neighId,
                    squareId = squareId,
                    familySize = 6
                )
            )
        }

        // Employee CAN observe own citizens (query scoped with ownerUid)
        val observed = withTimeout(FLOW_TIMEOUT_MS) {
            empRepo.observeCitizens().first { list -> list.any { it.id == personId } }
        }
        assertEquals(empUid, observed.first { it.id == personId }.ownerUid)

        // Employee CANNOT modify areas or global app_settings
        var deniedArea: FirebaseFirestoreException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                empRepo.upsertNeighborhood(NeighborhoodEntity(id = randomId("neigh_bad"), name = "تجاوز"))
            }
        } catch (e: Exception) {
            deniedArea = e.unwrapFirestoreException()
        }
        assertNotNull(deniedArea)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, deniedArea!!.code)

        var deniedSettings: FirebaseFirestoreException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                empRepo.upsertSettings(AppSettingsEntity(organizationTitle = "إعداد غير مصرح"))
            }
        } catch (e: Exception) {
            deniedSettings = e.unwrapFirestoreException()
        }
        assertNotNull(deniedSettings)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, deniedSettings!!.code)

        // Employee CANNOT update existing citizen (canEditCitizen == false)
        var deniedUpdate: FirebaseFirestoreException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                empRepo.upsertCitizen(
                    PersonEntity(
                        id = personId,
                        citizenCode = "FAHD-EMP1",
                        fullName = "محاولة تعديل",
                        nationalId = "99887766",
                        neighborhoodId = neighId,
                        squareId = squareId,
                        familySize = 6
                    )
                )
            }
        } catch (e: Exception) {
            deniedUpdate = e.unwrapFirestoreException()
        }
        assertNotNull(deniedUpdate)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, deniedUpdate!!.code)
    }

    @Test
    fun unauthenticatedCallerIsRejectedBySecurityRules() = runBlocking {
        auth.signOut()
        val repo = FirestoreCloudRepository(firestore, auth)

        var caughtState: Exception? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) { repo.observeCitizens().first() }
        } catch (e: Exception) {
            caughtState = e
        }
        assertNotNull(caughtState)

        var caughtDirectEx: Throwable? = null
        var firestoreEx: FirebaseFirestoreException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection(FirestoreCloudRepository.COL_PEOPLE).get().await()
            }
        } catch (e: Throwable) {
            caughtDirectEx = e
            firestoreEx = e.unwrapFirestoreException()
        }
        assertNotNull("Expected PERMISSION_DENIED from Firestore rules, got: $caughtDirectEx", firestoreEx)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx!!.code)
    }

    @Test
    fun crossUserAccessToAnotherEmployeeCitizenIsRejectedWithPermissionDenied() = runBlocking {
        signInVerifiedUser(FirestoreCloudRepository.BOOTSTRAP_ADMIN_EMAIL)
        val adminRepo = FirestoreCloudRepository(firestore, auth)
        val neighId = randomId("neigh_cross")
        val squareId = randomId("sq_cross")
        withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.upsertNeighborhood(NeighborhoodEntity(id = neighId, name = "حارة مشتركة"))
            adminRepo.upsertSquare(ResidentialSquareEntity(id = squareId, neighborhoodId = neighId, name = "مربع مشترك"))
        }

        signInTestUser("owner1@sijil.test")
        val aliceRepo = FirestoreCloudRepository(firestore, auth)
        val personId = randomId("cit_cross")
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.ensureUserProfile("المستخدم الأول", "owner1@sijil.test")
            aliceRepo.upsertCitizen(
                PersonEntity(
                    id = personId,
                    citizenCode = "FAHD-CROSS1",
                    fullName = "سجل خاص بمستخدم أول",
                    nationalId = "55443322",
                    neighborhoodId = neighId,
                    squareId = squareId,
                    familySize = 3
                )
            )
        }

        signInTestUser("other2@sijil.test")
        var denied: FirebaseFirestoreException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection(FirestoreCloudRepository.COL_PEOPLE).document(personId).get().await()
            }
        } catch (e: Exception) {
            denied = e.unwrapFirestoreException()
        }
        assertNotNull(denied)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, denied!!.code)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val FLOW_TIMEOUT_MS = 8_000L
    }
}
