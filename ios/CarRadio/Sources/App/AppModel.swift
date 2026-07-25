// AppModel.swift
// Central orchestrator: wires location → rooms → realtime → filter → playback,
// the send pipeline, moderation, elastic mode, drive lock, media buttons and
// the wake word. Shared by the phone UI and the CarPlay scene.

import Foundation
import Combine

@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    enum Phase: Equatable {
        case idle
        case starting
        case live
        case failed(String)
    }

    // MARK: Published state (phone UI + CarPlay)

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var handle: String = ""
    @Published private(set) var peerCount = 0
    @Published private(set) var isRecording = false
    @Published private(set) var isPlayingBurst = false
    @Published private(set) var currentSpeakerHandle: String?
    @Published private(set) var lastSpeakerHandle: String?
    @Published private(set) var driveLocked = false
    @Published private(set) var isElasticMode = false
    @Published private(set) var speedMps: Double = 0

    // MARK: Services

    let supabase = SupabaseService()
    let earcons = EarconPlayer()
    lazy var playback = PlaybackQueue(earcons: earcons)
    lazy var realtime = RealtimeCoordinator(client: supabase.client)
    lazy var sendPipeline = SendPipeline(supabase: supabase, realtime: realtime, earcons: earcons)
    let recorder = RecorderService()
    let location = LocationService()
    let nowPlaying = NowPlayingService()
    let wakeWord = WakeWordService()
    private let playedIDs = PlayedIDStore()
    private let filter = BurstFilter()
    private let elastic = ElasticMode()
    lazy var breadcrumbs = BreadcrumbService(
        supabase: supabase, playback: playback, playedIDs: playedIDs, filter: filter
    )

    // MARK: Session state

    private(set) var tripID: UUID?
    private var currentGps: GpsState?
    private var mutedTripIDs: Set<String> = []
    private var wakeWordRecording = false
    /// §14: hashed convoy tag for this trip (from the start screen); nil = public mode.
    private(set) var convoyTag: String? = ConvoyTag.fromCode(
        UserDefaults.standard.string(forKey: "convoy_code")
    )

    /// Called from the start screen; takes effect for the next trip.
    func setConvoyCode(_ code: String) {
        convoyTag = ConvoyTag.fromCode(code)
    }

    // Drive lock candidates
    private var lockCandidateSince: Date?
    private var unlockCandidateSince: Date?
    private var passengerOverride = false

    private var cancellables: Set<AnyCancellable> = []

    private init() {
        wireCallbacks()
    }

    // MARK: Lifecycle

    /// Starts a trip: fresh ephemeral identity, then all services (PROTOCOL §1).
    func startTrip() {
        guard phase != .starting, phase != .live else { return }
        phase = .starting
        let newHandle = HandleGenerator.generate()
        handle = newHandle
        Task {
            do {
                let id = try await supabase.createTrip(handle: newHandle)
                tripID = id
                breadcrumbs.tripID = id
                phase = .live
                location.requestPermissionAndStart()
                nowPlaying.activate()
                wakeWord.requestAuthorizationAndStart()
            } catch {
                phase = .failed("Couldn't reach the tower. Check connection.")
                NSLog("CarRadio trip create failed: \(error)")
            }
        }
    }

    func endTrip() {
        Task { await realtime.shutdown() }
        location.stop()
        wakeWord.stopListening()
        nowPlaying.deactivate()
        recorder.cancel()
        tripID = nil
        mutedTripIDs = []
        phase = .idle
        driveLocked = false
        passengerOverride = false
    }

    // MARK: User actions

    /// Push-to-talk toggle (tap-to-talk button, Play/Pause media button).
    func toggleTalk() {
        guard phase == .live else { return }
        if recorder.isRecording {
            recorder.stop() // onFinished sends
        } else {
            startRecording(endpointOnSilence: false)
        }
    }

    /// PROTOCOL §8 report: reports the playing (or last played) burst, stops it,
    /// and mutes the sender locally. The sender is never notified.
    func reportCurrentOrLast() {
        guard phase == .live, let tripID else { return }
        let payload = playback.skipCurrent() ?? playback.lastFinishedPayload
        guard let payload, !payload.isSystem else { return }
        mutedTripIDs.insert(payload.tripID)
        if let reportedUUID = UUID(uuidString: payload.tripID) {
            let messageUUID = UUID(uuidString: payload.messageID)
            Task {
                try? await supabase.insertReport(
                    reporter: tripID,
                    reported: reportedUUID,
                    messageID: messageUUID
                )
            }
        }
        Task {
            AudioSessionController.shared.beginPlayback()
            await earcons.play(.muted)
            AudioSessionController.shared.end()
        }
    }

    /// Swipe-down / Next-Track: skip current burst + stealth-mute its sender
    /// (PROTOCOL §8). Falls back to muting the last finished speaker.
    func skipAndMute() {
        guard phase == .live, let tripID else { return }
        let payload = playback.skipCurrent() ?? playback.lastFinishedPayload
        guard let payload, !payload.isSystem else { return }
        mutedTripIDs.insert(payload.tripID)
        if let mutedUUID = UUID(uuidString: payload.tripID) {
            Task {
                try? await supabase.insertMuteEvent(muter: tripID, muted: mutedUUID)
            }
        }
        Task {
            AudioSessionController.shared.beginPlayback()
            await earcons.play(.muted)
            AudioSessionController.shared.end()
        }
    }

    func repeatLast() {
        guard phase == .live else { return }
        playback.repeatLast()
    }

    /// Deliberate 8 s long-press: "I'm a passenger".
    func passengerUnlock() {
        driveLocked = false
        passengerOverride = true
    }

    // MARK: Wiring

    private func wireCallbacks() {
        location.onFix = { [weak self] fix in
            self?.handleFix(fix)
        }

        realtime.onBurst = { [weak self] payload in
            self?.handleIncomingBurst(payload)
        }
        realtime.onPeerCountChange = { [weak self] count in
            guard let self else { return }
            self.peerCount = count
            self.elastic.updatePresence(peerCount: count)
            self.isElasticMode = self.elastic.isElastic
            self.nowPlaying.updateNowPlaying(subtitle: "\(count) nearby — \(self.handle)")
        }

        recorder.onFinished = { [weak self] url in
            self?.handleRecordingFinished(url)
        }

        nowPlaying.onPlayPause = { [weak self] in self?.toggleTalk() }
        nowPlaying.onNextTrack = { [weak self] in self?.skipAndMute() }

        wakeWord.onCommand = { [weak self] command in
            guard let self else { return }
            switch command {
            case .mute:
                self.skipAndMute()
                self.wakeWord.startListening()
            case .report:
                self.reportCurrentOrLast()
                self.wakeWord.startListening()
            case .repeatLast:
                self.repeatLast()
                self.wakeWord.startListening()
            case .startTalking:
                self.startRecording(endpointOnSilence: true)
            }
        }

        breadcrumbs.mutedTripIDs = { [weak self] in self?.mutedTripIDs ?? [] }
        breadcrumbs.isElastic = { [weak self] in self?.elastic.isElastic ?? false }
        breadcrumbs.convoyTag = { [weak self] in self?.convoyTag }

        // Mirror playback state.
        playback.$isPlaying
            .receive(on: RunLoop.main)
            .sink { [weak self] playing in self?.isPlayingBurst = playing }
            .store(in: &cancellables)
        playback.$currentHandle
            .receive(on: RunLoop.main)
            .sink { [weak self] handle in self?.currentSpeakerHandle = handle }
            .store(in: &cancellables)
        playback.$lastSpeakerHandle
            .receive(on: RunLoop.main)
            .sink { [weak self] handle in self?.lastSpeakerHandle = handle }
            .store(in: &cancellables)
        recorder.$isRecording
            .receive(on: RunLoop.main)
            .sink { [weak self] recording in self?.isRecording = recording }
            .store(in: &cancellables)
    }

    // MARK: Location / rooms / drive lock

    private func handleFix(_ fix: GpsState) {
        currentGps = fix
        speedMps = fix.speed
        updateDriveLock(speed: fix.speed)

        guard phase == .live, let tripID else { return }

        let subscribe = RoomManager.subscribeRooms(lat: fix.lat, lng: fix.lng)
        if let ownCell = RoomManager.res7Cell(lat: fix.lat, lng: fix.lng) {
            let presence = PresencePayload(
                trip_id: tripID.uuidString.lowercased(),
                handle: handle,
                heading: fix.heading,
                speed: fix.speed,
                kind: "human"
            )
            Task {
                await realtime.updateRooms(
                    subscribe: subscribe,
                    ownRoom: RoomManager.roomName(forCell: ownCell),
                    presence: presence
                )
            }
        }

        breadcrumbs.onFix(fix)
        elastic.tick()
        isElasticMode = elastic.isElastic
    }

    private func updateDriveLock(speed: Double) {
        if speed > Constants.driveLockSpeedMps {
            unlockCandidateSince = nil
            if lockCandidateSince == nil { lockCandidateSince = Date() }
            if !driveLocked, !passengerOverride,
               let since = lockCandidateSince,
               Date().timeIntervalSince(since) >= Constants.driveLockAfter {
                driveLocked = true
            }
        } else if speed < Constants.driveUnlockSpeedMps {
            lockCandidateSince = nil
            if driveLocked {
                if unlockCandidateSince == nil { unlockCandidateSince = Date() }
                if let since = unlockCandidateSince,
                   Date().timeIntervalSince(since) >= Constants.driveUnlockAfter {
                    driveLocked = false
                    passengerOverride = false
                }
            } else {
                passengerOverride = false
            }
        } else {
            lockCandidateSince = nil
            unlockCandidateSince = nil
        }
    }

    // MARK: Receive path

    private func handleIncomingBurst(_ payload: BurstPayload) {
        guard phase == .live, let tripID, let gps = currentGps else { return }
        let result = filter.evaluate(
            payload: payload,
            receiverTripID: tripID.uuidString.lowercased(),
            receiver: gps,
            muted: mutedTripIDs,
            elasticMode: elastic.isElastic,
            convoyTag: convoyTag
        )
        if result.passesStrict {
            elastic.noteStrictFilterPass()
            isElasticMode = elastic.isElastic
        }
        guard result.verdict == .play else { return }
        playback.enqueue(PlaybackQueue.Item(payload: payload))
    }

    // MARK: Send path

    private func startRecording(endpointOnSilence: Bool) {
        guard phase == .live, !recorder.isRecording else { return }
        wakeWordRecording = endpointOnSilence
        wakeWord.stopListening()
        Task {
            AudioSessionController.shared.beginRecording()
            await earcons.play(.micOpen) // crisp "bloop-bleep" before the mic opens
            if !recorder.start(endpointOnSilence: endpointOnSilence) {
                AudioSessionController.shared.end()
                wakeWord.startListening()
            }
        }
    }

    private func handleRecordingFinished(_ url: URL?) {
        defer {
            if Constants.featureWakeWord {
                wakeWord.startListening()
            }
        }
        guard let url, let tripID, let gps = currentGps else { return }
        let currentHandle = handle
        Task {
            await sendPipeline.sendBurst(
                fileURL: url,
                tripID: tripID,
                handle: currentHandle,
                state: gps,
                convoyTag: convoyTag
            )
        }
    }
}
