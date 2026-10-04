package com.carradio.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** PROTOCOL §16 conformance: pack tags, open-road reach, VOX state machine. */
class PelotonTest {

    // --- PelotonTag -------------------------------------------------------------------

    @Test
    fun packTagMatchesCrossPlatformVector() {
        // sha256("pelotoncb:code:hill4821")[:16] and sha256("pelotoncb:open")[:16] (node crypto)
        assertEquals("0675040865c1d052", PelotonTag.fromCode("HILL-4821"))
        assertEquals("edf37905db8bba1b", PelotonTag.OPEN_ROAD)
    }

    @Test
    fun codeNormalizationIgnoresCaseSpacesAndPunctuation() {
        val tag = PelotonTag.fromCode("hill4821")
        assertEquals(tag, PelotonTag.fromCode("  Hill 4821 "))
        assertEquals(tag, PelotonTag.fromCode("h.i.l.l-48_21"))
        assertNull(PelotonTag.fromCode("ab-1"))
        assertNull(PelotonTag.fromCode("   "))
        assertNull(PelotonTag.fromCode(null))
    }

    @Test
    fun packTagNeverCollidesWithCarConvoyTag() {
        assertNotEquals(ConvoyTag.fromCode("hill4821"), PelotonTag.fromCode("hill4821"))
    }

    @Test
    fun generatedCodesAreJoinable() {
        val rng = Random(7)
        repeat(50) {
            val code = PelotonTag.generateCode(rng)
            assertTrue(code, Regex("^[A-Z]+-\\d{4}$").matches(code))
            assertTrue(PelotonTag.fromCode(code) != null)
        }
    }

    @Test
    fun channelNames() {
        assertEquals("peloton:pack:0675040865c1d052", PelotonTag.packChannel("0675040865c1d052"))
        assertEquals("peloton:geo:882a100d25fffff", PelotonTag.geoChannel("882a100d25fffff"))
    }

    @Test
    fun carRadioFilterDropsPelotonTrafficAndPackMatchesPlay() {
        val burst = BurstPayload(
            messageId = "m1", tripId = "rider", audioPath = "voice_bursts/rider/m1.m4a",
            lat = 37.0, lng = -122.0, convoy = PelotonTag.OPEN_ROAD
        )
        val car = BurstFilter.ReceiverState("me", 37.0, -122.0, 0.0, 0.0, convoyTag = null)
        assertEquals(BurstFilter.Decision.DROP_CONVOY, BurstFilter().evaluate(burst, car))
        val rider = car.copy(convoyTag = PelotonTag.OPEN_ROAD)
        assertEquals(BurstFilter.Decision.PLAY, BurstFilter().evaluate(burst, rider))
    }

    // --- PelotonGeo -------------------------------------------------------------------

    private fun receiverAt(bearing: Double, meters: Double, heading: Double, speed: Double) =
        GeoMath.destinationPoint(37.0, -122.0, bearing, meters).let { (lat, lng) ->
            BurstFilter.ReceiverState("me", lat, lng, heading, speed)
        }

    @Test
    fun openRoadReachIsSymmetricRadius() {
        // Behind the sender counts too — unlike the car forward cone.
        assertTrue(PelotonGeo.inRange(37.0, -122.0, 90.0, 9.0, receiverAt(270.0, 450.0, 90.0, 9.0)))
        assertTrue(PelotonGeo.inRange(37.0, -122.0, 90.0, 9.0, receiverAt(90.0, 450.0, 90.0, 9.0)))
        assertFalse(PelotonGeo.inRange(37.0, -122.0, 90.0, 9.0, receiverAt(90.0, 600.0, 90.0, 9.0)))
    }

    @Test
    fun oncomingRidersDropOnlyWhenBothMoving() {
        val oncoming = receiverAt(90.0, 100.0, 270.0, 9.0)
        assertFalse(PelotonGeo.inRange(37.0, -122.0, 90.0, 9.0, oncoming))
        // Regroup stop: heading is noise, keep them.
        assertTrue(PelotonGeo.inRange(37.0, -122.0, 90.0, 1.0, oncoming))
        // Switchback within ±60°.
        assertTrue(PelotonGeo.inRange(37.0, -122.0, 90.0, 9.0, receiverAt(90.0, 100.0, 140.0, 9.0)))
    }

    // --- VoxDetector ------------------------------------------------------------------

    private class Feed(val vox: VoxDetector = VoxDetector()) {
        val events = mutableListOf<Pair<Int, VoxDetector.Event>>()
        var t = 0
        fun run(levelDb: Double, ms: Int, frameMs: Int = 20) {
            var left = ms
            while (left > 0) {
                val ev = vox.onFrame(levelDb, frameMs)
                t += frameMs
                if (ev != VoxDetector.Event.NONE) events += t to ev
                left -= frameMs
            }
        }
        fun kinds() = events.map { it.second }
    }

