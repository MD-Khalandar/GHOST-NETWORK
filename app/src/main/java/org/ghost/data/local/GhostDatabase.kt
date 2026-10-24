package org.ghost.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.ghost.domain.model.SosDraft

/** The signed packet in each row is also its durable outgoing work. */
@Entity(tableName = "reports", indices = [Index(value = ["packetId"], unique = true), Index(value = ["requestId"], unique = true)])
data class ReportRow(
    @PrimaryKey val eventId: String,
    val packetId: String,
    val requestId: String,
    val sender: String,
    val category: String,
    val description: String,
    val people: Int?,
    val area: String?,
    val createdAt: Long,
    val receivedAt: Long,
    val expiresAt: Long,
    val owned: Boolean,
    val publicSharing: Boolean,
    val cloudConsent: Boolean,
    val wire: ByteArray,
    val receipt: ByteArray? = null,
    val sentAt: Long? = null,
    val receivedBy: String? = null,
)

@Entity(tableName = "draft")
data class DraftRow(@PrimaryKey val id: Int = 1, val value: String)

@Dao
interface SosDao {
    @Query("SELECT * FROM reports ORDER BY receivedAt DESC LIMIT 1000") fun observe(): Flow<List<ReportRow>>
    @Query("SELECT * FROM reports WHERE requestId = :id") suspend fun request(id: String): ReportRow?
    @Query("SELECT * FROM reports WHERE packetId = :id") suspend fun packet(id: String): ReportRow?
    @Query("SELECT * FROM reports WHERE eventId = :id") suspend fun event(id: String): ReportRow?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(row: ReportRow): Long
    @Query("SELECT COUNT(*) FROM reports") suspend fun count(): Int
    @Query("SELECT COUNT(*) FROM reports WHERE owned = 1 AND expiresAt > :now") suspend fun activeOwned(now: Long): Int
    @Query("SELECT * FROM reports WHERE publicSharing = 1 AND expiresAt > :now ORDER BY owned DESC, createdAt LIMIT 1000")
    suspend fun outgoing(now: Long): List<ReportRow>
    @Query("UPDATE reports SET sentAt = :now WHERE packetId = :packetId AND owned = 1") suspend fun markSent(packetId: String, now: Long)
    @Query("UPDATE reports SET receivedBy = :recipient, receipt = :receipt WHERE packetId = :packetId AND owned = 1") suspend fun markReceived(packetId: String, recipient: String, receipt: ByteArray)
    @Query("DELETE FROM reports WHERE owned = 0 AND expiresAt <= :now") suspend fun pruneReceived(now: Long)
    @Query("SELECT * FROM draft WHERE id = 1") fun observeDraft(): Flow<DraftRow?>
    @Query("SELECT * FROM draft WHERE id = 1") suspend fun draft(): DraftRow?
    @Upsert suspend fun saveDraft(row: DraftRow)
}

@Database(entities = [ReportRow::class, DraftRow::class], version = 1, exportSchema = true)
abstract class GhostDatabase : RoomDatabase() { abstract fun sos(): SosDao }
