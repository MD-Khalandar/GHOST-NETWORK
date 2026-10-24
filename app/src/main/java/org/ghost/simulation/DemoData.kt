package org.ghost.simulation

import org.ghost.domain.model.*

/** Sample reports for the non-sending UI preview. */
object DemoData {
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

}
