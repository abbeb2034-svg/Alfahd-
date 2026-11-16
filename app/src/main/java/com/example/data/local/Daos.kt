package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY createdAt ASC")
    suspend fun getAllOnce(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE username = :username LIMIT 1")
    suspend fun findByUsername(username: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE LOWER(email) = LOWER(:email) AND email != '' LIMIT 1")
    suspend fun findByEmail(email: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(profiles: List<ProfileEntity>)

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int
}

@Dao
interface NeighborhoodDao {
    @Query("SELECT * FROM neighborhoods WHERE isDeleted = 0 ORDER BY name ASC")
    fun observeAllActive(): Flow<List<NeighborhoodEntity>>

    @Query("SELECT * FROM neighborhoods WHERE isDeleted = 0 ORDER BY name ASC")
    suspend fun getAllActiveOnce(): List<NeighborhoodEntity>

    @Query("SELECT * FROM neighborhoods")
    suspend fun getAllRaw(): List<NeighborhoodEntity>

    @Query("SELECT * FROM neighborhoods WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): NeighborhoodEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: NeighborhoodEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<NeighborhoodEntity>)

    @Query("UPDATE neighborhoods SET isDeleted = 1, updatedAt = :ts, syncStatus = 'Pending' WHERE id = :id")
    suspend fun softDelete(id: String, ts: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM neighborhoods WHERE isDeleted = 0")
    fun observeCount(): Flow<Int>
}

@Dao
interface ResidentialSquareDao {
    @Query("SELECT * FROM residential_squares WHERE isDeleted = 0 ORDER BY name ASC")
    fun observeAllActive(): Flow<List<ResidentialSquareEntity>>

    @Query("SELECT * FROM residential_squares WHERE isDeleted = 0 AND neighborhoodId = :neighborhoodId ORDER BY name ASC")
    fun observeByNeighborhood(neighborhoodId: String): Flow<List<ResidentialSquareEntity>>

    @Query("SELECT * FROM residential_squares WHERE isDeleted = 0 ORDER BY name ASC")
    suspend fun getAllActiveOnce(): List<ResidentialSquareEntity>

    @Query("SELECT * FROM residential_squares")
    suspend fun getAllRaw(): List<ResidentialSquareEntity>

    @Query("SELECT * FROM residential_squares WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ResidentialSquareEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ResidentialSquareEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ResidentialSquareEntity>)

    @Query("SELECT COUNT(*) FROM residential_squares WHERE isDeleted = 0")
    fun observeCount(): Flow<Int>
}

@Dao
interface RepresentativeHistoryDao {
    @Query("SELECT * FROM representatives ORDER BY assignedAt DESC")
    fun observeAll(): Flow<List<RepresentativeHistoryEntity>>

    @Query("SELECT * FROM representatives")
    suspend fun getAllRaw(): List<RepresentativeHistoryEntity>

    @Query("UPDATE representatives SET isCurrent = 0, unassignedAt = :ts WHERE roleType = :roleType AND neighborhoodId = :neighborhoodId AND ((:squareId IS NULL AND squareId IS NULL) OR squareId = :squareId) AND isCurrent = 1")
    suspend fun closeCurrentAssignment(
        roleType: String,
        neighborhoodId: String,
        squareId: String?,
        ts: Long = System.currentTimeMillis()
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: RepresentativeHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<RepresentativeHistoryEntity>)
}

@Dao
interface PersonDao {
    @Query("SELECT * FROM people WHERE isDeleted = 0 ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getPaged(limit: Int, offset: Int): List<PersonEntity>

    @Query(
        """
        SELECT * FROM people
        WHERE isDeleted = 0
        AND (:query = '' OR fullName LIKE '%' || :query || '%' OR nationalId LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%' OR citizenCode LIKE '%' || :query || '%')
        AND (:neighborhoodId = '' OR neighborhoodId = :neighborhoodId)
        AND (:squareId = '' OR squareId = :squareId)
        AND (:housingType = '' OR housingType = :housingType)
        AND (:healthStatus = '' OR healthStatus LIKE '%' || :healthStatus || '%')
        ORDER BY createdAt DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchPaged(
        query: String,
        neighborhoodId: String,
        squareId: String,
        housingType: String,
        healthStatus: String,
        limit: Int,
        offset: Int
    ): List<PersonEntity>

    @Query("SELECT * FROM people WHERE isDeleted = 0 ORDER BY createdAt DESC")
    fun observeAllActive(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM people WHERE isDeleted = 0 ORDER BY createdAt DESC")
    suspend fun getAllActiveOnce(): List<PersonEntity>

    @Query("SELECT * FROM people")
    suspend fun getAllRaw(): List<PersonEntity>

    @Query("SELECT * FROM people WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PersonEntity?

    @Query("SELECT * FROM people WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<PersonEntity?>

    @Query("SELECT * FROM people WHERE isDeleted = 0 AND idType = :idType AND nationalId = :nationalId AND nationalId != '' LIMIT 1")
    suspend fun findActiveByNationalId(idType: String, nationalId: String): PersonEntity?

    @Query("SELECT COUNT(*) FROM people WHERE isDeleted = 0")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM people WHERE isDeleted = 0")
    suspend fun countActive(): Int

    @Query("SELECT COALESCE(SUM(familySize), 0) FROM people WHERE isDeleted = 0")
    fun observeTotalFamilyMembers(): Flow<Int>

    @Query("SELECT * FROM people WHERE isDeleted = 0 AND lastAidDate = '' ORDER BY fullName ASC")
    suspend fun getCitizensWithoutAid(): List<PersonEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(person: PersonEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(people: List<PersonEntity>)

    @Query("UPDATE people SET isDeleted = 1, updatedAt = :ts, updatedBy = :updatedBy, syncStatus = 'Pending' WHERE id = :id")
    suspend fun softDelete(id: String, updatedBy: String?, ts: Long = System.currentTimeMillis())

    @Query("UPDATE people SET lastAidDate = :date, updatedAt = :ts, syncStatus = 'Pending' WHERE id = :personId")
    suspend fun updateLastAidDate(personId: String, date: String, ts: Long = System.currentTimeMillis())
}

@Dao
interface IdentityDocumentDao {
    @Query("SELECT * FROM identity_documents WHERE personId = :personId AND isDeleted = 0 ORDER BY createdAt ASC")
    fun observeForPerson(personId: String): Flow<List<IdentityDocumentEntity>>

    @Query("SELECT * FROM identity_documents WHERE personId = :personId AND isDeleted = 0 ORDER BY createdAt ASC")
    suspend fun getForPersonOnce(personId: String): List<IdentityDocumentEntity>

    @Query("SELECT * FROM identity_documents")
    suspend fun getAllRaw(): List<IdentityDocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(doc: IdentityDocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(docs: List<IdentityDocumentEntity>)

    @Query("DELETE FROM identity_documents WHERE personId = :personId")
    suspend fun deleteAllForPerson(personId: String)
}

@Dao
interface AidDao {
    @Query("SELECT * FROM aid_programs WHERE isDeleted = 0 ORDER BY distributionDate DESC")
    fun observePrograms(): Flow<List<AidProgramEntity>>

    @Query("SELECT * FROM aid_programs WHERE isDeleted = 0 ORDER BY distributionDate DESC")
    suspend fun getProgramsOnce(): List<AidProgramEntity>

    @Query("SELECT * FROM aid_programs")
    suspend fun getAllProgramsRaw(): List<AidProgramEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgram(program: AidProgramEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPrograms(programs: List<AidProgramEntity>)

    @Query("SELECT * FROM aid_beneficiaries WHERE programId = :programId ORDER BY createdAt DESC")
    fun observeBeneficiaries(programId: String): Flow<List<AidBeneficiaryEntity>>

    @Query("SELECT * FROM aid_beneficiaries")
    suspend fun getAllBeneficiariesRaw(): List<AidBeneficiaryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBeneficiaryIgnore(item: AidBeneficiaryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBeneficiaries(items: List<AidBeneficiaryEntity>)

    @Query("DELETE FROM aid_beneficiaries WHERE id = :id")
    suspend fun deleteBeneficiary(id: String)

    @Query("SELECT * FROM aid_distributions WHERE programId = :programId ORDER BY receivedAt DESC")
    fun observeDistributionsForProgram(programId: String): Flow<List<AidDistributionEntity>>

    @Query("SELECT * FROM aid_distributions WHERE personId = :personId ORDER BY receivedAt DESC")
    fun observeDistributionsForPerson(personId: String): Flow<List<AidDistributionEntity>>

    @Query("SELECT * FROM aid_distributions ORDER BY receivedAt DESC")
    fun observeAllDistributions(): Flow<List<AidDistributionEntity>>

    @Query("SELECT * FROM aid_distributions")
    suspend fun getAllDistributionsRaw(): List<AidDistributionEntity>

    @Query("SELECT * FROM aid_distributions WHERE programId = :programId AND personId = :personId LIMIT 1")
    suspend fun findDistribution(programId: String, personId: String): AidDistributionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDistributionStrict(item: AidDistributionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDistributions(items: List<AidDistributionEntity>)

    @Update
    suspend fun updateDistribution(item: AidDistributionEntity)
}

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 'global' LIMIT 1")
    fun observeSettings(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 'global' LIMIT 1")
    suspend fun getSettingsOnce(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: AppSettingsEntity)
}

@Dao
interface AuditLogDao {
    @Query("SELECT * FROM audit_logs ORDER BY createdAt DESC LIMIT 500")
    fun observeRecent(): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_logs ORDER BY createdAt DESC")
    suspend fun getAllRaw(): List<AuditLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: AuditLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(logs: List<AuditLogEntity>)
}

@Dao
interface SyncMetadataDao {
    @Query("SELECT * FROM sync_metadata ORDER BY localUpdatedAt DESC")
    fun observeAll(): Flow<List<SyncMetadataEntity>>

    @Query("SELECT * FROM sync_metadata WHERE syncStatus IN ('Pending', 'Failed') ORDER BY localUpdatedAt ASC")
    suspend fun getPendingOrFailed(): List<SyncMetadataEntity>

    @Query("SELECT COUNT(*) FROM sync_metadata WHERE syncStatus IN ('Pending', 'Failed', 'Conflict')")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM sync_metadata")
    suspend fun getAllRaw(): List<SyncMetadataEntity>

    @Query("SELECT * FROM sync_metadata WHERE entityTable = :table AND recordId = :recordId LIMIT 1")
    suspend fun find(table: String, recordId: String): SyncMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SyncMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SyncMetadataEntity>)

    @Query("UPDATE sync_metadata SET syncStatus = :status, serverSyncedAt = :syncedAt, lastError = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, syncedAt: Long?, error: String?)
}
