package com.carradio.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROTOCOL §5 receive-filter conformance tests (pure JVM, no Android).
 * Geometry fixtures are built with GeoMath.destinationPoint so distance/bearing values are
 * self-consistent with the haversine/bearing implementations used by the filter.
 */
class BurstFilterTest {

    private val senderLat = 37.0
    private val senderLng = -122.0

    private fun burst(
        messageId: String = "m1",
        tripId: String = "sender",
        kind: String = BurstPayload.KIND_VOICE,
        lat: Double = senderLat,
        lng: Double = senderLng,
        heading: Double = 90.0,
        speed: Double = 29.06 // ~65 mph → senderRadius ≈ 4157 m
    ) = BurstPayload(
        messageId = messageId,
        tripId = tripId,
        kind = kind,
        audioPath = "voice_bursts/sender/$messageId.ogg",
        lat = lat,
        lng = lng,
        heading = heading,
        speed = speed,
        createdAt = "2026-07-23T00:00:00.000Z"
    )

    /** Receiver placed at `bearingDeg`/`distanceM` from the sender. */
    private fun receiverAt(
        bearingDeg: Double,
        distanceM: Double,
        heading: Double = 90.0,
        speed: Double = 25.0,
        tripId: String = "receiver"
    ): BurstFilter.ReceiverState {
        val (lat, lng) = GeoMath.destinationPoint(senderLat, senderLng, bearingDeg, distanceM)
        return BurstFilter.ReceiverState(tripId, lat, lng, heading, speed)
    }

    // --- §5.1 self ---------------------------------------------------------------------

    @Test
    fun `own bursts are dropped`() {
        val filter = BurstFilter()
        val receiver = receiverAt(90.0, 100.0, tripId = "sender")
        assertEquals(
            BurstFilter.Decision.DROP_SELF,
            filter.evaluate(burst(tripId = "sender"), receiver)
        )
    }

    // --- §5.2 dedupe -------------------------------------------------------------------

    @Test
    fun `duplicate message ids are dropped (multi-room fan-out)`() {
        val filter = BurstFilter()
        val receiver = receiverAt(90.0, 100.0)
        assertEquals(BurstFilter.Decision.PLAY, filter.evaluate(burst(messageId = "x"), receiver))
        assertEquals(
            BurstFilter.Decision.DROP_DUPLICATE,
            filter.evaluate(burst(messageId = "x"), receiver)
        )
    }

    @Test
    fun `dedupe LRU evicts oldest beyond capacity`() {
        val filter = BurstFilter(maxSeenIds = 3)
        val receiver = receiverAt(90.0, 100.0)
        for (i in 1..4) filter.evaluate(burst(messageId = "m$i"), receiver)
        // m1 was evicted (capacity 3) — plays again; m4 is still fresh in the LRU.
        assertEquals(BurstFilter.Decision.PLAY, filter.evaluate(burst(messageId = "m1"), receiver))
        assertEquals(
            BurstFilter.Decision.DROP_DUPLICATE,
            filter.evaluate(burst(messageId = "m4"), receiver)
        )
    }

    // --- §5.3 mute ---------------------------------------------------------------------

    @Test
    fun `muted senders are dropped`() {
        val filter = BurstFilter()
        filter.mute("sender")
        val receiver = receiverAt(90.0, 100.0)
        assertEquals(BurstFilter.Decision.DROP_MUTED, filter.evaluate(burst(), receiver))
        assertTrue(filter.isMuted("sender"))
    }

    // --- §5.4 heading match ---------------------------------------------------------------

    @Test
    fun `opposing traffic is dropped by heading`() {
        val filter = BurstFilter()
        val receiver = receiverAt(90.0, 100.0, heading = 270.0)
        assertEquals(BurstFilter.Decision.DROP_HEADING, filter.evaluate(burst(), receiver))
    }

    @Test
    fun `heading threshold is dot 0_85 (about 31_8 degrees)`() {
        val filter = BurstFilter()
        // 30° apart: cos(30°) ≈ 0.866 ≥ 0.85 → pass.
        assertEquals(
            BurstFilter.Decision.PLAY,
            filter.evaluate(burst(messageId = "a"), receiverAt(90.0, 100.0, heading = 120.0))
        )
        // 35° apart: cos(35°) ≈ 0.819 < 0.85 → drop.
        assertEquals(
            BurstFilter.Decision.DROP_HEADING,
            filter.evaluate(burst(messageId = "b"), receiverAt(90.0, 100.0, heading = 125.0))
        )
    }

