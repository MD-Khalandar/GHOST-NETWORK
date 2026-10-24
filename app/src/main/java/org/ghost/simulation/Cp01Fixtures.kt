package org.ghost.simulation

import org.ghost.domain.model.*

/** Typed copies of A's cp01-fixtures.json, checked against that resource in host tests.
 * Binary fields are placeholders, not valid signed GHOST packets.
 */
object Cp01Fixtures {
    const val REPORTED_AT_MS = 1792859000000L
    val requester = NodeId("b".repeat(64))
    val helperOwner = NodeId("a".repeat(64))

    fun sos() = SosCreatePayload(
        requestId = RequestId("11111111-1111-4111-8111-111111111111"),
        category = SosCategory.WATER,
        description = "Two people need drinking water near the marked community hall.",
        peopleCount = 2,
        coarseArea = "Sector 4",
        landmark = "Community hall",
        consent = Consent(publicMeshAllowed = true, cloudProcessingAllowed = true),
    )

    fun helper() = HelperProfilePublishPayload(
        ownerNodeId = helperOwner,
        displayName = "Volunteer B",
        coarseArea = "Sector 4",
        helpTypes = listOf(HelpType.WATER, HelpType.TRANSPORT),
        availability = HelperAvailability.AVAILABLE,
        consent = Consent(publicMeshAllowed = true, cloudProcessingAllowed = true),
    )

    fun offer() = OfferPublishPayload(
        offerId = OfferId("22222222-2222-4222-8222-222222222222"),
        ownerNodeId = helperOwner,
        resourceType = ResourceType.WATER,
        quantity = 20,
        unit = "liters",
        coarseArea = "Sector 4",
        availableUntilMs = 1792861200000L,
        consent = Consent(publicMeshAllowed = true, cloudProcessingAllowed = true),
    )

    fun packet() = WirePacket(
        immutableEnvelopeBytesBase64 = "AQID",
        signature = "BAUG",
        relay = RelayMetadata(remainingHops = 3, hopCount = 1, previousNodeId = helperOwner),
    )

    fun receipt() = DeliveryReceiptPayload(
        packetId = PacketId("66666666-6666-4666-8666-666666666666"),
        recipientNodeId = requester,
        receiptType = ReceiptType.PERSISTED_BY_RECIPIENT,
        observedAtMs = 1792861800000L,
    )
}
