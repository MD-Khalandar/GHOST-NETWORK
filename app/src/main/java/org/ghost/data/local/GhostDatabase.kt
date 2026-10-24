package org.ghost.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    val cloudAck: ByteArray? = null,
    val cloudError: String? = null,
)

@Entity(tableName = "draft")
data class DraftRow(@PrimaryKey val id: Int = 1, val value: String)

/** A row means this authenticated neighbor already holds the packet. */
@Entity(tableName = "neighbor_deliveries", primaryKeys = ["packetId", "peerNodeId"], foreignKeys = [
    ForeignKey(entity = ReportRow::class, parentColumns = ["packetId"], childColumns = ["packetId"], onDelete = ForeignKey.CASCADE),
])
data class NeighborDeliveryRow(
    val packetId: String,
    val peerNodeId: String,
    val receiptFromPeer: ByteArray? = null,
    val receiptToPeer: ByteArray? = null,
)

@Entity(tableName = "cloud_returns", indices = [Index(value = ["eventId"], unique = true)])
data class CloudReturnRow(@PrimaryKey val packetId: String, val eventId: String, val originEventId: String,
                          val destination: String, val expiresAt: Long, val wire: ByteArray)

@Entity(tableName = "cloud_return_deliveries", primaryKeys = ["packetId", "peerNodeId"], foreignKeys = [
    ForeignKey(entity = CloudReturnRow::class, parentColumns = ["packetId"], childColumns = ["packetId"], onDelete = ForeignKey.CASCADE),
])
data class CloudReturnDeliveryRow(val packetId: String, val peerNodeId: String,
                                  val receiptFromPeer: ByteArray? = null, val receiptToPeer: ByteArray? = null)

data class ReportStatusRow(@Embedded val report: ReportRow, val acknowledgedNeighbors: Int)

