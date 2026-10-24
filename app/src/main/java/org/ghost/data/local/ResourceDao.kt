package org.ghost.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "resources", indices = [Index(value = ["packetId"], unique = true), Index("entityId")])
data class ResourceRow(@PrimaryKey val eventId: String, val packetId: String, val entityId: String,
    val kind: Int, val owner: String, val owned: Boolean, val publicSharing: Boolean, val cloudConsent: Boolean,
    val createdAt: Long, val expiresAt: Long, val wire: ByteArray, val cloudAck: ByteArray? = null, val cloudError: String? = null)

@Entity(tableName = "resource_deliveries", primaryKeys = ["packetId", "peerNodeId"], foreignKeys = [
    ForeignKey(entity = ResourceRow::class, parentColumns = ["packetId"], childColumns = ["packetId"], onDelete = ForeignKey.CASCADE)])
data class ResourceDeliveryRow(val packetId: String, val peerNodeId: String,
    val receiptFromPeer: ByteArray? = null, val receiptToPeer: ByteArray? = null)

@Dao
interface ResourceDao {
    @Query("SELECT * FROM resources ORDER BY createdAt DESC, eventId DESC LIMIT 1000") fun observe(): Flow<List<ResourceRow>>
    @Query("SELECT * FROM resources WHERE eventId = :id") suspend fun event(id: String): ResourceRow?
    @Query("SELECT * FROM resources WHERE packetId = :id") suspend fun packet(id: String): ResourceRow?
    @Query("SELECT * FROM resources WHERE entityId = :id ORDER BY createdAt DESC, eventId DESC LIMIT 1") suspend fun entity(id: String): ResourceRow?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(row: ResourceRow)
    @Query("SELECT COUNT(*) FROM resources") suspend fun count(): Int
    @Query("DELETE FROM resources WHERE expiresAt <= :now AND owned = 0") suspend fun prune(now: Long)
    @Query("DELETE FROM resources WHERE eventId IN (SELECT eventId FROM resources WHERE owned = 1 AND expiresAt <= :now ORDER BY createdAt DESC LIMIT -1 OFFSET 100)") suspend fun pruneOwned(now: Long)
    @Query("SELECT * FROM resources WHERE publicSharing = 1 AND expiresAt > :now AND NOT EXISTS (SELECT 1 FROM resource_deliveries d WHERE d.packetId = resources.packetId AND d.peerNodeId = :peer) ORDER BY createdAt LIMIT 100")
    suspend fun outgoing(now: Long, peer: String): List<ResourceRow>
    @Query("SELECT packetId FROM resources WHERE publicSharing = 1 AND expiresAt > :now") suspend fun active(now: Long): List<String>
    @Query("SELECT * FROM resources WHERE publicSharing = 1 AND cloudConsent = 1 AND cloudAck IS NULL AND cloudError IS NULL AND expiresAt > :now ORDER BY createdAt LIMIT 20") suspend fun uploads(now: Long): List<ResourceRow>
    @Query("UPDATE resources SET cloudAck = :ack, cloudError = NULL WHERE eventId = :id AND cloudAck IS NULL") suspend fun acknowledge(id: String, ack: ByteArray)
    @Query("UPDATE resources SET cloudError = :code WHERE eventId = :id AND cloudAck IS NULL") suspend fun reject(id: String, code: String)
    @Query("UPDATE resources SET cloudError = NULL WHERE expiresAt > :now") suspend fun retry(now: Long)
    @Query("SELECT * FROM resources WHERE cloudAck IS NOT NULL LIMIT 1000") suspend fun storedAcks(): List<ResourceRow>
    @Query("SELECT * FROM resource_deliveries WHERE packetId = :packet AND peerNodeId = :peer") suspend fun delivery(packet: String, peer: String): ResourceDeliveryRow?
    @Upsert suspend fun saveDelivery(row: ResourceDeliveryRow)
}
