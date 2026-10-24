package org.ghost.simulation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.ghost.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class FakeClockAndFixturesTest {
    @Test
    fun clockOnlyChangesOnExplicitAdvanceAndRejectsBackwardTimeAndOverflow() {
        val clock = FakeClock(20)
        assertEquals(20L, clock.nowMs)
        clock.advanceBy(0)
        clock.advanceBy(10)
        assertEquals(30L, clock.time.value)
        assertThrows(IllegalArgumentException::class.java) { clock.advanceBy(-1) }
        assertEquals(30L, clock.nowMs)
        assertThrows(IllegalArgumentException::class.java) { FakeClock(-1) }
        val end = FakeClock(Long.MAX_VALUE)
        assertThrows(ArithmeticException::class.java) { end.advanceBy(1) }
        assertEquals(Long.MAX_VALUE, end.nowMs)
    }

    @Test
    fun runtimeFixturesMatchAsPublishedJsonWithoutPackagingItAsAnAppAsset() {
        val json = Json { explicitNulls = false }
        val root = checkNotNull(javaClass.classLoader?.getResourceAsStream("cp01-fixtures.json"))
            .bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
        assertEquals(json.decodeFromJsonElement<SosCreatePayload>(root.getValue("sos_create")), Cp01Fixtures.sos())
        assertEquals(json.decodeFromJsonElement<HelperProfilePublishPayload>(root.getValue("helper_profile_publish")), Cp01Fixtures.helper())
        assertEquals(json.decodeFromJsonElement<OfferPublishPayload>(root.getValue("offer_publish")), Cp01Fixtures.offer())
        assertEquals(json.decodeFromJsonElement<WirePacket>(root.getValue("wire_packet")), Cp01Fixtures.packet())
        assertEquals(json.decodeFromJsonElement<DeliveryReceiptPayload>(root.getValue("delivery_receipt")), Cp01Fixtures.receipt())
        val request = json.decodeFromJsonElement<DashboardSosPage>(root.getValue("dashboard_sos_page")).items.single()
        assertEquals(request.reportedAtMs, FakeClock().nowMs)
        assertEquals(request.sourceNodeId, Cp01Fixtures.requester)
    }
}