@Dao
interface SosDao {
    @Query("SELECT reports.*, (SELECT COUNT(*) FROM neighbor_deliveries d WHERE d.packetId = reports.packetId AND d.receiptFromPeer IS NOT NULL) AS acknowledgedNeighbors FROM reports ORDER BY receivedAt DESC LIMIT 1000")
    fun observe(): Flow<List<ReportStatusRow>>
    @Query("SELECT * FROM reports WHERE requestId = :id") suspend fun request(id: String): ReportRow?
    @Query("SELECT * FROM reports WHERE packetId = :id") suspend fun packet(id: String): ReportRow?
    @Query("SELECT * FROM reports WHERE eventId = :id") suspend fun event(id: String): ReportRow?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(row: ReportRow): Long
    @Query("UPDATE reports SET wire = :wire WHERE packetId = :packetId AND owned = 0")
    suspend fun updateRelay(packetId: String, wire: ByteArray)
    @Query("SELECT COUNT(*) FROM reports") suspend fun count(): Int
    @Query("SELECT COUNT(*) FROM reports WHERE owned = 1 AND expiresAt > :now AND NOT EXISTS (SELECT 1 FROM sos_closures c WHERE c.requestId = reports.requestId)") suspend fun activeOwned(now: Long): Int
    @Query("SELECT * FROM reports WHERE publicSharing = 1 AND expiresAt > :now AND NOT EXISTS (SELECT 1 FROM sos_closures c WHERE c.requestId = reports.requestId) AND (:peerNode IS NULL OR NOT EXISTS (SELECT 1 FROM neighbor_deliveries d WHERE d.packetId = reports.packetId AND d.peerNodeId = :peerNode)) ORDER BY owned DESC, createdAt LIMIT 1000")
    suspend fun outgoing(now: Long, peerNode: String? = null): List<ReportRow>
    @Query("SELECT * FROM reports WHERE packetId = :packetId AND publicSharing = 1 AND expiresAt > :now AND NOT EXISTS (SELECT 1 FROM sos_closures c WHERE c.requestId = reports.requestId) AND NOT EXISTS (SELECT 1 FROM neighbor_deliveries d WHERE d.packetId = reports.packetId AND d.peerNodeId = :peerNode)")
    suspend fun outgoingPacket(packetId: String, peerNode: String, now: Long): ReportRow?
    @Query("SELECT packetId FROM reports WHERE publicSharing = 1 AND expiresAt > :now UNION SELECT packetId FROM cloud_returns WHERE expiresAt > :now UNION SELECT packetId FROM sos_closures WHERE publicSharing = 1 AND expiresAt > :now")
    suspend fun activePacketIds(now: Long): List<String>
    @Query("SELECT * FROM reports WHERE publicSharing = 1 AND cloudConsent = 1 AND cloudAck IS NULL AND cloudError IS NULL AND expiresAt > :now AND NOT EXISTS (SELECT 1 FROM sos_closures c WHERE c.requestId = reports.requestId) ORDER BY owned DESC, createdAt LIMIT 20")
    suspend fun cloudOutgoing(now: Long): List<ReportRow>
    @Query("UPDATE reports SET cloudAck = :ack, cloudError = NULL WHERE eventId = :eventId AND publicSharing = 1 AND cloudConsent = 1 AND cloudAck IS NULL")
    suspend fun saveCloudAck(eventId: String, ack: ByteArray)
    @Query("UPDATE reports SET cloudError = :code WHERE eventId = :eventId AND cloudAck IS NULL")
    suspend fun rejectCloudUpload(eventId: String, code: String)
    @Query("UPDATE reports SET cloudError = NULL WHERE cloudError IS NOT NULL AND expiresAt > :now")
    suspend fun retryCloudUploads(now: Long)
    @Query("SELECT * FROM reports WHERE cloudAck IS NOT NULL LIMIT 1000") suspend fun storedCloudAcks(): List<ReportRow>
    @Query("SELECT * FROM cloud_returns WHERE packetId = :id") suspend fun cloudReturn(id: String): CloudReturnRow?
    @Query("SELECT * FROM cloud_returns WHERE eventId = :id") suspend fun cloudReturnEvent(id: String): CloudReturnRow?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertReturn(row: CloudReturnRow): Long
    @Query("SELECT COUNT(*) FROM cloud_returns") suspend fun countReturns(): Int
    @Query("DELETE FROM cloud_returns WHERE expiresAt <= :now") suspend fun pruneReturns(now: Long)
    @Query("SELECT * FROM cloud_returns WHERE expiresAt > :now AND destination != :localNode AND NOT EXISTS (SELECT 1 FROM cloud_return_deliveries d WHERE d.packetId = cloud_returns.packetId AND d.peerNodeId = :peerNode) ORDER BY CASE WHEN destination = :peerNode THEN 0 ELSE 1 END, expiresAt LIMIT 1000")
    suspend fun outgoingReturns(now: Long, localNode: String, peerNode: String): List<CloudReturnRow>
    @Query("SELECT * FROM cloud_return_deliveries WHERE packetId = :packetId AND peerNodeId = :peerNode")
    suspend fun returnDelivery(packetId: String, peerNode: String): CloudReturnDeliveryRow?
    @Upsert suspend fun saveReturnDelivery(row: CloudReturnDeliveryRow)
    @Query("SELECT packetId FROM cloud_returns WHERE expiresAt > :now") fun observeReturns(now: Long): Flow<List<String>>
    @Query("SELECT * FROM neighbor_deliveries WHERE packetId = :packetId AND peerNodeId = :peerNode")
    suspend fun delivery(packetId: String, peerNode: String): NeighborDeliveryRow?
    @Upsert suspend fun saveDelivery(row: NeighborDeliveryRow)
    @Query("DELETE FROM neighbor_deliveries WHERE packetId IN (SELECT packetId FROM reports WHERE expiresAt <= :now)")
    suspend fun pruneDeliveries(now: Long)
    @Query("UPDATE reports SET sentAt = :now WHERE packetId = :packetId AND owned = 1") suspend fun markSent(packetId: String, now: Long)
    @Query("UPDATE reports SET receivedBy = :recipient, receipt = :receipt WHERE packetId = :packetId AND owned = 1") suspend fun markReceived(packetId: String, recipient: String, receipt: ByteArray)
    @Query("DELETE FROM reports WHERE owned = 0 AND expiresAt <= :now") suspend fun pruneReceived(now: Long)
    @Query("DELETE FROM reports WHERE eventId IN (SELECT eventId FROM reports WHERE owned = 1 AND expiresAt <= :now ORDER BY createdAt DESC LIMIT -1 OFFSET 100)")
    suspend fun pruneOldOwned(now: Long)
    @Query("SELECT * FROM draft WHERE id = 1") fun observeDraft(): Flow<DraftRow?>
    @Query("SELECT * FROM draft WHERE id = 1") suspend fun draft(): DraftRow?
    @Upsert suspend fun saveDraft(row: DraftRow)
}