    @Test
    fun `heading check wraps around north`() {
        val filter = BurstFilter()
        // 350° vs 10° is only 20° apart — must pass.
        assertEquals(
            BurstFilter.Decision.PLAY,
            filter.evaluate(
                burst(messageId = "n", heading = 350.0),
                receiverAt(90.0, 100.0, heading = 10.0)
            )
        )
    }

    @Test
    fun `heading is skipped when receiver is parked or creeping`() {
        val filter = BurstFilter()
        val receiver = receiverAt(90.0, 100.0, heading = 270.0, speed = 2.0) // < 3 m/s
        assertEquals(BurstFilter.Decision.PLAY, filter.evaluate(burst(), receiver))
    }

    @Test
    fun `system bursts skip the heading check`() {
        val filter = BurstFilter()
        val receiver = receiverAt(90.0, 100.0, heading = 270.0)
        assertEquals(
            BurstFilter.Decision.PLAY,
            filter.evaluate(burst(kind = BurstPayload.KIND_SYSTEM), receiver)
        )
    }

    // --- §5.5 distance / forward cone ------------------------------------------------------

    @Test
    fun `near bubble passes even behind the sender`() {
        val filter = BurstFilter()
        val receiver = receiverAt(270.0, 700.0) // 700 m directly behind
        assertEquals(BurstFilter.Decision.PLAY, filter.evaluate(burst(), receiver))
    }

    @Test
    fun `beyond 800 m a receiver behind the sender is dropped`() {
        val filter = BurstFilter()
        val receiver = receiverAt(270.0, 1000.0)
        assertEquals(BurstFilter.Decision.DROP_DISTANCE, filter.evaluate(burst(), receiver))
    }

    @Test
    fun `ahead within the sender radius passes`() {
        val filter = BurstFilter()
        val receiver = receiverAt(90.0, 3000.0) // 65 mph sender → radius ≈ 4157 m
        assertEquals(BurstFilter.Decision.PLAY, filter.evaluate(burst(), receiver))
    }

    @Test
    fun `ahead beyond the sender radius is dropped`() {
        val filter = BurstFilter()
        val receiver = receiverAt(90.0, 5000.0)
        assertEquals(BurstFilter.Decision.DROP_DISTANCE, filter.evaluate(burst(), receiver))
    }

    @Test
    fun `ahead cone is plus-minus 60 degrees`() {
        val filter = BurstFilter()
        // 55° off the sender's nose, cos(55°) ≈ 0.574 ≥ 0.5 → pass.
        assertEquals(
            BurstFilter.Decision.PLAY,
            filter.evaluate(burst(messageId = "c1"), receiverAt(145.0, 2000.0))
        )
        // 65° off, cos(65°) ≈ 0.423 < 0.5 → drop.
        assertEquals(
            BurstFilter.Decision.DROP_DISTANCE,
            filter.evaluate(burst(messageId = "c2"), receiverAt(155.0, 2000.0))
        )
    }

    @Test
    fun `asymmetric delivery - slow sender excludes a receiver its own fast cone would include`() {
        val filter = BurstFilter()
        // Slow truck (15 mph → 804 m radius) broadcasting; receiver 2 km ahead → drop.
        val slowSender = burst(messageId = "slow", speed = 6.7056)
        assertEquals(
            BurstFilter.Decision.DROP_DISTANCE,
            filter.evaluate(slowSender, receiverAt(90.0, 2000.0))
        )
    }

    // --- sender radius formula ----------------------------------------------------------

    @Test
    fun `senderRadius formula matches protocol anchors and clamps`() {
        val mph15 = 15.0 / GeoMath.MPS_TO_MPH
        val mph75 = 75.0 / GeoMath.MPS_TO_MPH
        assertEquals(0.5 * GeoMath.METERS_PER_MILE, GeoMath.senderRadiusMeters(mph15), 1.0)
        assertEquals(3.0 * GeoMath.METERS_PER_MILE, GeoMath.senderRadiusMeters(mph75), 1.0)
        // Clamped below and above.
        assertEquals(0.5 * GeoMath.METERS_PER_MILE, GeoMath.senderRadiusMeters(0.0), 1.0)
        assertEquals(3.0 * GeoMath.METERS_PER_MILE, GeoMath.senderRadiusMeters(60.0), 1.0)
        // 45 mph midpoint: 0.5 + 30 × (2.5/60) = 1.75 mi.
        assertEquals(
            1.75 * GeoMath.METERS_PER_MILE,
            GeoMath.senderRadiusMeters(45.0 / GeoMath.MPS_TO_MPH),
            1.0
        )
    }

