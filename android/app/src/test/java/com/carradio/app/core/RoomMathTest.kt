package com.carradio.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/** PROTOCOL §3 publish-set fan-out tests using a synthetic (H3-free) cell mapping. */
class RoomMathTest {

    /** Fake "cell": ~1.1 km lat buckets so consecutive 1.2 km samples land in new cells. */
    private val fakeCell: (Double, Double) -> String = { lat, lng ->
        "c:${(lat * 100).roundToInt()}:${(lng * 100).roundToInt()}"
    }

    @Test
    fun `slow sender publishes only its own cell`() {
        // 15 mph → radius ≈ 805 m < first 1200 m sample.
        val cells = RoomMath.publishCells(37.0, -122.0, 0.0, 6.7056, fakeCell)
        assertEquals(listOf(fakeCell(37.0, -122.0)), cells)
    }

    @Test
    fun `fast sender fans out forward and caps at 4 cells`() {
        // 75 mph → radius ≈ 4828 m → samples at 1.2/2.4/3.6/4.8 km heading north.
        val cells = RoomMath.publishCells(37.0, -122.0, 0.0, 33.528, fakeCell)
        assertTrue(cells.size in 2..RoomMath.MAX_PUBLISH_CELLS)
        assertEquals(fakeCell(37.0, -122.0), cells.first())
        assertEquals(cells.toSet().size, cells.size) // deduped
    }

    @Test
    fun `duplicate cells along the ray are deduped`() {
        val constantCell: (Double, Double) -> String = { _, _ -> "same" }
        val cells = RoomMath.publishCells(37.0, -122.0, 90.0, 33.528, constantCell)
        assertEquals(listOf("same"), cells)
    }

    @Test
    fun `room name format`() {
        assertEquals("room:872a1008bffffff", RoomMath.roomName("872a1008bffffff"))
    }
}
