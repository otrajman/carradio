// BurstFilterTests.swift
// Receive-filter tests per docs/PROTOCOL.md §5 and §7.

import XCTest
#if canImport(CarRadioCore)
@testable import CarRadioCore
#else
@testable import CarRadio
#endif

final class BurstFilterTests: XCTestCase {
    private let receiverTrip = "11111111-1111-1111-1111-111111111111"
    private let senderTrip = "22222222-2222-2222-2222-222222222222"

    /// ~1 degree of longitude at 37.77N is ~88 km; helpers below place the
    /// receiver at exact great-circle offsets from the sender instead.
    private func payload(
        tripID: String? = nil,
        messageID: String = UUID().uuidString,
        kind: String = "voice",
        lat: Double = 37.7749,
        lng: Double = -122.4194,
        heading: Double = 90,
        speed: Double = 30
    ) -> BurstPayload {
        BurstPayload(
            messageID: messageID,
            tripID: tripID ?? senderTrip,
            handle: "Neon Falcon",
            kind: kind,
            audioPath: kind == "voice" ? "voice_bursts/\(tripID ?? senderTrip)/\(messageID).m4a" : nil,
            text: kind == "system" ? "System alert: test." : nil,
            lat: lat,
            lng: lng,
            heading: heading,
            speed: speed,
            h3R9: "89283082803ffff",
            createdAt: "2026-07-22T05:12:00.000Z"
        )
    }

    private func receiver(
        lat: Double = 37.7749,
        lng: Double = -122.4194,
        heading: Double = 90,
        speed: Double = 30
    ) -> GpsState {
        GpsState(lat: lat, lng: lng, heading: heading, speed: speed)
    }

    /// Receiver placed `meters` from the sender along `bearing`.
    private func receiverOffset(from p: BurstPayload, bearing: Double, meters: Double,
                                heading: Double, speed: Double = 30) -> GpsState {
        let dest = GeoMath.destination(lat: p.lat, lng: p.lng, bearingDeg: bearing, distanceM: meters)
        return GpsState(lat: dest.lat, lng: dest.lng, heading: heading, speed: speed)
    }

    private func evaluate(
        _ p: BurstPayload,
        _ r: GpsState,
        muted: Set<String> = [],
        elastic: Bool = false,
        filter: BurstFilter = BurstFilter()
    ) -> FilterResult {
        filter.evaluate(payload: p, receiverTripID: receiverTrip, receiver: r,
                        muted: muted, elasticMode: elastic)
    }

    // MARK: §5.1 self

    func testDropsOwnBurst() {
        let result = evaluate(payload(tripID: receiverTrip), receiver())
        XCTAssertEqual(result.verdict, .dropSelf)
    }

    // MARK: §5.2 dedupe

    func testDropsDuplicateMessageID() {
        let filter = BurstFilter()
        let p = payload()
        XCTAssertEqual(evaluate(p, receiver(), filter: filter).verdict, .play)
        XCTAssertEqual(evaluate(p, receiver(), filter: filter).verdict, .dropDuplicate)
    }

    func testDedupeLRUEvictsOldest() {
        let lru = SeenLRU(capacity: 2)
        lru.insert("a"); lru.insert("b"); lru.insert("c")
        XCTAssertFalse(lru.contains("a"))
        XCTAssertTrue(lru.contains("b"))
        XCTAssertTrue(lru.contains("c"))
        XCTAssertEqual(lru.count, 2)
    }

    // MARK: §5.3 mute

    func testDropsMutedSender() {
        let result = evaluate(payload(), receiver(), muted: [senderTrip])
        XCTAssertEqual(result.verdict, .dropMuted)
    }

    // MARK: §5.4 heading match

    func testSameHeadingPasses() {
        let result = evaluate(payload(heading: 90), receiver(heading: 90))
        XCTAssertEqual(result.verdict, .play)
        XCTAssertTrue(result.passesStrict)
    }

    func testHeadingWithin30DegreesPasses() {
        // dot(90°, 118°) = cos(28°) ≈ 0.883 ≥ 0.85
        XCTAssertEqual(evaluate(payload(heading: 90), receiver(heading: 118)).verdict, .play)
    }

    func testOpposingHeadingDropsEvenWhenClose() {
        // Same location (d = 0 ≤ 800 m) but opposite heading: §5.4 runs BEFORE
        // the near-bubble, so this must drop.
        let result = evaluate(payload(heading: 90), receiver(heading: 270))
        XCTAssertEqual(result.verdict, .dropHeading)
        XCTAssertFalse(result.passesStrict)
    }

    func testHeadingAt35DegreesDrops() {
        // dot(0°, 35°) = cos(35°) ≈ 0.819 < 0.85
        XCTAssertEqual(evaluate(payload(heading: 0), receiver(heading: 35)).verdict, .dropHeading)
    }

