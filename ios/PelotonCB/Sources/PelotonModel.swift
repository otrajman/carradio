// PelotonModel.swift
// PelotonCB orchestrator — the iOS twin of Android's PelotonService. Reuses Car Radio's
// shared Core + Services (SupabaseService, RealtimeCoordinator, SendPipeline,
// PlaybackQueue, EarconPlayer, LocationService, NowPlayingService); only the reach rules
// (PROTOCOL §16) and the hands-free VOX transmit model are new.

import Foundation
import Combine
import CoreLocation

@MainActor
final class PelotonModel: ObservableObject {
    static let shared = PelotonModel()

    enum Phase: Equatable {
        case join
        case starting
        case riding
        case failed(String)
    }

    // MARK: Published state

    @Published private(set) var phase: Phase = .join
    @Published private(set) var handle = ""
    /// Display code ("CLIMB-4821"); nil in open-road mode.
    @Published private(set) var packCode: String?
    @Published private(set) var riderCount = 0
    @Published private(set) var mic: PelotonMic = .off
    @Published private(set) var micLevel: Float = 0
    @Published private(set) var isPlaying = false
    @Published private(set) var speakerHandle: String?
    @Published private(set) var lastSpeakerHandle: String?
    @Published private(set) var snippetsSent = 0
    @Published private(set) var status: String?

    // MARK: Services (shared with Car Radio)

    private let supabase = SupabaseService()
    private let earcons = EarconPlayer()
    private lazy var playback = PlaybackQueue(earcons: earcons)
    private lazy var realtime = RealtimeCoordinator(client: supabase.client)
    private lazy var sendPipeline = SendPipeline(supabase: supabase, realtime: realtime, earcons: earcons)
    private let location = LocationService()
    private let nowPlaying = NowPlayingService()
    private let vox = VoxEngine()
    private let filter = BurstFilter()

    // MARK: Ride state

    private var tripID: UUID?
    private var packTag = PelotonTag.openRoad
    private var isPack = false
    private var currentGps: GpsState?
    private var ownRoom: String?
    private var mutedTripIDs: Set<String> = []
    private var paused = false
    private var releaseTurnTask: Task<Void, Never>?
    private var syntheticCells: Set<String> = []
    private var cancellables: Set<AnyCancellable> = []

    private init() {
        location.activityType = .fitness
        nowPlaying.title = "PelotonCB — riding"
        wire()
    }

    // MARK: Lifecycle

    /// - Parameter code: pack code, or nil for open road.
    func startRide(code: String?) {
        guard phase != .starting, phase != .riding else { return }
        let tag = PelotonTag.fromCode(code)
        isPack = tag != nil
        packTag = tag ?? PelotonTag.openRoad
        packCode = isPack ? code?.trimmingCharacters(in: .whitespaces).uppercased() : nil
        phase = .starting
        let newHandle = HandleGenerator.generate()
        handle = newHandle
        Task {
            do {
                tripID = try await supabase.createTrip(handle: newHandle)
                phase = .riding
                status = "Waiting for GPS…"
                AudioSessionController.shared.pinForVox()
                location.requestPermissionAndStart()
                nowPlaying.activate()
                paused = false
                if !vox.start() {
                    mic = .paused
                    status = "Microphone unavailable"
                }
            } catch {
                phase = .failed("Couldn't reach the pack. Check your connection.")
                NSLog("PelotonCB trip create failed: \(error)")
            }
        }
    }

    func leaveRide() {
        Task { await realtime.shutdown() }
        vox.stop()
        location.stop()
        nowPlaying.deactivate()
        playback.stopAll()
        AudioSessionController.shared.unpinVox()
        tripID = nil
        currentGps = nil
        syntheticCells = []
        ownRoom = nil
        mutedTripIDs = []
        riderCount = 0
        mic = .off
        micLevel = 0
        snippetsSent = 0
        status = nil
        phase = .join
    }

    // MARK: Rider actions

    func togglePause() {
        guard phase == .riding else { return }
        paused.toggle()
        if paused {
            vox.setPaused(true)
            mic = .paused
            Task { await earcons.play(.muted) }
        } else {
            Task {
                // The cue sounds before transmit resumes, so it isn't sent to the pack.
                await earcons.play(.micOpen)
                vox.setPaused(false)
            }
        }
    }

    func skipAndMute() {
        guard phase == .riding, let tripID else { return }
        guard let payload = playback.skipCurrent() ?? playback.lastFinishedPayload,
              !payload.isSystem, payload.tripID != tripID.uuidString.lowercased() else { return }
        mutedTripIDs.insert(payload.tripID)
        if let muted = UUID(uuidString: payload.tripID) {
            Task { try? await supabase.insertMuteEvent(muter: tripID, muted: muted) }
        }
        Task { await earcons.play(.muted) }
    }

    func report() {
        guard phase == .riding, let tripID else { return }
        guard let payload = playback.skipCurrent() ?? playback.lastFinishedPayload,
              !payload.isSystem, payload.tripID != tripID.uuidString.lowercased() else { return }
        mutedTripIDs.insert(payload.tripID)
        if let reported = UUID(uuidString: payload.tripID) {
            Task {
                try? await supabase.insertReport(
                    reporter: tripID, reported: reported,
                    messageID: UUID(uuidString: payload.messageID)
                )
            }
        }
        Task { await earcons.play(.muted) }
    }

    // MARK: Wiring

