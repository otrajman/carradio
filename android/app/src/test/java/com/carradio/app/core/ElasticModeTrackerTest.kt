package com.carradio.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROTOCOL §7 elastic mode entry/exit rules. */
class ElasticModeTrackerTest {

    @Test
    fun `enters after 3 lonely minutes`() {
        val t = ElasticModeTracker()
        var now = 0L
        t.onPresence(0, now)
        assertFalse(t.tick(now))
        now += 3 * 60 * 1000L - 1
        assertFalse(t.tick(now))
        now += 2
        assertTrue(t.tick(now))
    }

    @Test
    fun `a strict filter pass exits immediately and resets the timer`() {
        val t = ElasticModeTracker()
        var now = 0L
        t.onPresence(0, now)
        now += 4 * 60 * 1000L
        assertTrue(t.tick(now))
        t.onStrictBurstPassed(now)
        assertFalse(t.tick(now))
        // Timer restarts from scratch.
        now += 60 * 1000L
        assertFalse(t.tick(now))
        now += 3 * 60 * 1000L
        assertTrue(t.tick(now))
    }

    @Test
    fun `two peers exit elastic mode`() {
        val t = ElasticModeTracker()
        var now = 0L
        t.onPresence(0, now)
        now += 4 * 60 * 1000L
        assertTrue(t.tick(now))
        t.onPresence(2, now)
        assertFalse(t.tick(now))
    }

    @Test
    fun `a single peer prevents entry but does not force exit`() {
        val t = ElasticModeTracker()
        var now = 0L
        t.onPresence(0, now)
        now += 4 * 60 * 1000L
        assertTrue(t.tick(now)) // entered
        t.onPresence(1, now) // one peer: stays elastic
        assertTrue(t.tick(now))
    }
}
