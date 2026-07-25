package com.carradio.app.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the pure-Kotlin H3 port against fixtures generated from official
 * h3-js v4 (see android/tools/gen-h3-fixtures.mjs — regenerate with node if
 * H3Lite.kt or H3LiteTables.kt is ever edited).
 *
 * latLngToCell asserts exact string equality on every case. gridDisk asserts
 * exact set equality (sorted lists) — H3Lite's BFS discovery order differs
 * from h3-js spiral order by design, so disks are compared as sets.
 */
class H3LiteTest {

    private val fixtures = Json.parseToJsonElement(
        requireNotNull(javaClass.getResourceAsStream("/h3_fixtures.json")) {
            "missing test resource h3_fixtures.json"
        }.bufferedReader().use { it.readText() }
    ).jsonObject

    @Test
    fun latLngToCellMatchesH3Js() {
        val cases = fixtures.getValue("latLngToCell").jsonArray
        assertTrue("expected at least 2000 latLngToCell fixtures, got ${cases.size}", cases.size >= 2000)
        for (case in cases) {
            val obj = case.jsonObject
            val lat = obj.getValue("lat").jsonPrimitive.double
            val lng = obj.getValue("lng").jsonPrimitive.double
            val res = obj.getValue("res").jsonPrimitive.int
            val expected = obj.getValue("expected").jsonPrimitive.content
            assertEquals(
                "latLngToCell($lat, $lng, $res)",
                expected,
                H3Lite.latLngToCell(lat, lng, res)
            )
        }
    }

    @Test
    fun gridDiskMatchesH3Js() {
        val cases = fixtures.getValue("gridDisk").jsonArray
        assertTrue("expected at least 200 gridDisk fixtures, got ${cases.size}", cases.size >= 200)
        for (case in cases) {
            val obj = case.jsonObject
            val cell = obj.getValue("cell").jsonPrimitive.content
            val k = obj.getValue("k").jsonPrimitive.int
            val expected = obj.getValue("expected").jsonArray.map { it.jsonPrimitive.content }
            val actual = H3Lite.gridDisk(cell, k)
            assertEquals("gridDisk($cell, $k) origin", cell, actual.first())
            assertEquals("gridDisk($cell, $k)", expected, actual.sorted())
        }
    }

    @Test
    fun gridDiskZeroReturnsOrigin() {
        assertEquals(listOf("872830828ffffff"), H3Lite.gridDisk("872830828ffffff", 0))
    }

    @Test
    fun gridDiskUnparsableInputReturnsInputUnchanged() {
        assertEquals(listOf("not-a-cell"), H3Lite.gridDisk("not-a-cell", 2))
    }
}