    private func wire() {
        location.onFix = { [weak self] fix in self?.handleFix(fix) }
        realtime.onBurst = { [weak self] payload in self?.handleIncoming(payload) }
        realtime.onPeerCountChange = { [weak self] count in
            guard let self else { return }
            self.riderCount = count
            self.nowPlaying.updateNowPlaying(subtitle: "\(count) riding with you — \(self.handle)")
        }

        vox.onSnippet = { [weak self] url in self?.send(url) }
        vox.onMic = { [weak self] m in
            guard let self, !self.paused || m == .paused else { return }
            self.mic = m
        }
        vox.onLevel = { [weak self] v in self?.micLevel = v }

        // Half-duplex: the pack waits for the rider's phrase; the mic yields while it plays.
        playback.awaitTurn = { [weak self] in
            guard let self else { return }
            self.releaseTurnTask?.cancel()
            await self.vox.yieldTurn()
        }
        playback.onItemFinished = { [weak self] _ in
            guard let self else { return }
            self.releaseTurnTask?.cancel()
            self.releaseTurnTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 350_000_000)
                guard !Task.isCancelled else { return }
                self?.vox.releaseTurn()
            }
        }

        nowPlaying.onPlayPause = { [weak self] in self?.togglePause() }
        nowPlaying.onNextTrack = { [weak self] in self?.skipAndMute() }

        playback.$isPlaying.receive(on: RunLoop.main)
            .sink { [weak self] in self?.isPlaying = $0 }
            .store(in: &cancellables)
        playback.$currentHandle.receive(on: RunLoop.main)
            .sink { [weak self] in self?.speakerHandle = $0 }
            .store(in: &cancellables)
        playback.$lastSpeakerHandle.receive(on: RunLoop.main)
            .sink { [weak self] in self?.lastSpeakerHandle = $0 }
            .store(in: &cancellables)
    }

    // MARK: Location → channels (PROTOCOL §16)

    private func handleFix(_ fix: GpsState) {
        if currentGps == nil { status = nil }
        currentGps = fix
        guard phase == .riding, let tripID else { return }

        let own: String
        let subscribe: Set<String>
        if isPack {
            own = PelotonTag.packChannel(packTag)
            subscribe = [own]
        } else {
            guard let cell = RoomManager.res8Cell(lat: fix.lat, lng: fix.lng) else { return }
            own = PelotonTag.geoChannel(cell)
            subscribe = Set(H3Lite.gridDisk(cell, k: 1).map(PelotonTag.geoChannel))
        }
        ownRoom = own
        fetchSystemScriptsIfNewCell(fix)
        let presence = PresencePayload(
            trip_id: tripID.uuidString.lowercased(),
            handle: handle,
            heading: fix.heading,
            speed: fix.speed,
            kind: "rider"
        )
        Task { await realtime.updateRooms(subscribe: subscribe, ownRoom: own, presence: presence) }
    }

    /// §12 synthetic nodes (Gemini-voiced alerts + local trivia), once per res-7 cell like
    /// Car Radio. Played to this rider only; never broadcast to the pack.
    private func fetchSystemScriptsIfNewCell(_ fix: GpsState) {
        guard Constants.syntheticNodes,
              let cell = RoomManager.res7Cell(lat: fix.lat, lng: fix.lng),
              syntheticCells.insert(cell).inserted else { return }
        Task {
            guard let messages = try? await supabase.fetchSyntheticNodes(lat: fix.lat, lng: fix.lng),
                  phase == .riding else { return }
            for m in messages {
                playback.enqueue(PlaybackQueue.Item(payload: m.asPayload(), isBreadcrumb: true))
            }
        }
    }

    // MARK: Receive

    private func handleIncoming(_ payload: BurstPayload) {
        guard phase == .riding, let tripID, let gps = currentGps else { return }
        // Car Radio's filter verbatim: self / dedupe / mute / tag. A matching tag plays
        // regardless of geometry — exactly pack mode.
        let result = filter.evaluate(
            payload: payload,
            receiverTripID: tripID.uuidString.lowercased(),
            receiver: gps,
            muted: mutedTripIDs,
            elasticMode: false,
            convoyTag: packTag
        )
        guard result.verdict == .play, !payload.isSystem else { return }
        if !isPack, !PelotonGeo.inRange(sender: payload, receiver: gps) { return }
        playback.enqueue(PlaybackQueue.Item(payload: payload))
    }

    // MARK: Transmit (VOX → PROTOCOL §4)

    private func send(_ url: URL) {
        guard let tripID, let gps = currentGps, let room = ownRoom else {
            try? FileManager.default.removeItem(at: url)
            status = "Waiting for GPS — not sent"
            return
        }
        let currentHandle = handle
        let tag = packTag
        Task {
            let sent = await sendPipeline.sendBurst(
                fileURL: url,
                tripID: tripID,
                handle: currentHandle,
                state: gps,
                convoyTag: tag,
                publishRooms: [room],
                playSentCue: false // the rider may already be talking again
            )
            try? FileManager.default.removeItem(at: url)
            snippetsSent += 1
            if sent != nil { status = nil }
            // §17: the gated Road Guide may answer this rider privately.
            if let sent, Constants.roadGuideEnabled,
               let answer = await supabase.askRoadGuide(about: sent), phase == .riding {
                playback.enqueue(PlaybackQueue.Item(payload: answer, isBreadcrumb: true))
            }
        }
    }
}
