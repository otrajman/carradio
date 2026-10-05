package com.carradio.app.core

import kotlin.random.Random

/**
 * PelotonCB pack identity + channel naming, PROTOCOL §16. Identical on every platform.
 *
 * Pack tags ride in the same `convoy` payload field / `messages.convoy_tag` column as §14,
 * but are derived under a "pelotoncb:" namespace so a bike pack code can never collide with
 * a car convoy code, and Car Radio clients drop peloton traffic by the existing §14 rules.
 */
object PelotonTag {

    private const val NAMESPACE = "pelotoncb:"
    const val MIN_CODE_LENGTH = 4

    /** Codes compare on ASCII letters + digits only: "Hill-4821" == "hill 4821" == "HILL4821". */
    fun normalizeCode(code: String): String =
        code.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    /** Tag for a join-by-code pack; null when the code is too short to be a pack. */
    fun fromCode(code: String?): String? {
        val normalized = normalizeCode(code.orEmpty())
        if (normalized.length < MIN_CODE_LENGTH) return null
        return ConvoyTag.fromCode(NAMESPACE + "code:" + normalized)
    }

    /** Reserved pack code: never empty — the server seats three synthetic riders (§16.7). */
    const val DEMO_CODE = "DEMO"
    /** Synthetic riders the server seats in the demo pack (added to the roster client-side). */
    const val DEMO_RIDERS = 3
    fun isDemo(code: String?): Boolean = normalizeCode(code.orEmpty()) == "demo"

    /** Tag shared by every rider in open-road (geo) mode. */
    val OPEN_ROAD: String = ConvoyTag.fromCode(NAMESPACE + "open")!!

    fun packChannel(tag: String): String = "peloton:pack:$tag"

    fun geoChannel(res8Cell: String): String = "peloton:geo:$res8Cell"

    private val WORDS = listOf(
        "HILL", "SPIN", "DRAFT", "SPRINT", "CLIMB", "GRAVEL", "TEMPO", "CADENCE",
        "ECHELON", "BONK", "CHAIN", "GRUPPO", "DESCENT", "RIDGE", "COL", "PAVE"
    )

    /** Human-shareable code for a new pack, e.g. "CLIMB-4821". */
    fun generateCode(random: Random = Random.Default): String =
        WORDS[random.nextInt(WORDS.size)] + "-" + (1000 + random.nextInt(9000))
}