    // --- §7 elastic mode ----------------------------------------------------------------

    @Test
    fun `elastic mode drops all directional and distance gating`() {
        val filter = BurstFilter()
        // Wrong heading + 6 km ahead: strict drops, elastic plays.
        val far = receiverAt(90.0, 6000.0, heading = 270.0)
        assertEquals(
            BurstFilter.Decision.DROP_HEADING,
            filter.evaluate(burst(messageId = "e1"), far)
        )
        assertEquals(
            BurstFilter.Decision.PLAY,
            filter.evaluate(burst(messageId = "e2"), far, elasticMode = true)
        )
        // Behind the sender also plays in elastic mode — reach is bounded only by the
        // subscribed rooms (§7; matches PWA and iOS).
        assertEquals(
            BurstFilter.Decision.PLAY,
            filter.evaluate(burst(messageId = "e3"), receiverAt(270.0, 1500.0), elasticMode = true)
        )
        // Mutes still apply in elastic mode.
        filter.mute("sender")
        assertEquals(
            BurstFilter.Decision.DROP_MUTED,
            filter.evaluate(burst(messageId = "e4"), far, elasticMode = true)
        )
    }

    // --- §14 convoy mode ----------------------------------------------------------------

    @Test
    fun `convoy members hear each other regardless of geometry, outsiders are isolated`() {
        val filter = BurstFilter()
        val tag = "aabbccdd00112233"
        // Wrong heading + far behind: convoy match still plays.
        val convoyReceiver = receiverAt(270.0, 6000.0, heading = 270.0)
            .copy(convoyTag = tag)
        assertEquals(
            BurstFilter.Decision.PLAY,
            filter.evaluate(burst(messageId = "c1", speed = 5.0).copy(convoy = tag), convoyReceiver)
        )
        // Convoy member never hears public traffic.
        assertEquals(
            BurstFilter.Decision.DROP_CONVOY,
            filter.evaluate(burst(messageId = "c2"), convoyReceiver)
        )
        // ...but does hear nearby system bursts (geometry still applies to them).
        assertEquals(
            BurstFilter.Decision.PLAY,
            filter.evaluate(
                burst(messageId = "c3", kind = BurstPayload.KIND_SYSTEM),
                receiverAt(90.0, 500.0).copy(convoyTag = tag)
            )
        )
        // Public receiver never hears convoy traffic.
        assertEquals(
            BurstFilter.Decision.DROP_CONVOY,
            filter.evaluate(burst(messageId = "c4").copy(convoy = tag), receiverAt(90.0, 500.0))
        )
        // Mute wins over convoy match.
        filter.mute("sender")
        assertEquals(
            BurstFilter.Decision.DROP_MUTED,
            filter.evaluate(burst(messageId = "c5").copy(convoy = tag), convoyReceiver)
        )
    }

    @Test
    fun `convoy tag derivation matches the cross-platform spec`() {
        // Same normalization + SHA-256 prefix on every platform (PROTOCOL §14).
        assertEquals(ConvoyTag.fromCode("  Road Trip  2026 "), ConvoyTag.fromCode("road trip 2026"))
        assertEquals(16, ConvoyTag.fromCode("x")!!.length)
        assertEquals(null, ConvoyTag.fromCode("   "))
        assertEquals(null, ConvoyTag.fromCode(null))
    }

    @Test
    fun `passesStrictGeometry is stateless and matches strict evaluation`() {
        val filter = BurstFilter()
        val nearReceiver = receiverAt(90.0, 500.0)
        val farBehind = receiverAt(270.0, 2000.0)
        assertTrue(filter.passesStrictGeometry(burst(), nearReceiver))
        assertFalse(filter.passesStrictGeometry(burst(), farBehind))
        // Repeated calls never consume dedupe slots.
        assertEquals(BurstFilter.Decision.PLAY, filter.evaluate(burst(messageId = "s1"), nearReceiver))
    }

    // --- breadcrumb path ----------------------------------------------------------------

    @Test
    fun `recordSeen=false does not consume dedupe slots`() {
        val filter = BurstFilter()
        val receiver = receiverAt(90.0, 100.0)
        assertEquals(
            BurstFilter.Decision.PLAY,
            filter.evaluate(burst(messageId = "bc"), receiver, recordSeen = false)
        )
        assertEquals(
            BurstFilter.Decision.PLAY,
            filter.evaluate(burst(messageId = "bc"), receiver)
        )
    }
}