    @Test
    fun silenceNeverTransmits() {
        val f = Feed()
        f.run(-65.0, 10_000)
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun speechThenPauseSendsOneSnippet() {
        val f = Feed()
        f.run(-62.0, 1_000)
        f.run(-24.0, 1_500)
        f.run(-62.0, 2_000)
        assertEquals(listOf(VoxDetector.Event.START, VoxDetector.Event.STOP_SEND), f.kinds())
        // Onset after the 120 ms attack; release after the 700 ms hangover.
        assertEquals(1_120, f.events[0].first)
        assertEquals(2_500 + 700, f.events[1].first)
    }

    @Test
    fun speechDuringWarmupIsIgnored() {
        val f = Feed()
        f.run(-24.0, 400)
        f.run(-62.0, 3_000)
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun clicksAndBumpsAreNotSnippets() {
        val f = Feed()
        f.run(-62.0, 1_000)
        f.run(-20.0, 60) // shorter than attack
        f.run(-62.0, 2_000)
        assertTrue(f.events.isEmpty())

        f.run(-20.0, 160) // passes attack, fails min speech
        f.run(-62.0, 2_000)
        assertEquals(listOf(VoxDetector.Event.START, VoxDetector.Event.STOP_DISCARD), f.kinds())
    }

    @Test
    fun steadyWindRaisesTheFloorInsteadOfTransmitting() {
        val f = Feed()
        f.run(-30.0, 20_000) // loud but stationary from the first frame
        assertTrue(f.events.isEmpty())
        // A voice clearly above the wind still opens the mic.
        f.run(-12.0, 800)
        assertEquals(VoxDetector.Event.START, f.kinds().first())
    }

    @Test
    fun windGustAfterQuietIsAtMostOneBoundedSnippet() {
        val f = Feed()
        f.run(-62.0, 1_000)
        f.run(-30.0, 30_000)
        // The floor catches up within one window; the gust can't keep the mic open.
        val sends = f.kinds().count { it == VoxDetector.Event.SPLIT || it == VoxDetector.Event.STOP_SEND }
        assertTrue("sends=$sends", sends <= 1)
        assertFalse(f.vox.capturing)
    }

    @Test
    fun longMonologueStreamsAsThreeSecondChunks() {
        val f = Feed()
        f.run(-62.0, 1_000)
        // Talking with natural inter-word dips for 23 s.
        repeat(23 * 5) {
            f.run(-22.0, 160)
            f.run(-60.0, 40)
        }
        f.run(-62.0, 2_000)
        // 23 s of speech from t=1 s: a chunk boundary every 3 s (t=4 s … 22 s), then release.
        assertEquals(
            listOf(VoxDetector.Event.START) +
                List(7) { VoxDetector.Event.SPLIT } +
                VoxDetector.Event.STOP_SEND,
            f.kinds()
        )
        assertEquals(4_000, f.events[1].first)
    }

    @Test
    fun resetAbandonsSnippetButKeepsFloor() {
        val f = Feed()
        f.run(-62.0, 1_000)
        f.run(-24.0, 300)
        assertTrue(f.vox.capturing)
        f.vox.reset()
        assertFalse(f.vox.capturing)
        assertEquals(-62.0, f.vox.noiseFloorDb, 0.001)
    }

    @Test
    fun uneven85msFramesBehaveTheSame() {
        // iOS AVAudioEngine taps deliver ~85 ms buffers.
        val f = Feed()
        f.run(-62.0, 1_020, frameMs = 85)
        f.run(-24.0, 1_530, frameMs = 85)
        f.run(-62.0, 2_040, frameMs = 85)
        assertEquals(listOf(VoxDetector.Event.START, VoxDetector.Event.STOP_SEND), f.kinds())
    }

    @Test
    fun levelOfPcm() {
        assertEquals(VoxDetector.SILENCE_DB, VoxDetector.levelDbfs(ShortArray(320)), 0.0)
        val full = ShortArray(320) { if (it % 2 == 0) 32767 else -32767 }
        assertEquals(0.0, VoxDetector.levelDbfs(full), 0.01)
        val half = ShortArray(320) { if (it % 2 == 0) 16384 else -16384 }
        assertEquals(-6.02, VoxDetector.levelDbfs(half), 0.01)
    }

    // --- §16.5 rider name ---------------------------------------------------------------

    @Test
    fun riderNameKeepsOrdinaryNames() {
        assertEquals("Omer", RiderName.clean("  Omer  "))
        assertEquals("Jean-Luc O'Neil Jr.", RiderName.clean("Jean-Luc O'Neil Jr."))
        assertEquals("Zoë 2", RiderName.clean("Zoë 2"))
    }

    @Test
    fun riderNameStripsMarkupEmojiAndControls() {
        assertEquals("b Sam b", RiderName.clean("<b>Sam</b>\n\u0007"))
        assertEquals("Kim", RiderName.clean("\uD83D\uDEB4 Kim"))
    }

    @Test
    fun riderNameIsCappedAndNullWhenEmpty() {
        assertEquals("A".repeat(20), RiderName.clean("A".repeat(50)))
        assertEquals(null, RiderName.clean("   "))
        assertEquals(null, RiderName.clean("!!!"))
        assertEquals(null, RiderName.clean(null))
    }
}
