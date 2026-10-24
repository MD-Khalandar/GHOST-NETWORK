package org.ghost.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="assistance_packets", indices=[Index(value=["packetId"],unique=true)])
data class AssistanceRow(@PrimaryKey val eventId:String,val packetId:String,val proposalId:String,val version:Int,
    val kind:Int,val destination:String,val expiresAt:Long,val wire:ByteArray,val cloudAck:ByteArray?=null,val cloudError:String?=null)
@Entity(tableName="assistance_deliveries",primaryKeys=["packetId","peer"],foreignKeys=[
    ForeignKey(entity=AssistanceRow::class,parentColumns=["packetId"],childColumns=["packetId"],onDelete=ForeignKey.CASCADE)])
data class AssistanceDelivery(val packetId:String,val peer:String,val receipt:ByteArray)
@Entity(tableName="assistance_cursor")
data class AssistanceCursor(@PrimaryKey val id:Int=1,val server:String,val cursor:String)
@Dao interface AssistanceDao {
    @Query("SELECT * FROM assistance_packets ORDER BY version DESC,eventId DESC") fun observe():Flow<List<AssistanceRow>>
    @Query("SELECT * FROM assistance_packets WHERE packetId=:id") suspend fun packet(id:String):AssistanceRow?
    @Query("SELECT * FROM assistance_packets WHERE eventId=:id") suspend fun event(id:String):AssistanceRow?
    @Query("SELECT * FROM assistance_packets WHERE proposalId=:id ORDER BY version DESC,eventId DESC") suspend fun proposal(id:String):List<AssistanceRow>
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row:AssistanceRow)
    @Query("UPDATE assistance_packets SET wire=:wire WHERE packetId=:id") suspend fun relay(id:String,wire:ByteArray)
    @Query("SELECT COUNT(*) FROM assistance_packets") suspend fun count():Int
    // Keep the latest status addressed to this phone after packet expiry. It is
    // durable local history and prevents late older snapshots reviving an offer.
    @Query("DELETE FROM assistance_packets WHERE expiresAt<=:now AND NOT (kind=16 AND destination=:local AND NOT EXISTS (SELECT 1 FROM assistance_packets newer WHERE newer.kind=16 AND newer.destination=:local AND newer.proposalId=assistance_packets.proposalId AND newer.version>assistance_packets.version))")
    suspend fun prune(now:Long,local:String)
    @Query("SELECT * FROM assistance_packets WHERE expiresAt>:now AND NOT EXISTS(SELECT 1 FROM assistance_deliveries d WHERE d.packetId=assistance_packets.packetId AND d.peer=:peer) ORDER BY kind,version DESC LIMIT 1000") suspend fun outgoing(now:Long,peer:String):List<AssistanceRow>
    @Query("SELECT * FROM assistance_packets WHERE kind=17 AND cloudAck IS NULL AND cloudError IS NULL AND expiresAt>:now LIMIT 20") suspend fun uploads(now:Long):List<AssistanceRow>
    @Query("UPDATE assistance_packets SET cloudAck=:ack,cloudError=NULL WHERE eventId=:id") suspend fun acknowledge(id:String,ack:ByteArray)
    @Query("UPDATE assistance_packets SET cloudError=:code WHERE eventId=:id AND cloudAck IS NULL") suspend fun reject(id:String,code:String)
    @Query("UPDATE assistance_packets SET cloudError=NULL WHERE expiresAt>:now") suspend fun retry(now:Long)
    @Upsert suspend fun delivered(row:AssistanceDelivery)
    @Query("SELECT * FROM assistance_deliveries WHERE packetId=:id AND peer=:peer") suspend fun delivery(id:String,peer:String):AssistanceDelivery?
    @Query("SELECT * FROM assistance_cursor WHERE id=1") suspend fun cursor():AssistanceCursor?
    @Upsert suspend fun checkpoint(row:AssistanceCursor)
}
