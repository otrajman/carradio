import XCTest
#if canImport(CarRadioCore)
@testable import CarRadioCore
#else
@testable import CarRadio
#endif

/// PROTOCOL §16 conformance — mirrors android/.../core/PelotonTest.kt.
final class PelotonTests: XCTestCase {

    // MARK: PelotonTag

    func testPackTagMatchesCrossPlatformVector() {
        XCTAssertEqual(PelotonTag.fromCode("HILL-4821"), "0675040865c1d052")
        XCTAssertEqual(PelotonTag.openRoad, "edf37905db8bba1b")
    }

    func testCodeNormalization() {
        let tag = PelotonTag.fromCode("hill4821")
        XCTAssertEqual(PelotonTag.fromCode("  Hill 4821 "), tag)
        XCTAssertEqual(PelotonTag.fromCode("h.i.l.l-48_21"), tag)
        XCTAssertNil(PelotonTag.fromCode("ab-1"))
        XCTAssertNil(PelotonTag.fromCode("   "))
        XCTAssertNil(PelotonTag.fromCode(nil))
    }

    func testPackTagNeverCollidesWithCarConvoyTag() {
        XCTAssertNotEqual(ConvoyTag.fromCode("hill4821"), PelotonTag.fromCode("hill4821"))
    }

    func testGeneratedCodesAreJoinable() {
        for _ in 0..<50 {
            let code = PelotonTag.generateCode()
            XCTAssertNotNil(code.range(of: "^[A-Z]+-[0-9]{4}$", options: .regularExpression), code)
            XCTAssertNotNil(PelotonTag.fromCode(code))
        }
    }

    func testCarRadioFilterDropsPelotonTraffic() {
        let burst = BurstPayload(
            messageID: "m1", tripID: "rider", handle: "Neon Falcon", kind: "voice",
            audioPath: "voice_bursts/rider/m1.m4a", text: nil,
            lat: 37, lng: -122, heading: 0, speed: 0, h3R9: "",
            createdAt: "2026-07-23T00:00:00.000Z", convoy: PelotonTag.openRoad
        )
        let gps = GpsState(lat: 37, lng: -122, heading: 0, speed: 0)
        let car = BurstFilter().evaluate(
            payload: burst, receiverTripID: "me", receiver: gps,
            muted: [], elasticMode: false
        )
        XCTAssertEqual(car.verdict, .dropConvoy)
        let rider = BurstFilter().evaluate(
            payload: burst, receiverTripID: "me", receiver: gps,
            muted: [], elasticMode: false, convoyTag: PelotonTag.openRoad
        )
        XCTAssertEqual(rider.verdict, .play)
    }

    // MARK: PelotonGeo

    private func sender(heading: Double, speed: Double) -> BurstPayload {
        BurstPayload(
            messageID: "m", tripID: "s", handle: "", kind: "voice", audioPath: nil, text: "x",
            lat: 37, lng: -122, heading: heading, speed: speed, h3R9: "",
            createdAt: "", convoy: nil
        )
    }

    private func receiverAt(bearing: Double, meters: Double, heading: Double, speed: Double) -> GpsState {
        let p = GeoMath.destination(lat: 37, lng: -122, bearingDeg: bearing, distanceM: meters)
        return GpsState(lat: p.lat, lng: p.lng, heading: heading, speed: speed)
    }

    func testOpenRoadReachIsSymmetricRadius() {
        let s = sender(heading: 90, speed: 9)
        XCTAssertTrue(PelotonGeo.inRange(sender: s, receiver: receiverAt(bearing: 270, meters: 450, heading: 90, speed: 9)))
        XCTAssertTrue(PelotonGeo.inRange(sender: s, receiver: receiverAt(bearing: 90, meters: 450, heading: 90, speed: 9)))
        XCTAssertFalse(PelotonGeo.inRange(sender: s, receiver: receiverAt(bearing: 90, meters: 600, heading: 90, speed: 9)))
    }

    func testOncomingRidersDropOnlyWhenBothMoving() {
        let oncoming = receiverAt(bearing: 90, meters: 100, heading: 270, speed: 9)
        XCTAssertFalse(PelotonGeo.inRange(sender: sender(heading: 90, speed: 9), receiver: oncoming))
        XCTAssertTrue(PelotonGeo.inRange(sender: sender(heading: 90, speed: 1), receiver: oncoming))
        XCTAssertTrue(PelotonGeo.inRange(
            sender: sender(heading: 90, speed: 9),
            receiver: receiverAt(bearing: 90, meters: 100, heading: 140, speed: 9)
        ))
    }

    // MARK: VoxDetector

    private final class Feed {
        let vox = VoxDetector()
        var events: [(Int, VoxDetector.Event)] = []
        var t = 0
        func run(_ level: Double, _ ms: Int, frame: Int = 20) {
            var left = ms
            while left > 0 {
                let ev = vox.onFrame(levelDb: level, durationMs: frame)
                t += frame
                if ev != .none { events.append((t, ev)) }
                left -= frame
            }
        }
        var kinds: [VoxDetector.Event] { events.map(\.1) }
    }