    func testHeadingWrapAround() {
        // 350° vs 10° is a 20° difference: cos(20°) ≈ 0.94 ≥ 0.85
        XCTAssertEqual(evaluate(payload(heading: 350), receiver(heading: 10)).verdict, .play)
    }

    func testSlowReceiverSkipsHeadingCheck() {
        // Receiver speed < 3 m/s (parked / jam creep): heading is noise.
        XCTAssertEqual(
            evaluate(payload(heading: 90), receiver(heading: 270, speed: 2.0)).verdict,
            .play
        )
    }

    func testSystemBurstSkipsHeadingCheck() {
        XCTAssertEqual(
            evaluate(payload(kind: "system", heading: 90), receiver(heading: 270)).verdict,
            .play
        )
    }

    // MARK: §5.5 distance / forward cone

    func testNearBubblePassesRegardlessOfCone() {
        // 500 m BEHIND the sender (bearing 270 while sender heads 90): the
        // ≤ 800 m near-bubble is omnidirectional.
        let p = payload(heading: 90, speed: 30)
        let r = receiverOffset(from: p, bearing: 270, meters: 500, heading: 90)
        XCTAssertEqual(evaluate(p, r).verdict, .play)
    }

    func testAheadWithinRadiusPasses() {
        // Sender at 30 m/s (~67 mph) → radius ≈ 2.68 mi ≈ 4315 m.
        // Receiver 2 km directly ahead.
        let p = payload(heading: 90, speed: 30)
        let r = receiverOffset(from: p, bearing: 90, meters: 2_000, heading: 90)
        let result = evaluate(p, r)
        XCTAssertEqual(result.verdict, .play)
        XCTAssertTrue(result.passesStrict)
    }

    func testBehindSenderBeyondBubbleDrops() {
        // Receiver 2 km BEHIND the sender: outside bubble, aheadDot = -1.
        let p = payload(heading: 90, speed: 30)
        let r = receiverOffset(from: p, bearing: 270, meters: 2_000, heading: 90)
        XCTAssertEqual(evaluate(p, r).verdict, .dropDistance)
    }

    func testAheadCone60DegreeEdge() {
        // 55° off the sender's heading, 2 km out: cos(55°) ≈ 0.574 ≥ 0.5 → pass.
        let p = payload(heading: 0, speed: 30)
        let pass = receiverOffset(from: p, bearing: 55, meters: 2_000, heading: 0)
        XCTAssertEqual(evaluate(p, pass).verdict, .play)
        // 65° off: cos(65°) ≈ 0.42 < 0.5 → drop.
        let p2 = payload(heading: 0, speed: 30)
        let fail = receiverOffset(from: p2, bearing: 65, meters: 2_000, heading: 0)
        XCTAssertEqual(evaluate(p2, fail).verdict, .dropDistance)
    }

    func testBeyondSenderRadiusDrops() {
        // Sender at 6.7 m/s (15 mph) → radius 0.5 mi ≈ 805 m. Receiver 1.5 km ahead.
        let p = payload(heading: 90, speed: 6.7)
        let r = receiverOffset(from: p, bearing: 90, meters: 1_500, heading: 90)
        XCTAssertEqual(evaluate(p, r).verdict, .dropDistance)
    }

    func testAsymmetricDelivery() {
        // Fast car (30 m/s) 2 km behind a slow truck (7 m/s): the fast car's
        // burst reaches the truck (truck is ahead, inside the big radius)…
        let fastBurst = payload(heading: 90, speed: 30)
        let truck = receiverOffset(from: fastBurst, bearing: 90, meters: 2_000, heading: 90, speed: 7)
        XCTAssertEqual(evaluate(fastBurst, truck).verdict, .play)

        // …but the truck's burst does NOT reach the fast car behind it
        // (behind → aheadDot < 0.5, and outside the truck's small radius anyway).
        let truckBurst = payload(lat: truck.lat, lng: truck.lng, heading: 90, speed: 7)
        let fastCar = GpsState(lat: fastBurst.lat, lng: fastBurst.lng, heading: 90, speed: 30)
        XCTAssertEqual(evaluate(truckBurst, fastCar).verdict, .dropDistance)
    }

    // MARK: senderRadius spec points

    func testSenderRadiusSpecPoints() {
        // 15 mph → 0.5 mi
        XCTAssertEqual(GeoMath.senderRadiusMeters(speedMps: 15 / GeoMath.mphPerMps),
                       0.5 * GeoMath.metersPerMile, accuracy: 0.5)
        // 75 mph → 3.0 mi
        XCTAssertEqual(GeoMath.senderRadiusMeters(speedMps: 75 / GeoMath.mphPerMps),
                       3.0 * GeoMath.metersPerMile, accuracy: 0.5)
        // clamps below 15 mph and above 75 mph
        XCTAssertEqual(GeoMath.senderRadiusMeters(speedMps: 0), 0.5 * GeoMath.metersPerMile, accuracy: 0.5)
        XCTAssertEqual(GeoMath.senderRadiusMeters(speedMps: 60), 3.0 * GeoMath.metersPerMile, accuracy: 0.5)
        // 45 mph → 0.5 + 30 × (2.5/60) = 1.75 mi
        XCTAssertEqual(GeoMath.senderRadiusMeters(speedMps: 45 / GeoMath.mphPerMps),
                       1.75 * GeoMath.metersPerMile, accuracy: 0.5)
    }

