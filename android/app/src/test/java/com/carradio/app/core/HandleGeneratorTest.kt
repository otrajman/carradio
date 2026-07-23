package com.carradio.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The word lists must remain byte-identical to docs/handles.json (PROTOCOL §1) —
 * these tests pin the exact contents.
 */
class HandleGeneratorTest {

    private val expectedAdjectives = listOf(
        "Neon", "Crimson", "Silver", "Cobalt", "Amber", "Turbo", "Midnight", "Solar",
        "Electric", "Copper", "Ivory", "Onyx", "Scarlet", "Golden", "Azure", "Emerald",
        "Velvet", "Chrome", "Shadow", "Blazing", "Frost", "Thunder", "Drift", "Radiant",
        "Lucky", "Rusty", "Swift", "Quiet", "Wild", "Nova", "Retro", "Phantom"
    )

    private val expectedAnimals = listOf(
        "Falcon", "Otter", "Lynx", "Bison", "Coyote", "Heron", "Marlin", "Puma",
        "Raven", "Stallion", "Badger", "Condor", "Dingo", "Elk", "Fox", "Gazelle",
        "Hawk", "Ibex", "Jaguar", "Kestrel", "Llama", "Moose", "Narwhal", "Osprey",
        "Panther", "Quail", "Rhino", "Sparrow", "Tiger", "Viper", "Wolf", "Wombat"
    )

    @Test
    fun `word lists match handles json verbatim`() {
        assertEquals(expectedAdjectives, HandleGenerator.ADJECTIVES)
        assertEquals(expectedAnimals, HandleGenerator.ANIMALS)
    }

    @Test
    fun `generated handle is adjective space animal`() {
        repeat(200) {
            val handle = HandleGenerator.generate()
            val parts = handle.split(" ")
            assertEquals(2, parts.size)
            assertTrue("unknown adjective ${parts[0]}", parts[0] in HandleGenerator.ADJECTIVES)
            assertTrue("unknown animal ${parts[1]}", parts[1] in HandleGenerator.ANIMALS)
        }
    }

    @Test
    fun `generation is deterministic for a seeded random`() {
        val a = HandleGenerator.generate(Random(1234))
        val b = HandleGenerator.generate(Random(1234))
        assertEquals(a, b)
    }

    @Test
    fun `handle fits the trips table constraint (3 to 40 chars)`() {
        for (adj in HandleGenerator.ADJECTIVES) {
            for (animal in HandleGenerator.ANIMALS) {
                val len = adj.length + 1 + animal.length
                assertTrue("$adj $animal too long", len in 3..40)
            }
        }
    }
}
