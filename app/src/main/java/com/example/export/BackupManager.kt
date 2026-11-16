package com.example.export

import android.content.Context
import android.net.Uri
import com.example.data.local.*
import com.example.util.SecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BackupPayload(
    val version: Int = 1,
    val createdAtIso: String,
    val createdByUsername: String,
    val profiles: List<ProfileEntity>,
    val neighborhoods: List<NeighborhoodEntity>,
    val residentialSquares: List<ResidentialSquareEntity>,
    val representatives: List<RepresentativeHistoryEntity>,
    val people: List<PersonEntity>,
    val identityDocuments: List<IdentityDocumentEntity>,
    val aidPrograms: List<AidProgramEntity>,
    val aidBeneficiaries: List<AidBeneficiaryEntity>,
    val aidDistributions: List<AidDistributionEntity>,
    val appSettings: AppSettingsEntity?,
    val auditLogs: List<AuditLogEntity>,
    val syncMetadata: List<SyncMetadataEntity>
)

@Serializable
data class BackupManifest(
    val schemaVersion: Int = 1,
    val appName: String = "سجل الفهد",
    val createdAtIso: String,
    val payloadSha256: String,
    val peopleCount: Int,
    val documentsCount: Int,
    val neighborhoodsCount: Int,
    val aidProgramsCount: Int
)

data class BackupInspectionResult(
    val manifest: BackupManifest,
    val payload: BackupPayload,
    val extractedImagesDir: File
)

object BackupManager {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun exportBackupZip(
        context: Context,
        db: AppDatabase,
        actor: ProfileEntity
    ): File = withContext(Dispatchers.IO) {
        if (UserRole.fromDb(actor.role) != UserRole.ADMIN) {
            throw SecurityException("النسخ الاحتياطي متاح لمدير النظام فقط")
        }

        val payload = BackupPayload(
            createdAtIso = SecurityUtils.nowIso(),
            createdByUsername = actor.username,
            profiles = db.profileDao().getAllOnce(),
            neighborhoods = db.neighborhoodDao().getAllRaw(),
            residentialSquares = db.residentialSquareDao().getAllRaw(),
            representatives = db.representativeHistoryDao().getAllRaw(),
            people = db.personDao().getAllRaw(),
            identityDocuments = db.identityDocumentDao().getAllRaw(),
            aidPrograms = db.aidDao().getAllProgramsRaw(),
            aidBeneficiaries = db.aidDao().getAllBeneficiariesRaw(),
            aidDistributions = db.aidDao().getAllDistributionsRaw(),
            appSettings = db.appSettingsDao().getSettingsOnce(),
            auditLogs = db.auditLogDao().getAllRaw(),
            syncMetadata = db.syncMetadataDao().getAllRaw()
        )

        val payloadJson = json.encodeToString(payload)
        val payloadBytes = payloadJson.toByteArray(Charsets.UTF_8)
        val checksum = SecurityUtils.sha256Hex(payloadBytes)

        val manifest = BackupManifest(
            createdAtIso = payload.createdAtIso,
            payloadSha256 = checksum,
            peopleCount = payload.people.count { !it.isDeleted },
            documentsCount = payload.identityDocuments.count { !it.isDeleted },
            neighborhoodsCount = payload.neighborhoods.count { !it.isDeleted },
            aidProgramsCount = payload.aidPrograms.count { !it.isDeleted }
        )
        val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)

