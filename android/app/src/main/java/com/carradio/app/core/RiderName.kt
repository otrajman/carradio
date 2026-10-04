package com.carradio.app.core

/**
 * PROTOCOL §16.5 optional rider name — identical to `cleanRiderName` in the web app and
 * `RiderName` on iOS. Applied to what a rider types and to every received handle.
 */
object RiderName {
    const val MAX_LENGTH = 20

    private val DISALLOWED = Regex("[^\\p{L}\\p{N} .'-]")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Letters, digits, spaces and . ' - only, whitespace collapsed, ≤ 20 characters.
     * Null when nothing usable is left (the generated handle is used instead).
     */
    fun clean(raw: String?): String? {
        val cleaned = (raw ?: "").replace(DISALLOWED, " ").replace(WHITESPACE, " ").trim()
        val end = cleaned.offsetByCodePoints(0, minOf(MAX_LENGTH, cleaned.codePointCount(0, cleaned.length)))
        return cleaned.substring(0, end).trim().ifEmpty { null }
    }
}
