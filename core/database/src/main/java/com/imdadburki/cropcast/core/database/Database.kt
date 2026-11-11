package com.imdadburki.cropcast.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A finished recording (spec §9). Status is a plain string: ready / exporting / deleted. */
@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileUri: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val videoCodec: String,
    val audioMode: String,
    val fileSizeBytes: Long,
    val thumbnailUri: String?,
    val recordingStatus: String,
)

/**
 * The user's recording configuration, one singleton row (id = 1).
 * Region is stored normalized (0..1) so it survives rotation/resolution changes.
 */
@Entity(tableName = "recording_settings")
data class RecordingSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val selectedMode: String,
    val regionLeft: Float,
    val regionTop: Float,
    val regionWidth: Float,
    val regionHeight: Float,
    val aspectRatio: Float?,
    val resolutionPreset: String,
    val frameRate: Int,
    val bitrate: Int,
    val audioMode: String,
)

/** An export job (spec §9). Status: pending / running / done / failed. */
@Entity(tableName = "export_jobs")
data class ExportJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceRecordingId: Long,
    val destinationUri: String,
    val exportStatus: String,
    val progress: Float,
    val createdAt: Long,
    val completedAt: Long?,
    val errorMessage: String?,
)

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun getAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: Long): RecordingEntity?

    @Query("SELECT COUNT(*) FROM recordings")
    suspend fun count(): Int

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM recordings")
    suspend fun totalDurationMs(): Long

    @Query("SELECT COALESCE(SUM(fileSizeBytes), 0) FROM recordings")
    suspend fun totalSizeBytes(): Long

    @Insert
    suspend fun insert(recording: RecordingEntity): Long

    @Update
    suspend fun update(recording: RecordingEntity)

    @Delete
    suspend fun delete(recording: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface RecordingSettingsDao {
    @Query("SELECT * FROM recording_settings WHERE id = 1")
    suspend fun get(): RecordingSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(settings: RecordingSettingsEntity)
}

@Dao
interface ExportJobDao {
    @Insert
    suspend fun insert(job: ExportJobEntity): Long

    @Update
    suspend fun update(job: ExportJobEntity)

    @Query("SELECT * FROM export_jobs WHERE id = :id")
    suspend fun getById(id: Long): ExportJobEntity?
}

@Database(
    entities = [RecordingEntity::class, RecordingSettingsEntity::class, ExportJobEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun recordingSettingsDao(): RecordingSettingsDao
    abstract fun exportJobDao(): ExportJobDao
}
