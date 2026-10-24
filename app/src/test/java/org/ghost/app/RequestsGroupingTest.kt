package org.ghost.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.ghost.data.local.AssistanceSummary
import org.ghost.design.GhostTheme
import org.ghost.domain.model.LocalCloudState
import org.ghost.domain.model.RequestId
import org.ghost.domain.model.SosState
import org.ghost.feature.foundation.FoundationPreviewFixtures
import org.ghost.feature.foundation.FoundationState
import org.ghost.feature.requests.RequestsScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RequestsGroupingTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun response(id:String,request:String,state:Int=2)=AssistanceSummary(id,2,request,"supply-$id",false,
        state,"Need water","Station",7,"litres",System.currentTimeMillis()+3600000,"",false,null,2)
    private fun show(matcher:SemanticsMatcher) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        compose.onNode(matcher).performScrollTo()
    }

    @Test fun groupsByRequestIdentityAndConfirmsTheRightResponse() {
        val first=FoundationPreviewFixtures.loaded.requests.first().copy(createdHere=true,stale=false,
            state=SosState.OPEN,description="Need water",cloudState=LocalCloudState.ACCEPTED)
        val second=first.copy(requestId=RequestId("22222222-2222-4222-8222-222222222222"))
        val local=mutableStateOf(LocalUiState(assistance=listOf(
            response("second",second.requestId.value,7),
            response("first-invite",first.requestId.value,1),
            response("first-commitment",first.requestId.value),
            response("owners-invitation",first.requestId.value,1).copy(owner=true))))
        var selected:String?=null
        val actions=LocalActions({}, {_,_->}, {_,_->}, {}, {}, {}, decideAssistance={id,version,choice->
            assertEquals(2,version);assertEquals(4,choice);selected=id
            local.value=local.value.copy(assistance=local.value.assistance.map{if(it.id==id)it.copy(pending=true) else it})
        })
        compose.setContent { GhostTheme { RequestsScreen(FoundationState(requests=listOf(first,second)),{},local=local.value,actions=actions) } }
        val firstThread=hasTestTag("request-thread-${first.requestId.value}")
        val secondThread=hasTestTag("request-thread-${second.requestId.value}")
        val committed=hasTestTag("assistance-first-commitment") and hasAnyAncestor(firstThread)
        show(committed)
        compose.onNode(hasTestTag("assistance-first-invite") and hasAnyAncestor(firstThread)).assertExists()
        compose.onNode(hasTestTag("assistance-owners-invitation")).assertDoesNotExist()
        val confirm=hasText("Confirm help received") and hasAnyAncestor(committed)
        show(confirm)
        compose.onNode(confirm).performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.runOnIdle { assertEquals("first-commitment",selected) }
        compose.onNode(hasText("Your decision is saved; waiting for server confirmation") and hasAnyAncestor(firstThread)).assertExists()
        show(hasTestTag("assistance-second") and hasAnyAncestor(secondThread))
        compose.onNode(hasText("Help received (confirmed by requester)") and hasAnyAncestor(secondThread)).assertExists()
        compose.onNode(hasTestTag("assistance-first-commitment") and hasAnyAncestor(secondThread)).assertDoesNotExist()
    }

    @Test fun keepsUpdatesWhenTheOriginalIsMissingAndClosedRequestsCannotConfirmReceipt() {
        val closed=FoundationPreviewFixtures.loaded.requests.first().copy(createdHere=true,stale=false,state=SosState.CANCELLED)
        val local=LocalUiState(assistance=listOf(response("closed",closed.requestId.value),response("missing","missing-request",7)))
        val actions=LocalActions({}, {_,_->}, {_,_->}, {}, {}, {}, decideAssistance={_,_,_->fail("Closed requests cannot confirm receipt")})
        val requests=mutableStateOf(listOf(closed))
        compose.setContent { GhostTheme { RequestsScreen(FoundationState(requests=requests.value),{},local=local,actions=actions) } }
        show(hasTestTag("assistance-closed"))
        compose.onNode(hasText("SOS closed on this phone; waiting for the server to update the arrangement") and
            hasAnyAncestor(hasTestTag("request-thread-${closed.requestId.value}"))).assertExists()
        compose.onNodeWithText("Confirm help received").assertDoesNotExist()
        show(hasTestTag("assistance-missing") and hasAnyAncestor(hasTestTag("request-thread-missing-request")))
        compose.onNodeWithText("The original request is not saved on this phone. Its assistance updates are shown together.").assertExists()
        compose.runOnIdle { requests.value=emptyList() }
        show(hasTestTag("assistance-missing"))
        compose.onNodeWithText("No requests yet").assertDoesNotExist()
    }
}
