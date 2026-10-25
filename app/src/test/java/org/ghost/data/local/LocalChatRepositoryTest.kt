package org.ghost.data.local

import android.app.Application
import androidx.room.Room
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.ghost.contracts.v1.*
import org.ghost.domain.model.*
import org.ghost.protocol.*
import org.ghost.security.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.Security
import java.security.spec.ECGenParameterSpec
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class LocalChatRepositoryTest {
    private val hostConscrypt=Security.getProvider("Conscrypt")
    // JDK 25's framework signing certificate is unsupported by Robolectric's bundled Conscrypt.
    @Before fun hostCrypto(){Security.removeProvider("Conscrypt")}
    private val context:Application get()=RuntimeEnvironment.getApplication()
    private val dbs=mutableListOf<GhostDatabase>();private val names=mutableListOf<String>()
    private var time=1792860000000L
    private val id=UUID.randomUUID().toString()
    private fun signer()=KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair().let{SigningIdentity(it.private,it.public)}
    private fun crypto()=ChatCrypto(KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair())
    private val cloud=signer();private val owner=signer();private val requester=signer();private val pin=cloud.publicKey.encoded
    private fun open(name:String="c-${UUID.randomUUID()}.db")=Room.databaseBuilder(context,GhostDatabase::class.java,name)
        .addMigrations(GhostDatabase.MIGRATION_1_2,GhostDatabase.MIGRATION_2_3,GhostDatabase.MIGRATION_3_4,GhostDatabase.MIGRATION_4_5).build().also{dbs.add(it);names.add(name)}
    private data class Phone(val db:GhostDatabase,val signer:SigningIdentity,val crypto:ChatCrypto,val assist:LocalAssistanceRepository,val chat:LocalChatRepository)
    private fun phone(key:SigningIdentity,name:String="c-${UUID.randomUUID()}.db",encryption:ChatCrypto=crypto()):Phone {
        val db=open(name);val assist=LocalAssistanceRepository(db,{key},{time})
        return Phone(db,key,encryption,assist,LocalChatRepository(db,assist,{key},{encryption},{time}))
    }
    private fun status(destination:SigningIdentity,state:Int=2,version:Int=2)=SignedPackets.create(cloud,16,AssistanceStatusV1.newBuilder()
        .setProposalId(id).setVersion(version).setRequestId("b16e3248-e46b-4b53-a049-14c03c90ce9c")
        .setResourceId("d9db115d-a859-476a-9b26-dcf783c69729").setResourceKind(2).setOwnerNodeId(ByteString.copyFrom(WireCodec.bytes(owner.nodeId.value)))
        .setRequesterNodeId(ByteString.copyFrom(WireCodec.bytes(requester.nodeId.value))).setState(state).setQuantity(2).setUnit("litres")
        .setDescription("Need water").setDeadlineMs(time+3600000).build().toByteString(),time,ttl=3600000,destination=destination.nodeId.value)
    private fun p(row:AssistanceRow)=PrivateChatV1.parseFrom(ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(WireCodec.decode(row.wire).immutableEnvelopeBytesBase64,24576)).payloadBytes)
    private suspend fun transfer(from:Phone,to:Phone) {
        for(row in from.assist.outgoing(to.signer.nodeId.value,emptySet()).filter{it.kind==18})
            to.assist.receive(WireCodec.decode(row.wire),from.signer.nodeId.value,pin)
    }
    private suspend fun connect(a:Phone,b:Phone) {
        a.assist.store(status(a.signer),pin);b.assist.store(status(b.signer),pin)
        a.chat.process(pin);b.chat.process(pin);transfer(a,b);transfer(b,a)
    }
    @After fun close(){dbs.forEach{it.close()};names.distinct().forEach{context.deleteDatabase(it)};hostConscrypt?.let{Security.insertProviderAt(it,1)}}

    @Test @Config(sdk=[24,35]) fun confirmedSupplyNotifiesOnceAndOpensTheMatchingChat()=runBlocking {
        if(android.os.Build.VERSION.SDK_INT>=33)org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val a=phone(owner)
        val manager=context.getSystemService(android.app.NotificationManager::class.java)
        fun notifications()=org.ghost.app.ChatNotifications(context){time}
        a.assist.store(status(owner,1,1),pin)
        notifications().process(a.db.assistance().observe().first(),owner.nodeId.value)
        assertTrue(manager.activeNotifications.isEmpty())
        a.assist.store(status(owner),pin)
        notifications().process(a.db.assistance().observe().first(),owner.nodeId.value)
        val notification=manager.activeNotifications.single().notification
        assertEquals("Help confirmed",notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertTrue(notification.extras.getString(android.app.Notification.EXTRA_TEXT)!!.contains("message the requester"))
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE,notification.visibility)
        notification.contentIntent.send()
        val opened=org.robolectric.Shadows.shadowOf(context).nextStartedActivity
        assertEquals(org.ghost.app.MainActivity::class.java.name,opened.component!!.className)
        assertEquals(id,org.ghost.app.ChatNotifications.chatId(opened))
        manager.cancelAll()
        notifications().process(a.db.assistance().observe().first(),owner.nodeId.value)
        assertTrue(manager.activeNotifications.isEmpty()) // A new observer/process cannot repeat the alert.
        notifications().process(a.db.assistance().observe().first(),requester.nodeId.value)
        assertTrue(manager.activeNotifications.isEmpty()) // A relay is not a participant on this phone.
        if(android.os.Build.VERSION.SDK_INT>=26)
            assertEquals(android.app.NotificationManager.IMPORTANCE_DEFAULT,manager.getNotificationChannel(org.ghost.app.ChatNotifications.CHANNEL).importance)
    }

    @Test fun incomingTextNotifiesAfterDecryptionWithoutLeakingTextOrRepeatingOnReplay()=runBlocking {
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val a=phone(owner);val b=phone(requester);connect(a,b)
        val manager=context.getSystemService(android.app.NotificationManager::class.java)
        fun notifications()=org.ghost.app.ChatNotifications(context){time}
        notifications().process(b.db.assistance().observe().first(),requester.nodeId.value)
        assertTrue(manager.activeNotifications.single().notification.extras.getString(android.app.Notification.EXTRA_TEXT)!!.contains("supplier accepted"))
        manager.cancelAll()
        a.chat.send(id,"Private meeting place",pin);transfer(a,b)
        notifications().process(b.db.assistance().observe().first(),requester.nodeId.value)
        assertTrue(manager.activeNotifications.isEmpty()) // Ciphertext alone does not prove readable delivery.
        b.chat.process(pin)
        notifications().process(b.db.assistance().observe().first(),requester.nodeId.value)
        val notification=manager.activeNotifications.single().notification
        assertEquals("New private message",notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertFalse(notification.extras.getString(android.app.Notification.EXTRA_TEXT)!!.contains("Private meeting place"))
        manager.cancelAll();transfer(a,b)
        notifications().process(b.db.assistance().observe().first(),requester.nodeId.value)
        notifications().process(a.db.assistance().observe().first(),owner.nodeId.value)
        assertTrue(manager.activeNotifications.isEmpty())
        b.assist.store(status(requester,7,3),pin)
        notifications().process(b.db.assistance().observe().first(),requester.nodeId.value)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun notificationPermissionDenialAndClosedRequestsDoNotPreventChat()=runBlocking {
        org.robolectric.Shadows.shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val a=phone(owner);val b=phone(requester);connect(a,b)
        val notifications=org.ghost.app.ChatNotifications(context){time}
        val rows=a.db.assistance().observe().first()
        notifications.process(rows,owner.nodeId.value)
        val manager=context.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(manager.activeNotifications.isEmpty())
        assertTrue(a.chat.observe().first().single().canSend)
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        notifications.process(rows,owner.nodeId.value,setOf("b16e3248-e46b-4b53-a049-14c03c90ce9c"))
        assertTrue(manager.activeNotifications.isEmpty())
        time+=3600001
        notifications.process(rows,owner.nodeId.value)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun privateTextRelaysOfflineAndOnlyRecipientReceiptMeansDelivered()=runBlocking {
        val a=phone(owner);val b=phone(requester);val carrier=phone(signer())
        connect(a,b)
        a.chat.send(id,"Meet at the east entrance",pin)
        var shown=a.chat.observe().first().single().messages.single()
        assertEquals("Meet at the east entrance",shown.text);assertEquals("Waiting for delivery",shown.status)
        val row=a.db.assistance().uploads(time).single{it.kind==18 && p(it).type==2}
        assertFalse(row.wire.toString(Charsets.UTF_8).contains("Meet at the east entrance"))
        val cloudAck=SignedPackets.create(cloud,7,CloudAckV1.newBuilder().setOriginEventId(row.eventId).setStatusValue(1).setCloudRevision(1).setReceivedAtMs(time).build().toByteString(),time,destination=owner.nodeId.value)
        LocalSosRepository(a.db,{owner},{time}).acceptCloudAck(row.eventId,cloudAck,pin,1)
        assertEquals("Saved on server; waiting for the other phone",a.chat.observe().first().single().messages.single().status)
        transfer(a,carrier);transfer(a,carrier)
        assertEquals(1,carrier.db.assistance().observe().first().count{it.kind==18 && p(it).type==2})
        assertTrue(carrier.chat.observe().first().isEmpty())
        transfer(carrier,b);b.chat.process(pin)
        assertEquals("Meet at the east entrance",b.chat.observe().first().single().messages.single().text)
        transfer(b,carrier);transfer(carrier,a)
        shown=a.chat.observe().first().single().messages.single()
        assertEquals("Delivered to the other phone",shown.status)
        b.chat.send(id,"I am waiting there",pin);transfer(b,a);a.chat.process(pin);transfer(a,b)
        assertEquals(2,a.chat.observe().first().single().messages.size)
        assertEquals("Delivered to the other phone",b.chat.observe().first().single().messages.last().status)
    }

    @Test fun historySurvivesRestartAndClosedArrangementCannotSend()=runBlocking {
        val a=phone(owner);val name="history-${UUID.randomUUID()}.db";val b=phone(requester,name)
        connect(a,b);b.chat.send(id,"Please use the side door",pin)
        b.db.close();dbs.remove(b.db)
        val restored=phone(requester,name,b.crypto)
        assertEquals("Please use the side door",restored.chat.observe().first().single().messages.single().text)
        restored.assist.store(status(requester,7,3),pin)
        assertFalse(restored.chat.observe().first().single().canSend)
        assertTrue(runCatching{restored.chat.send(id,"Too late",pin)}.isFailure)
        time+=86400001;restored.db.assistance().prune(time,requester.nodeId.value)
        assertEquals(1,restored.chat.observe().first().single().messages.size)
    }

    @Test fun waitingKeysAndInvitationDoNotPermitSendingAndTamperingNeverGetsReceipt()=runBlocking {
        val a=phone(owner);val b=phone(requester)
        a.assist.store(status(owner,1,1),pin);a.chat.process(pin)
        assertFalse(a.chat.observe().first().single().canSend)
        assertTrue(runCatching{a.chat.send(id,"Not accepted",pin)}.isFailure)
        a.assist.store(status(owner),pin);a.chat.process(pin)
        assertTrue(runCatching{a.chat.send(id,"No peer key",pin)}.isFailure)
        connect(a,b);a.chat.send(id,"Authentic message",pin)
        val row=a.db.assistance().observe().first().single{it.kind==18 && p(it).type==2}
        val message=p(row)
        val changed=message.toBuilder().setCiphertext(ByteString.copyFrom(ByteArray(message.ciphertext.size()){1})).build()
        val altered=SignedPackets.create(owner,18,changed.toByteString(),time,destination=requester.nodeId.value,eventId=row.eventId,ttl=3600000)
        b.assist.store(altered,pin);b.chat.process(pin)
        assertFalse(b.db.assistance().observe().first().any{it.kind==18 && p(it).type==3})
        val forged=SignedPackets.create(signer(),18,message.toByteString(),time,destination=requester.nodeId.value,eventId=row.eventId,ttl=3600000)
        assertTrue(runCatching{PacketValidator(pin).verify(forged,time,forCloud=true)}.isFailure)
        assertEquals("Message unavailable on this phone",b.chat.observe().first().single().messages.single().text)
    }

    @Test fun encryptionAuthenticatesContextAndRejectsOtherPhoneKeys() {
        val a=crypto();val b=crypto();val aad=ChatCrypto.aad(id,"message","sender","recipient")
        val encrypted=a.seal("Private text",b.publicKey,aad)
        assertEquals("Private text",b.open(encrypted.nonce,encrypted.ciphertext,encrypted.wrapped,aad))
        assertEquals("Private text",a.open(encrypted.nonce,encrypted.ciphertext,encrypted.ownWrapped,aad))
        assertTrue(runCatching{a.open(encrypted.nonce,encrypted.ciphertext,encrypted.wrapped,aad)}.isFailure)
        assertTrue(runCatching{b.open(encrypted.nonce,encrypted.ciphertext,encrypted.wrapped,"wrong-context".toByteArray())}.isFailure)
    }
}
