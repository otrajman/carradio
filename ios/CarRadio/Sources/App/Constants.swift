// Constants.swift
// Backend config + feature flags + tunables (PROTOCOL §11).

import Foundation

enum Constants {
    // MARK: Backend

    static let supabaseURL = URL(string: "https://trstelgemjdeqqdlasgw.supabase.co")!
    // Public anon key (safe to embed; RLS enforces access).
    static let supabaseAnonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InRyc3RlbGdlbWpkZXFxZGxhc2d3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODQ3NzcwNTQsImV4cCI6MjEwMDM1MzA1NH0.kSfNycRV422WIRSdINM22vkEHM0HZ8QrvsqCVdmvk24"

    /// Public storage bucket for voice bursts.
    static let voiceBucket = "voice_bursts"

    /// Edge function for synthetic nodes (POST { lat, lng }).
    static let syntheticNodesFunction = "synthetic-nodes"

    /// Full public URL for a bucket-prefixed audio_path
    /// (e.g. "voice_bursts/<trip>/<msg>.m4a") per PROTOCOL §3.
    static func publicAudioURL(for audioPath: String) -> URL? {
        URL(string: supabaseURL.absoluteString + "/storage/v1/object/public/" + audioPath)
    }

    // MARK: Feature flags (PROTOCOL §11)

    /// "hey radio" wake word via SFSpeechRecognizer. Degrades gracefully when
    /// speech permissions/hardware are unavailable.
    static let featureWakeWord = true
    /// Road-graph snapping — reserved, no-op in v1.
    static let featureRoadSnap = false
    /// Call the synthetic-nodes edge function on res-7 cell change.
    static let syntheticNodes = true

    // MARK: Tunables

    /// GPS emit cadence, seconds.
    static let locationCadence: TimeInterval = 2
    /// Presence track throttle, seconds (PROTOCOL §3).
    static let presenceThrottle: TimeInterval = 10
    /// Max burst length, seconds (PROTOCOL §4).
    static let maxBurstSeconds: TimeInterval = 10
    /// Shadowban check cache, seconds (PROTOCOL §4.1).
    static let shadowbanCacheSeconds: TimeInterval = 60
    /// Live bursts older than this are dropped at dequeue (PROTOCOL §5.6).
    static let burstMaxAgeSeconds: TimeInterval = 60
    /// Minimum spacing between breadcrumb playbacks (PROTOCOL §6).
    static let breadcrumbMinInterval: TimeInterval = 45
    /// Breadcrumbs older than this get the "Earlier here: " TTS prefix.
    static let breadcrumbEarlierThreshold: TimeInterval = 3_600
    /// Drive Mode lock: speed above this for `driveLockAfter` locks the UI.
    static let driveLockSpeedMps = 4.5
    static let driveLockAfter: TimeInterval = 5
    /// Drive Mode unlock: speed below this for `driveUnlockAfter` unlocks.
    static let driveUnlockSpeedMps = 2.0
    static let driveUnlockAfter: TimeInterval = 30
    /// "I'm a passenger" long-press duration.
    static let passengerHold: TimeInterval = 8
}