    func testSilenceNeverTransmits() {
        let f = Feed()
        f.run(-65, 10_000)
        XCTAssertTrue(f.events.isEmpty)
    }

    func testSpeechThenPauseSendsOneSnippet() {
        let f = Feed()
        f.run(-62, 1_000)
        f.run(-24, 1_500)
        f.run(-62, 2_000)
        XCTAssertEqual(f.kinds, [.start, .stopSend])
        XCTAssertEqual(f.events[0].0, 1_120)
        XCTAssertEqual(f.events[1].0, 3_200) // 700 ms hangover
    }

    func testSpeechDuringWarmupIsIgnored() {
        let f = Feed()
        f.run(-24, 400)
        f.run(-62, 3_000)
        XCTAssertTrue(f.events.isEmpty)
    }

    func testClicksAndBumpsAreNotSnippets() {
        let f = Feed()
        f.run(-62, 1_000)
        f.run(-20, 60)
        f.run(-62, 2_000)
        XCTAssertTrue(f.events.isEmpty)
        f.run(-20, 160)
        f.run(-62, 2_000)
        XCTAssertEqual(f.kinds, [.start, .stopDiscard])
    }

    func testSteadyWindRaisesTheFloor() {
        let f = Feed()
        f.run(-30, 20_000)
        XCTAssertTrue(f.events.isEmpty)
        f.run(-12, 800)
        XCTAssertEqual(f.kinds.first, .start)
    }

    func testWindGustAfterQuietIsAtMostOneSnippet() {
        let f = Feed()
        f.run(-62, 1_000)
        f.run(-30, 30_000)
        XCTAssertLessThanOrEqual(f.kinds.filter { $0 == .split || $0 == .stopSend }.count, 1)
        XCTAssertFalse(f.vox.capturing)
    }

    func testLongMonologueStreamsAsThreeSecondChunks() {
        let f = Feed()
        f.run(-62, 1_000)
        for _ in 0..<(23 * 5) {
            f.run(-22, 160)
            f.run(-60, 40)
        }
        f.run(-62, 2_000)
        // 23 s of speech from t=1 s: a chunk boundary every 3 s (t=4 s … 22 s), then release.
        XCTAssertEqual(f.kinds, [.start] + Array(repeating: .split, count: 7) + [.stopSend])
        XCTAssertEqual(f.events[1].0, 4_000)
    }

    func testResetAbandonsSnippetButKeepsFloor() {
        let f = Feed()
        f.run(-62, 1_000)
        f.run(-24, 300)
        XCTAssertTrue(f.vox.capturing)
        f.vox.reset()
        XCTAssertFalse(f.vox.capturing)
        XCTAssertEqual(f.vox.noiseFloorDb, -62, accuracy: 0.001)
    }

    func testUneven85msFrames() {
        let f = Feed()
        f.run(-62, 1_020, frame: 85)
        f.run(-24, 1_530, frame: 85)
        f.run(-62, 2_040, frame: 85)
        XCTAssertEqual(f.kinds, [.start, .stopSend])
    }

    func testLevelOfFloatFrame() {
        let silent = [Float](repeating: 0, count: 256)
        silent.withUnsafeBufferPointer { XCTAssertEqual(VoxDetector.levelDbfs($0), VoxDetector.silenceDb) }
        let half = (0..<256).map { $0 % 2 == 0 ? Float(0.5) : Float(-0.5) }
        half.withUnsafeBufferPointer { XCTAssertEqual(VoxDetector.levelDbfs($0), -6.02, accuracy: 0.01) }
    }

    // MARK: §16.5 rider name

    func testRiderNameKeepsOrdinaryNames() {
        XCTAssertEqual(RiderName.clean("  Omer  "), "Omer")
        XCTAssertEqual(RiderName.clean("Jean-Luc O'Neil Jr."), "Jean-Luc O'Neil Jr.")
        XCTAssertEqual(RiderName.clean("Zoë 2"), "Zoë 2")
    }

    func testRiderNameStripsMarkupEmojiAndControls() {
        XCTAssertEqual(RiderName.clean("<b>Sam</b>\n\u{7}"), "b Sam b")
        XCTAssertEqual(RiderName.clean("\u{1F6B4} Kim"), "Kim")
    }

    func testRiderNameIsCappedAndNilWhenEmpty() {
        XCTAssertEqual(RiderName.clean(String(repeating: "A", count: 50)), String(repeating: "A", count: 20))
        XCTAssertNil(RiderName.clean("   "))
        XCTAssertNil(RiderName.clean("!!!"))
        XCTAssertNil(RiderName.clean(nil))
    }
}
