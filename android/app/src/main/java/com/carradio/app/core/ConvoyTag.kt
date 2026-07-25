package com.carradio.app.core

import java.security.MessageDigest

/** Convoy tag derivation per PROTOCOL §14 — identical on every platform. */
object ConvoyTag {

    fun normalize(code: String): String =
        code.trim().lowercase().replace(Regex("\\s+"), " ")

    /** First 16 hex chars of SHA-256(normalized code); null for blank input. */
    fun fromCode(code: String?): String? {
        val normalized = code?.let(::normalize).orEmpty()
        if (normalized.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
