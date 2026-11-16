package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.util.SecurityUtils
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Database(
    entities = [
        ProfileEntity::class,
        NeighborhoodEntity::class,
        ResidentialSquareEntity::class,
        RepresentativeHistoryEntity::class,
        PersonEntity::class,
        IdentityDocumentEntity::class,
        AidProgramEntity::class,
        AidBeneficiaryEntity::class,
        AidDistributionEntity::class,
        AppSettingsEntity::class,
        AuditLogEntity::class,
        SyncMetadataEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun neighborhoodDao(): NeighborhoodDao
    abstract fun residentialSquareDao(): ResidentialSquareDao
    abstract fun representativeHistoryDao(): RepresentativeHistoryDao
    abstract fun personDao(): PersonDao
    abstract fun identityDocumentDao(): IdentityDocumentDao
    abstract fun aidDao(): AidDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun syncMetadataDao(): SyncMetadataDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN headerRightText TEXT NOT NULL DEFAULT 'الجمهورية اليمنية\nمحافظة عدن - مديرية المنصورة\nسجل الفهد الميداني'")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN headerLeftText TEXT NOT NULL DEFAULT 'نظام سجل الفهد\nإدارة السكان والمساعدات\nالوثائق الرسمية'")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN headerFontFamily TEXT NOT NULL DEFAULT 'Cairo'")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN headerFontSizeSp REAL NOT NULL DEFAULT 13.0")
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN email TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sijil_alfahd.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

object DatabaseSeeder {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun ensureSeeded(db: AppDatabase) {
        val profileCount = db.profileDao().count()
        if (profileCount == 0) {
            val adminId = SecurityUtils.newUuid()
            val empId = SecurityUtils.newUuid()
            val adminPerms = json.encodeToString(UserPermissions.forRole(UserRole.ADMIN))
            val empPerms = json.encodeToString(UserPermissions.forRole(UserRole.EMPLOYEE))

            db.profileDao().upsertAll(
                listOf(
                    ProfileEntity(
                        id = adminId,
                        username = "abbeb34",
                        fullName = "مدير النظام الرئيسي",
                        email = "abbeb2031@gmail.com",
                        phone = "770000001",
                        role = UserRole.ADMIN.dbValue,
                        passwordHash = SecurityUtils.hashPassword("Aa0012365400"),
                        permissionsJson = adminPerms,
                        isActive = true
                    ),
                    ProfileEntity(
                        id = empId,
                        username = "emp1",
                        fullName = "موظف السجل الميداني",
                        phone = "770000002",
                        role = UserRole.EMPLOYEE.dbValue,
                        passwordHash = SecurityUtils.hashPassword("Emp@123"),
                        permissionsJson = empPerms,
                        isActive = true,
                        createdBy = adminId
                    )
                )
            )

            // Default neighborhoods & squares so dropdowns work immediately on first use
            val n1 = NeighborhoodEntity(
                id = SecurityUtils.newUuid(),
                name = "حارة الفهد المركزية",
                governorate = "عدن",
                district = "المنصورة",
                aqilName = "أحمد صالح الميسري",
                aqilPhone = "771111111",
                createdBy = adminId,
                createdByName = "مدير النظام الرئيسي"
            )
            val n2 = NeighborhoodEntity(
                id = SecurityUtils.newUuid(),
                name = "حارة السلام الشرقية",
                governorate = "عدن",
                district = "المنصورة",
                aqilName = "سالم عبده الحربي",
                aqilPhone = "772222222",
                createdBy = adminId,
                createdByName = "مدير النظام الرئيسي"
            )
            db.neighborhoodDao().upsertAll(listOf(n1, n2))

            db.residentialSquareDao().upsertAll(
                listOf(
                    ResidentialSquareEntity(
                        id = SecurityUtils.newUuid(),
                        neighborhoodId = n1.id,
                        name = "مربع (أ)",
                        squareCode = "SQ-A1",
                        representativeName = "خالد ناصر العولقي",
                        representativePhone = "773333331",
                        createdBy = adminId,
                        createdByName = "مدير النظام الرئيسي"
                    ),
                    ResidentialSquareEntity(
                        id = SecurityUtils.newUuid(),
                        neighborhoodId = n1.id,
                        name = "مربع (ب)",
                        squareCode = "SQ-A2",
                        representativeName = "محمد علي اليافعي",
                        representativePhone = "773333332",
                        createdBy = adminId,
                        createdByName = "مدير النظام الرئيسي"
                    ),
                    ResidentialSquareEntity(
                        id = SecurityUtils.newUuid(),
                        neighborhoodId = n2.id,
                        name = "مربع (1)",
                        squareCode = "SQ-B1",
                        representativeName = "ياسر حسن باوزير",
                        representativePhone = "774444441",
                        createdBy = adminId,
                        createdByName = "مدير النظام الرئيسي"
                    ),
                    ResidentialSquareEntity(
                        id = SecurityUtils.newUuid(),
                        neighborhoodId = n2.id,
                        name = "مربع (2)",
                        squareCode = "SQ-B2",
                        representativeName = "طارق سعيد المقطري",
                        representativePhone = "774444442",
                        createdBy = adminId,
                        createdByName = "مدير النظام الرئيسي"
                    )
                )
            )
        } else {
            val targetAdmin = db.profileDao().findByUsername("abbeb34")
            if (targetAdmin == null) {
                val legacyAdmin = db.profileDao().findByUsername("admin")
                if (legacyAdmin != null) {
                    db.profileDao().upsert(
                        legacyAdmin.copy(
                            username = "abbeb34",
                            email = "abbeb2031@gmail.com",
                            passwordHash = SecurityUtils.hashPassword("Aa0012365400"),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            } else if (targetAdmin.email.isBlank() || targetAdmin.email.equals("abbeb2034@gmail.com", ignoreCase = true)) {
                db.profileDao().upsert(
                    targetAdmin.copy(
                        email = "abbeb2031@gmail.com",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }

        if (db.appSettingsDao().getSettingsOnce() == null) {
            db.appSettingsDao().upsert(AppSettingsEntity())
        }
    }
}
