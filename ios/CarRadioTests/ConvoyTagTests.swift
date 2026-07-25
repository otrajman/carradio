import XCTest
#if canImport(CarRadioCore)
@testable import CarRadioCore
#else
@testable import CarRadio
#endif

final class ConvoyTagTests: XCTestCase {

    /// SHA256Lite against FIPS 180-4 known vectors.
    func testSha256KnownVectors() {
        func hex(_ bytes: [UInt8]) -> String {
            bytes.map { String(format: "%02x", $0) }.joined()
        }
        XCTAssertEqual(
            hex(SHA256Lite.hash(Array("abc".utf8))),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        )
        XCTAssertEqual(
            hex(SHA256Lite.hash([])),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )
        XCTAssertEqual(
            hex(SHA256Lite.hash(Array("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".utf8))),
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
        )
    }

    func testNormalizationAndTagShape() {
        XCTAssertEqual(ConvoyTag.fromCode("  Road Trip  2026 "), ConvoyTag.fromCode("road trip 2026"))
        XCTAssertEqual(ConvoyTag.fromCode("x")?.count, 16)
        XCTAssertNil(ConvoyTag.fromCode("   "))
        XCTAssertNil(ConvoyTag.fromCode(nil))
    }

    func testConvoyFilterRules() {
        let filter = BurstFilter()
        let tag = "aabbccdd00112233"
        let receiver = GpsState(lat: 37.0, lng: -122.0, heading: 90, speed: 25)

        func burst(_ id: String, convoy: String?, kind: String = "voice") -> BurstPayload {
            BurstPayload(
                messageID: id, tripID: "sender", handle: "Neon Falcon", kind: kind,
                audioPath: nil, text: "hi", lat: 37.0, lng: -122.0, heading: 90,
                speed: 25, h3R9: "", createdAt: "2026-07-25T00:00:00.000Z", convoy: convoy
            )
        }

        // Convoy match plays even before geometry.
        XCTAssertEqual(
            filter.evaluate(payload: burst("v1", convoy: tag), receiverTripID: "me",
                            receiver: receiver, muted: [], elasticMode: false,
                            convoyTag: tag).verdict,
            .play
        )
        // Convoy member never hears public traffic.
        XCTAssertEqual(
            filter.evaluate(payload: burst("v2", convoy: nil), receiverTripID: "me",
                            receiver: receiver, muted: [], elasticMode: false,
                            convoyTag: tag).verdict,
            .dropConvoy
        )
        // ...but hears nearby system bursts.
        XCTAssertEqual(
            filter.evaluate(payload: burst("v3", convoy: nil, kind: "system"),
                            receiverTripID: "me", receiver: receiver, muted: [],
                            elasticMode: false, convoyTag: tag).verdict,
            .play
        )
        // Public receiver never hears convoy traffic.
        XCTAssertEqual(
            filter.evaluate(payload: burst("v4", convoy: tag), receiverTripID: "me",
                            receiver: receiver, muted: [], elasticMode: false,
                            convoyTag: nil).verdict,
            .dropConvoy
        )
        // Mute wins over convoy match.
        XCTAssertEqual(
            filter.evaluate(payload: burst("v5", convoy: tag), receiverTripID: "me",
                            receiver: receiver, muted: ["sender"], elasticMode: false,
                            convoyTag: tag).verdict,
            .dropMuted
        )
    }
}