        val dir = File(context.filesDir, "backups").apply { mkdirs() }
        val outFile = File(dir, "sijil_alfahd_backup_${System.currentTimeMillis()}.zip")

        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestBytes)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("payload.json"))
            zip.write(payloadBytes)
            zip.closeEntry()

            // Bundle all local ID document images
            val addedPaths = mutableSetOf<String>()
            payload.identityDocuments.forEach { doc ->
                val f = File(doc.localFilePath)
                if (f.exists() && addedPaths.add(f.name)) {
                    zip.putNextEntry(ZipEntry("images/${f.name}"))
                    FileInputStream(f).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            // Bundle custom official header & eagle emblem if configured
            listOfNotNull(
                payload.appSettings?.officialHeaderLocalPath,
                payload.appSettings?.eagleEmblemLocalPath
            ).forEach { path ->
                val f = File(path)
                if (f.exists() && addedPaths.add("branding_${f.name}")) {
                    zip.putNextEntry(ZipEntry("branding/${f.name}"))
                    FileInputStream(f).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }

        db.auditLogDao().insert(
            AuditLogEntity(
                id = SecurityUtils.newUuid(),
                actorProfileId = actor.id,
                actorUsername = actor.username,
                actionType = "BACKUP",
                tableName = "all_tables",
                recordId = outFile.name,
                summaryAr = "إنشاء نسخة احتياطية كاملة (${manifest.peopleCount} مواطن، ${manifest.documentsCount} وثيقة)"
            )
        )
        outFile
    }

    suspend fun inspectAndVerifyBackup(
        context: Context,
        sourceUri: Uri
    ): BackupInspectionResult = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "restore_tmp_${System.currentTimeMillis()}").apply { mkdirs() }
        var manifestBytes: ByteArray? = null
        var payloadBytes: ByteArray? = null

        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!name.contains("..")) {
                        when {
                            name == "manifest.json" -> manifestBytes = zis.readBytes()
                            name == "payload.json" -> payloadBytes = zis.readBytes()
                            name.startsWith("images/") || name.startsWith("branding/") -> {
                                val target = File(tempDir, name).apply { parentFile?.mkdirs() }
                                FileOutputStream(target).use { zis.copyTo(it) }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } ?: throw IllegalStateException("تعذر فتح ملف النسخة الاحتياطية")

        val mBytes = manifestBytes ?: throw IllegalStateException("ملف التحقق manifest.json غير موجود")
        val pBytes = payloadBytes ?: throw IllegalStateException("ملف البيانات payload.json غير موجود")

        val manifest = json.decodeFromString<BackupManifest>(mBytes.toString(Charsets.UTF_8))
        val actualSha = SecurityUtils.sha256Hex(pBytes)
        if (!actualSha.equals(manifest.payloadSha256, ignoreCase = true)) {
            throw SecurityException("فشل التحقق من سلامة النسخة الاحتياطية: الملف تالف أو تم تعديله")
        }
        val payload = json.decodeFromString<BackupPayload>(pBytes.toString(Charsets.UTF_8))
        BackupInspectionResult(manifest, payload, tempDir)
    }

    suspend fun restoreVerifiedBackup(
        context: Context,
        db: AppDatabase,
        actor: ProfileEntity,
        inspection: BackupInspectionResult
    ) = withContext(Dispatchers.IO) {
        if (UserRole.fromDb(actor.role) != UserRole.ADMIN) {
            throw SecurityException("استعادة النسخة الاحتياطية متاحة لمدير النظام فقط")
        }

        // Restore bundled images into app-private folders and remap paths
        val idDocsDir = File(context.filesDir, "id_documents").apply { mkdirs() }
        val brandingDir = File(context.filesDir, "branding").apply { mkdirs() }

        val extractedImages = File(inspection.extractedImagesDir, "images")
        if (extractedImages.exists()) {
            extractedImages.listFiles()?.forEach { src ->
                src.copyTo(File(idDocsDir, src.name), overwrite = true)
            }
        }
        val extractedBranding = File(inspection.extractedImagesDir, "branding")
        if (extractedBranding.exists()) {
            extractedBranding.listFiles()?.forEach { src ->
                src.copyTo(File(brandingDir, src.name), overwrite = true)
            }
        }

        val p = inspection.payload
        val remappedDocs = p.identityDocuments.map { doc ->
            val fileName = File(doc.localFilePath).name
            val restoredFile = File(idDocsDir, fileName)
            doc.copy(localFilePath = if (restoredFile.exists()) restoredFile.absolutePath else doc.localFilePath)
        }
        val remappedPeople = p.people.map { person ->
            val path = person.photoLocalPath
            if (path.isNullOrBlank()) person else {
                val restored = File(idDocsDir, File(path).name)
                person.copy(photoLocalPath = if (restored.exists()) restored.absolutePath else path)
            }
        }

        db.clearAllTables()
        db.profileDao().upsertAll(p.profiles)
        db.neighborhoodDao().upsertAll(p.neighborhoods)
        db.residentialSquareDao().upsertAll(p.residentialSquares)
        db.representativeHistoryDao().upsertAll(p.representatives)
        db.personDao().upsertAll(remappedPeople)
        db.identityDocumentDao().upsertAll(remappedDocs)
        db.aidDao().upsertPrograms(p.aidPrograms)
        db.aidDao().upsertBeneficiaries(p.aidBeneficiaries)
        db.aidDao().upsertDistributions(p.aidDistributions)
        p.appSettings?.let { settings ->
            val headerName = settings.officialHeaderLocalPath?.let { File(it).name }
            val eagleName = settings.eagleEmblemLocalPath?.let { File(it).name }
            db.appSettingsDao().upsert(
                settings.copy(
                    officialHeaderLocalPath = headerName?.let { File(brandingDir, it).takeIf { f -> f.exists() }?.absolutePath },
                    eagleEmblemLocalPath = eagleName?.let { File(brandingDir, it).takeIf { f -> f.exists() }?.absolutePath }
                )
            )
        }
        db.auditLogDao().upsertAll(p.auditLogs)
        db.syncMetadataDao().upsertAll(p.syncMetadata)

        db.auditLogDao().insert(
            AuditLogEntity(
                id = SecurityUtils.newUuid(),
                actorProfileId = actor.id,
                actorUsername = actor.username,
                actionType = "RESTORE",
                tableName = "all_tables",
                recordId = inspection.manifest.createdAtIso,
                summaryAr = "استعادة نسخة احتياطية كاملة بتاريخ ${inspection.manifest.createdAtIso}"
            )
        )
        inspection.extractedImagesDir.deleteRecursively()
    }
}
