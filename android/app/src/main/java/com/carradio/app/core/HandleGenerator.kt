package com.carradio.app.core

import kotlin.random.Random

/**
 * Per-trip ephemeral phonetic handles: "<Adjective> <Animal>".
 *
 * Word lists are embedded VERBATIM from docs/handles.json — they must stay byte-identical
 * across every client platform (PWA, iOS, Android) per PROTOCOL §1.
 */
object HandleGenerator {

    val ADJECTIVES: List<String> = listOf(
        "Neon", "Crimson", "Silver", "Cobalt", "Amber", "Turbo", "Midnight", "Solar",
        "Electric", "Copper", "Ivory", "Onyx", "Scarlet", "Golden", "Azure", "Emerald",
        "Velvet", "Chrome", "Shadow", "Blazing", "Frost", "Thunder", "Drift", "Radiant",
        "Lucky", "Rusty", "Swift", "Quiet", "Wild", "Nova", "Retro", "Phantom"
    )

    val ANIMALS: List<String> = listOf(
        "Falcon", "Otter", "Lynx", "Bison", "Coyote", "Heron", "Marlin", "Puma",
        "Raven", "Stallion", "Badger", "Condor", "Dingo", "Elk", "Fox", "Gazelle",
        "Hawk", "Ibex", "Jaguar", "Kestrel", "Llama", "Moose", "Narwhal", "Osprey",
        "Panther", "Quail", "Rhino", "Sparrow", "Tiger", "Viper", "Wolf", "Wombat"
    )

    fun generate(random: Random = Random.Default): String =
        "${ADJECTIVES.random(random)} ${ANIMALS.random(random)}"
}