    // MARK: §7 elastic mode

    func testElasticModePlaysWrongHeadingAndFarBursts() {
        let p = payload(heading: 90, speed: 30)
        let r = receiverOffset(from: p, bearing: 270, meters: 2_000, heading: 270)
        let result = evaluate(p, r, elastic: true)
        XCTAssertEqual(result.verdict, .play)
        XCTAssertFalse(result.passesStrict, "strict verdict must still be computed for elastic exit")
    }

    func testElasticModeStillDropsSelfMutedAndDuplicates() {
        let filter = BurstFilter()
        XCTAssertEqual(evaluate(payload(tripID: receiverTrip), receiver(), elastic: true, filter: filter).verdict, .dropSelf)
        XCTAssertEqual(evaluate(payload(), receiver(), muted: [senderTrip], elastic: true, filter: filter).verdict, .dropMuted)
        let p = payload()
        _ = evaluate(p, receiver(), elastic: true, filter: filter)
        XCTAssertEqual(evaluate(p, receiver(), elastic: true, filter: filter).verdict, .dropDuplicate)
    }

    func testElasticStateMachine() {
        let elastic = ElasticMode()
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        elastic.updatePresence(peerCount: 0, now: t0)
        XCTAssertFalse(elastic.isElastic)
        // 2 minutes of silence: not yet.
        elastic.tick(now: t0.addingTimeInterval(120))
        XCTAssertFalse(elastic.isElastic)
        // 3 minutes: elastic.
        elastic.tick(now: t0.addingTimeInterval(180))
        XCTAssertTrue(elastic.isElastic)
        // Exit immediately on ≥ 2 peers.
        elastic.updatePresence(peerCount: 2, now: t0.addingTimeInterval(200))
        XCTAssertFalse(elastic.isElastic)
        // Re-enter after another silent 3 minutes.
        let t1 = t0.addingTimeInterval(300)
        elastic.updatePresence(peerCount: 0, now: t1)
        elastic.tick(now: t1.addingTimeInterval(181))
        XCTAssertTrue(elastic.isElastic)
        // Exit immediately when a burst passes the strict filter.
        elastic.noteStrictFilterPass(now: t1.addingTimeInterval(200))
        XCTAssertFalse(elastic.isElastic)
    }

    func testElasticClockResetsWhenPeerAppears() {
        let elastic = ElasticMode()
        let t0 = Date(timeIntervalSince1970: 2_000_000)
        elastic.updatePresence(peerCount: 0, now: t0)
        // A single peer appears at t+100 (1 peer doesn't exit, but resets silence).
        elastic.updatePresence(peerCount: 1, now: t0.addingTimeInterval(100))
        // Silence resumes at t+120; at t+240 only 120 s have elapsed → not elastic.
        elastic.updatePresence(peerCount: 0, now: t0.addingTimeInterval(120))
        elastic.tick(now: t0.addingTimeInterval(240))
        XCTAssertFalse(elastic.isElastic)
        elastic.tick(now: t0.addingTimeInterval(301))
        XCTAssertTrue(elastic.isElastic)
    }

    // MARK: payload codable round-trip

    func testBurstPayloadWireFormat() throws {
        let json = """
        {
          "v": 1,
          "message_id": "aaaa-bbbb",
          "trip_id": "cccc-dddd",
          "handle": "Neon Falcon",
          "kind": "voice",
          "audio_path": "voice_bursts/cccc-dddd/aaaa-bbbb.m4a",
          "text": null,
          "lat": 37.7749,
          "lng": -122.4194,
          "heading": 271.5,
          "speed": 29.1,
          "h3_r9": "89283082803ffff",
          "created_at": "2026-07-23T05:12:00.000Z"
        }
        """
        let decoded = try JSONDecoder().decode(BurstPayload.self, from: Data(json.utf8))
        XCTAssertEqual(decoded.messageID, "aaaa-bbbb")
        XCTAssertEqual(decoded.tripID, "cccc-dddd")
        XCTAssertEqual(decoded.kind, "voice")
        XCTAssertNil(decoded.text)
        XCTAssertEqual(decoded.speed, 29.1, accuracy: 1e-9)
        XCTAssertEqual(decoded.h3R9, "89283082803ffff")

        // Round-trip keeps snake_case keys.
        let data = try JSONEncoder().encode(decoded)
        let dict = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertNotNil(dict["message_id"])
        XCTAssertNotNil(dict["h3_r9"])
        XCTAssertNotNil(dict["created_at"])
    }
}
