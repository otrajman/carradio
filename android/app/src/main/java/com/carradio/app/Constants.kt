package com.carradio.app

/** Backend configuration + protocol constants (PROTOCOL §11). */
object Constants {
    const val SUPABASE_URL = "https://trstelgemjdeqqdlasgw.supabase.co"
    const val SUPABASE_ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InRyc3RlbGdlbWpkZXFxZGxhc2d3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODQ3NzcwNTQsImV4cCI6MjEwMDM1MzA1NH0.kSfNycRV422WIRSdINM22vkEHM0HZ8QrvsqCVdmvk24"

    const val STORAGE_BUCKET = "voice_bursts"
    const val SYNTHETIC_NODES_FN = "synthetic-nodes"

    /** Full public URL for a payload audio_path (which already includes the bucket prefix). */
    fun publicAudioUrl(audioPath: String): String {
        val clean = audioPath.trimStart('/')
        return if (clean.startsWith("$STORAGE_BUCKET/")) {
            "$SUPABASE_URL/storage/v1/object/public/$clean"
        } else {
            "$SUPABASE_URL/storage/v1/object/public/$STORAGE_BUCKET/$clean"
        }
    }

    // H3 resolutions (PROTOCOL §0/§3/§6)
    const val H3_RES_ROOMS = 7        // realtime rooms
    const val H3_RES_BREADCRUMB = 8   // breadcrumb query cadence
    const val H3_RES_MESSAGE = 9      // per-message index

    // Cadences
    const val LOCATION_INTERVAL_MS = 2_000L
    const val PRESENCE_THROTTLE_MS = 10_000L
    const val SHADOWBAN_CACHE_MS = 60_000L
    const val BREADCRUMB_MIN_GAP_MS = 45_000L
    const val LIVE_BURST_MAX_AGE_MS = 60_000L
    const val TRIP_IDLE_TIMEOUT_MS = 4 * 60 * 60 * 1000L

    // Recording (PROTOCOL §4)
    const val MAX_BURST_MS = 10_000
    const val AUDIO_BITRATE = 24_000
    const val AUDIO_SAMPLE_RATE = 48_000

    // Drive Mode lock (PROTOCOL §13)
    const val DRIVE_LOCK_SPEED_MPS = 4.5
    const val DRIVE_LOCK_AFTER_MS = 5_000L
    const val DRIVE_UNLOCK_SPEED_MPS = 2.0
    const val DRIVE_UNLOCK_AFTER_MS = 30_000L
    const val PASSENGER_LONG_PRESS_MS = 8_000L
}
