package org.ghost.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sos_closures", indices = [Index(value = ["packetId"], unique = true), Index("requestId")], foreignKeys = [
    ForeignKey(entity = ReportRow::class, parentColumns = ["requestId"], childColumns = ["requestId"], onDelete = ForeignKey.CASCADE)])
data class SosClosureRow(@PrimaryKey val eventId: String, val packetId: String, val requestId: String,
    val sender: String, val state: Int, val owned: Boolean, val publicSharing: Boolean, val cloudConsent: Boolean,
    val createdAt: Long, val expiresAt: Long, val wire: ByteArray,
    val cloudAck: ByteArray? = null, val cloudError: String? = null)

@Entity(tableName = "sos_closure_deliveries", primaryKeys = ["packetId", "peerNodeId"], foreignKeys = [
    ForeignKey(entity = SosClosureRow::class, parentColumns = ["packetId"], childColumns = ["packetId"], onDelete = ForeignKey.CASCADE)])
data class SosClosureDeliveryRow(val packetId: String, val peerNodeId: String,
    val receiptFromPeer: ByteArray? = null, val receiptToPeer: ByteArray? = null)

@Dao
interface SosClosureDao {
    @Query("SELECT * FROM sos_closures ORDER BY createdAt DESC, eventId DESC LIMIT 1000") fun observe(): Flow<List<SosClosureRow>>
    @Query("SELECT * FROM sos_closures WHERE eventId = :id") suspend fun event(id: String): SosClosureRow?
    @Query("SELECT * FROM sos_closures WHERE packetId = :id") suspend fun packet(id: String): SosClosureRow?
    @Query("SELECT * FROM sos_closures WHERE requestId = :id ORDER BY createdAt DESC, eventId DESC LIMIT 1") suspend fun latest(id: String): SosClosureRow?
    @Insert suspend fun insert(row: SosClosureRow)
    @Query("SELECT COUNT(*) FROM sos_closures") suspend fun count(): Int
    @Query("SELECT * FROM sos_closures WHERE publicSharing = 1 AND expiresAt > :now AND NOT EXISTS (SELECT 1 FROM sos_closure_deliveries d WHERE d.packetId = sos_closures.packetId AND d.peerNodeId = :peer) ORDER BY createdAt, eventId LIMIT 1000")
    suspend fun outgoing(now: Long, peer: String): List<SosClosureRow>
    @Query("SELECT * FROM sos_closures WHERE publicSharing = 1 AND cloudConsent = 1 AND cloudAck IS NULL AND cloudError IS NULL AND expiresAt > :now ORDER BY createdAt LIMIT 20")
    suspend fun uploads(now: Long): List<SosClosureRow>
    @Query("SELECT * FROM sos_closure_deliveries WHERE packetId = :packet AND peerNodeId = :peer") suspend fun delivery(packet: String, peer: String): SosClosureDeliveryRow?
    @Upsert suspend fun saveDelivery(row: SosClosureDeliveryRow)
    @Query("UPDATE sos_closures SET wire = :wire WHERE packetId = :packet AND owned = 0") suspend fun updateRelay(packet: String, wire: ByteArray)
    @Query("UPDATE sos_closures SET cloudAck = :ack, cloudError = NULL WHERE eventId = :event AND cloudAck IS NULL") suspend fun acknowledge(event: String, ack: ByteArray)
    @Query("UPDATE sos_closures SET cloudError = :code WHERE eventId = :event AND cloudAck IS NULL") suspend fun reject(event: String, code: String)
    @Query("UPDATE sos_closures SET cloudError = NULL WHERE expiresAt > :now") suspend fun retry(now: Long)
    @Query("SELECT * FROM sos_closures WHERE cloudAck IS NOT NULL LIMIT 1000") suspend fun storedAcks(): List<SosClosureRow>
}
