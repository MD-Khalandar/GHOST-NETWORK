package org.ghost.feature.home

import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.ghost.app.LocalActions
import org.ghost.data.local.AssistanceSummary
import org.ghost.design.InfoCard
import org.ghost.feature.foundation.fixtureTime

@Composable fun AssistanceCard(item:AssistanceSummary,actions:LocalActions?,busy:Boolean,closedLocally:Boolean=false) {
    var deciding by remember {mutableStateOf<Int?>(null)}
    val expired=item.deadline<=System.currentTimeMillis() && item.state in 1..2
    InfoCard(if(item.owner) "Request for your help" else "Assistance for your SOS",item.description) {
        Text(when { closedLocally && item.state in 1..2->"SOS closed on this phone; waiting for the server to update the arrangement"
            expired->"Assistance deadline passed; waiting for updated server status"
            item.pending && item.error==null->"Your decision is saved; waiting for server confirmation"
            item.state==1->if(item.owner)"Invitation awaiting your decision" else "Owner has been invited; help is not confirmed"
            item.state==2->"Owner commitment confirmed by server; delivery is not confirmed"
            item.state==3->"Declined"
            item.state==4->"Cancelled"
            item.state==5->"Expired"
            else->"Requester marked the SOS resolved" })
        Text(if(item.resourceKind==1)"Volunteer assistance" else "${item.quantity ?: "Unknown"} ${item.unit}")
        if(item.area.isNotBlank())Text("Reported area: ${item.area}")
        Text("Decision/commitment deadline: ${fixtureTime(item.deadline)}")
        if(item.reason.isNotBlank())Text(item.reason)
        item.error?.let {Text("Server rejected the decision ($it). Waiting for current coordination status.",color=MaterialTheme.colorScheme.error)}
        if(item.owner && !expired && !item.pending && actions?.decideAssistance!=null) {
            if(item.state==1){
                Button({deciding=1},enabled=!busy){Text("Accept request")}
                TextButton({deciding=2},enabled=!busy){Text("Decline request")}
            } else if(item.state==2)TextButton({deciding=3},enabled=!busy){Text("Cancel commitment")}
        }
    }
    deciding?.let {decision-> AlertDialog(onDismissRequest={deciding=null},title={Text(when(decision){1->"Commit to this request?";2->"Decline this request?";else->"Cancel your commitment?"})},
        text={Text(if(decision==1)"The server will check availability before confirming. This does not mark assistance delivered."
            else "Your decision will reach the server and requester when a connection is available.")},
        confirmButton={TextButton({actions?.decideAssistance?.invoke(item.id,item.version,decision);deciding=null},enabled=!busy){Text("Confirm")}},
        dismissButton={TextButton({deciding=null}){Text("Keep current state")}}) }
}
