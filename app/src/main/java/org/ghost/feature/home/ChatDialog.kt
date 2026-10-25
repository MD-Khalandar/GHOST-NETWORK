package org.ghost.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.ghost.app.LocalActions
import org.ghost.feature.foundation.fixtureTime

@Composable fun ChatDialog(id:String,owner:Boolean,actions:LocalActions,onClose:()->Unit) {
    val updates=remember(id,actions.observeChat){actions.observeChat!!(id)}
    val conversation by updates.collectAsState(initial=null)
    var text by rememberSaveable(id){mutableStateOf("")}
    var sending by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(owner)"Message requester" else "Message helper",style=MaterialTheme.typography.headlineSmall)
                Text(conversation?.status?:"Opening saved messages…",style=MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(conversation?.messages.isNullOrEmpty())item {Text("No messages yet.")}
                    items(conversation?.messages.orEmpty(),key={it.id}){message->
                        Surface(color=if(message.mine)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(if(message.mine)"You" else if(owner)"Requester" else "Helper",style=MaterialTheme.typography.labelMedium)
                                Text(message.text)
                                Text("${fixtureTime(message.createdAt)} · ${message.status}",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                OutlinedTextField(text,{if(it.length<=1000)text=it},label={Text("Message")},
                    enabled=!sending && conversation?.canSend==true,maxLines=4,modifier=Modifier.fillMaxWidth())
                Button({
                    sending=true;error=null
                    scope.launch {
                        try {actions.sendChat!!(id,text);text=""}
                        catch(cancelled:CancellationException){throw cancelled}
                        catch(failure:Exception){error=when(failure){
                            is org.ghost.protocol.ProtocolException->"Could not save this message. Try a shorter message or reconnect to GHOST."
                            is IllegalArgumentException->failure.message
                            else->"Could not save the message. Check the chat keys and storage, then try again."
                        }}
                        finally {sending=false}
                    }
                },enabled=!sending && conversation?.canSend==true && text.isNotBlank(),modifier=Modifier.fillMaxWidth()) {
                    Text(if(sending)"Saving…" else "Send message")
                }
                TextButton(onClose,modifier=Modifier.fillMaxWidth()){Text("Close chat")}
            }
        }
    }
}
