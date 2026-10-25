package org.ghost.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.ghost.R
import org.ghost.contracts.v1.AssistanceStatusV1
import org.ghost.contracts.v1.ImmutableEnvelopeV1
import org.ghost.contracts.v1.PrivateChatV1
import org.ghost.data.local.AssistanceRow
import org.ghost.protocol.WireCodec
import org.ghost.security.SigningIdentity

/** Local alerts for verified commitments and successfully decrypted incoming messages. */
class ChatNotifications(private val context:Context,private val now:()->Long=System::currentTimeMillis) {
    private val seen=context.getSharedPreferences("chat_notifications",Context.MODE_PRIVATE)
    private val manager=context.getSystemService(NotificationManager::class.java)
    private fun envelope(row:AssistanceRow)=ImmutableEnvelopeV1.parseFrom(
        WireCodec.unbase64(WireCodec.decode(row.wire).immutableEnvelopeBytesBase64,24576))

    // Called on the storage dispatcher. Expiring deduplication entries survive process restarts.
    fun process(rows:List<AssistanceRow>,local:String,closedRequests:Set<String> = emptySet()) {
        val time=now()
        val statuses=rows.filter{it.kind==16 && it.destination==local}.distinctBy{it.proposalId}
        val edits=seen.edit()
        seen.all.filterValues{it is Long && it<=time}.keys.forEach{edits.remove(it)}
        if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(
            NotificationChannel(CHANNEL,"Help and messages",NotificationManager.IMPORTANCE_DEFAULT))
        val allowed=NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
        val chat=rows.filter{it.kind==18}.associateWith{PrivateChatV1.parseFrom(envelope(it).payloadBytes)}
        val received=chat.filter{(row,p)->p.type==3 && SigningIdentity.hex(envelope(row).senderNodeId.toByteArray())==local}
            .values.map{it.messageId}.toSet()
        for(row in statuses) {
            val s=AssistanceStatusV1.parseFrom(envelope(row).payloadBytes)
            if(s.state!=2 || s.deadlineMs<=time || s.requestId in closedRequests) {
                manager.cancel(s.proposalId,NOTIFICATION_ID)
                continue
            }
            if(!allowed || row.expiresAt<=time)continue
            val messages=chat.filter{(message,p)->message.proposalId==s.proposalId && message.destination==local &&
                message.expiresAt>time && p.type==2 && message.eventId in received && !seen.contains("message:${message.eventId}")}
            val confirmation="accepted:${s.proposalId}"
            if(messages.isEmpty() && seen.contains(confirmation))continue
            val owner=SigningIdentity.hex(s.ownerNodeId.toByteArray())==local
            val text=if(messages.isNotEmpty())"Tap to open your private conversation."
                else if(owner)"Your commitment is confirmed. Tap to message the requester."
                else if(s.resourceKind==2)"Your supplier accepted. Tap to open chat."
                else "Your helper accepted. Tap to open chat."
            val intent=Intent(context,MainActivity::class.java).setAction(OPEN_CHAT)
                .setData(Uri.parse("ghost://chat/${s.proposalId}")).putExtra(CHAT_ID,s.proposalId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val open=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification=NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_connection)
                .setContentTitle(if(messages.isEmpty())"Help confirmed" else "New private message")
                .setContentText(text).setContentIntent(open).setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
            try { manager.notify(s.proposalId,NOTIFICATION_ID,notification) }
            catch(_:SecurityException){continue} // Permission may have been revoked since the check.
            edits.putLong(confirmation,minOf(row.expiresAt,s.deadlineMs))
            messages.keys.forEach{edits.putLong("message:${it.eventId}",it.expiresAt)}
        }
        edits.apply()
    }

    companion object {
        internal const val CHANNEL="ghost_chat"
        internal const val NOTIFICATION_ID=1002
        internal const val OPEN_CHAT="org.ghost.OPEN_CHAT"
        internal const val CHAT_ID="chat_proposal_id"
        fun chatId(intent:Intent?):String?=intent?.takeIf{it.action==OPEN_CHAT}?.getStringExtra(CHAT_ID)
            ?.takeIf{it.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}"))}
    }
}
