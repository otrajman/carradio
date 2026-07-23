// HandleGeneratorTests.swift

import XCTest
#if canImport(CarRadioCore)
@testable import CarRadioCore
#else
@testable import CarRadio
#endif

final class HandleGeneratorTests: XCTestCase {
    func testWordListsMatchHandlesJSONVerbatim() {
        // Counts and spot values from docs/handles.json — if these fail, the
        // embedded lists have drifted from the shared source of truth.
        XCTAssertEqual(HandleGenerator.adjectives.count, 32)
        XCTAssertEqual(HandleGenerator.animals.count, 32)
        XCTAssertEqual(HandleGenerator.adjectives.first, "Neon")
        XCTAssertEqual(HandleGenerator.adjectives.last, "Phantom")
        XCTAssertEqual(HandleGenerator.adjectives[8], "Electric")
        XCTAssertEqual(HandleGenerator.animals.first, "Falcon")
        XCTAssertEqual(HandleGenerator.animals.last, "Wombat")
        XCTAssertEqual(HandleGenerator.animals[22], "Narwhal")
        XCTAssertEqual(Set(HandleGenerator.adjectives).count, 32, "no duplicate adjectives")
        XCTAssertEqual(Set(HandleGenerator.animals).count, 32, "no duplicate animals")
    }

    func testGeneratedHandleIsWellFormed() {
        for _ in 0..<200 {
            let handle = HandleGenerator.generate()
            XCTAssertTrue(HandleGenerator.isValid(handle), "bad handle: \(handle)")
            let parts = handle.split(separator: " ")
            XCTAssertEqual(parts.count, 2)
        }
    }

    func testDeterministicWithSeededGenerator() {
        struct FixedRNG: RandomNumberGenerator {
            var state: UInt64
            mutating func next() -> UInt64 {
                state = state &* 6364136223846793005 &+ 1442695040888963407
                return state
            }
        }
        var a = FixedRNG(state: 42)
        var b = FixedRNG(state: 42)
        XCTAssertEqual(HandleGenerator.generate(using: &a), HandleGenerator.generate(using: &b))
    }

    func testIsValidRejectsGarbage() {
        XCTAssertFalse(HandleGenerator.isValid(""))
        XCTAssertFalse(HandleGenerator.isValid("Neon"))
        XCTAssertFalse(HandleGenerator.isValid("Neon Neon"))
        XCTAssertFalse(HandleGenerator.isValid("Falcon Neon"))
        XCTAssertFalse(HandleGenerator.isValid("Neon Falcon Extra"))
    }
}
