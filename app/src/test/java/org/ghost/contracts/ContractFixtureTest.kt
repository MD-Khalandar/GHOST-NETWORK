package org.ghost.contracts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.ghost.domain.model.CloudAckPayload
import org.ghost.domain.model.DashboardSosPage
import org.ghost.domain.model.DeliveryReceiptPayload
import org.ghost.domain.model.HelperProfilePublishPayload
import org.ghost.domain.model.MatchProposedPayload
import org.ghost.domain.model.OfferPublishPayload
import org.ghost.domain.model.ProposalDecisionRequest
import org.ghost.domain.model.ProposalDecisionResponse
import org.ghost.domain.model.SosCreatePayload
import org.ghost.domain.model.SyncUploadRequest
import org.ghost.domain.model.WirePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractFixtureTest {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    private fun fixture(): JsonObject = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("cp01-fixtures.json")
    ) { "CP01 fixture resource is missing" }.bufferedReader().use { reader ->
        json.parseToJsonElement(reader.readText()).jsonObject
    }

    @Test
    fun sharedFixtureUsesTheFrozenContractVersion() {
        assertEquals("ghost.contracts.v1", fixture()["contract_version"]?.jsonPrimitive?.content)
    }

    @Test
    fun signedDomainPayloadFixturesDecodeIntoTypedAndroidModels() {
        val root = fixture()
        val sos = json.decodeFromJsonElement<SosCreatePayload>(root.getValue("sos_create"))
        val helper = json.decodeFromJsonElement<HelperProfilePublishPayload>(root.getValue("helper_profile_publish"))
        val offer = json.decodeFromJsonElement<OfferPublishPayload>(root.getValue("offer_publish"))
        val ack = json.decodeFromJsonElement<CloudAckPayload>(root.getValue("cloud_ack"))
        val proposal = json.decodeFromJsonElement<MatchProposedPayload>(root.getValue("match_proposed"))
        val receipt = json.decodeFromJsonElement<DeliveryReceiptPayload>(root.getValue("delivery_receipt"))
        val approvalRequest = json.decodeFromJsonElement<ProposalDecisionRequest>(root.getValue("approval_request"))
        val approvalResponse = json.decodeFromJsonElement<ProposalDecisionResponse>(root.getValue("approval_response"))

        assertEquals("WATER", sos.category.name)
        assertEquals(2, sos.peopleCount)
        assertEquals(helper.ownerNodeId.value, offer.ownerNodeId.value)
        assertEquals(sos.consent.publicMeshAllowed, offer.consent.publicMeshAllowed)
        assertEquals("ACCEPTED", ack.status.name)
        assertEquals(sos.requestId.value, proposal.requestId.value)
        assertEquals(proposal.proposalId.value, approvalResponse.proposalId.value)
        assertEquals(proposal.version, approvalRequest.version)
        assertTrue(receipt.packetId.value.isNotBlank())
    }

    @Test
    fun transportAndApiFixturesDecodeWithoutRewritingOriginFields() {
        val root = fixture()
        val packet = json.decodeFromJsonElement<WirePacket>(root.getValue("wire_packet"))
        val upload = json.decodeFromJsonElement<SyncUploadRequest>(root.getValue("sync_upload"))
        val page = json.decodeFromJsonElement<DashboardSosPage>(root.getValue("dashboard_sos_page"))

        assertEquals(packet.immutableEnvelopeBytesBase64, upload.packets.single().immutableEnvelopeBytesBase64)
        assertEquals(packet.signature, upload.packets.single().signature)
        assertEquals(1, page.items.size)
        assertEquals("OPEN", page.items.single().state.name)
    }
}