@Database(entities = [ReportRow::class, DraftRow::class, NeighborDeliveryRow::class, CloudReturnRow::class, CloudReturnDeliveryRow::class,
    ResourceRow::class, ResourceDeliveryRow::class, SosClosureRow::class, SosClosureDeliveryRow::class],
    version = 6, exportSchema = true, autoMigrations = [AutoMigration(from = 5, to = 6)])
abstract class GhostDatabase : RoomDatabase() {
    abstract fun sos(): SosDao
    abstract fun resources(): ResourceDao
    abstract fun closures(): SosClosureDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS resources (eventId TEXT NOT NULL, packetId TEXT NOT NULL, entityId TEXT NOT NULL, kind INTEGER NOT NULL, owner TEXT NOT NULL, owned INTEGER NOT NULL, publicSharing INTEGER NOT NULL, cloudConsent INTEGER NOT NULL, createdAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL, wire BLOB NOT NULL, cloudAck BLOB, cloudError TEXT, PRIMARY KEY(eventId))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_resources_packetId ON resources(packetId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_resources_entityId ON resources(entityId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS resource_deliveries (packetId TEXT NOT NULL, peerNodeId TEXT NOT NULL, receiptFromPeer BLOB, receiptToPeer BLOB, PRIMARY KEY(packetId, peerNodeId), FOREIGN KEY(packetId) REFERENCES resources(packetId) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS cloud_returns (packetId TEXT NOT NULL, eventId TEXT NOT NULL, originEventId TEXT NOT NULL, destination TEXT NOT NULL, expiresAt INTEGER NOT NULL, wire BLOB NOT NULL, PRIMARY KEY(packetId))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_cloud_returns_eventId ON cloud_returns(eventId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS cloud_return_deliveries (packetId TEXT NOT NULL, peerNodeId TEXT NOT NULL, receiptFromPeer BLOB, receiptToPeer BLOB, PRIMARY KEY(packetId, peerNodeId), FOREIGN KEY(packetId) REFERENCES cloud_returns(packetId) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reports ADD COLUMN cloudAck BLOB")
                db.execSQL("ALTER TABLE reports ADD COLUMN cloudError TEXT")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS neighbor_deliveries (packetId TEXT NOT NULL, peerNodeId TEXT NOT NULL, receiptFromPeer BLOB, receiptToPeer BLOB, PRIMARY KEY(packetId, peerNodeId), FOREIGN KEY(packetId) REFERENCES reports(packetId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                // Preserve the last signed acknowledgement available in version 1.
                db.execSQL("INSERT INTO neighbor_deliveries (packetId, peerNodeId, receiptFromPeer) SELECT packetId, receivedBy, receipt FROM reports WHERE owned = 1 AND publicSharing = 1 AND receivedBy IS NOT NULL AND receipt IS NOT NULL")
            }
        }
    }
}
