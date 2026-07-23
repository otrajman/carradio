// RoomManagerTests.swift
// Room math per docs/PROTOCOL.md §3.

import XCTest
#if canImport(CarRadioCore)
@testable import CarRadioCore
#else
@testable import CarRadio
#endif

final class RoomManagerTests: XCTestCase {
    // San Francisco
    private let lat = 37.7749
    private let lng = -122.4194

    func testSubscribeRoomsIsKRing1WithPrefix() {
        let rooms = RoomManager.subscribeRooms(lat: lat, lng: lng)
        XCTAssertEqual(rooms.count, 7)
        XCTAssertTrue(rooms.contains("room:872830828ffffff"), "must contain own res-7 cell")
        for room in rooms {
            XCTAssertTrue(room.hasPrefix("room:"))
            XCTAssertEqual(room.count, "room:".count + 15, "res-7 index is 15 hex chars")
        }
    }

    func testPublishRoomsSlowSpeedIsOwnCellPlusShortRay() {
        // 15 mph → radius ≈ 805 m < 1200 m step ⇒ ray contributes nothing.
        let rooms = RoomManager.publishRooms(lat: lat, lng: lng, heading: 90, speed: 6.7)
        XCTAssertEqual(rooms, ["room:872830828ffffff"])
    }

    func testPublishRoomsFastSpeedCappedAt4() {
        // 75+ mph → radius ≈ 4828 m ⇒ points at 1.2/2.4/3.6/4.8 km.
        let rooms = RoomManager.publishRooms(lat: lat, lng: lng, heading: 90, speed: 40)
        XCTAssertLessThanOrEqual(rooms.count, 4)
        XCTAssertEqual(rooms.first, "room:872830828ffffff", "own cell always first")
        XCTAssertEqual(rooms.count, Set(rooms).count, "no duplicates")
    }

    func testPublishRoomsAreWithinSubscribeReachOfRay() {
        // Every publish room must be a valid res-7 room name.
        for heading in stride(from: 0.0, to: 360.0, by: 45.0) {
            let rooms = RoomManager.publishRooms(lat: lat, lng: lng, heading: heading, speed: 35)
            XCTAssertFalse(rooms.isEmpty)
            for room in rooms {
                XCTAssertTrue(room.hasPrefix("room:"))
                XCTAssertEqual(room.count, 20)
            }
        }
    }

    func testResolutionHelpers() {
        XCTAssertEqual(RoomManager.res7Cell(lat: lat, lng: lng), "872830828ffffff")
        XCTAssertEqual(RoomManager.res8Cell(lat: lat, lng: lng), "8828308281fffff")
        XCTAssertEqual(RoomManager.res9Cell(lat: lat, lng: lng), "89283082803ffff")
    }

    func testPlayedIDStoreCapsAt2000AndPersists() {
        let suiteName = "test-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let store = PlayedIDStore(defaults: defaults)
        for i in 0..<2_100 {
            store.markPlayed("id-\(i)")
        }
        XCTAssertEqual(store.count, PlayedIDStore.cap)
        XCTAssertFalse(store.contains("id-0"), "oldest ids evicted")
        XCTAssertTrue(store.contains("id-2099"))

        // Persists across instances.
        let reloaded = PlayedIDStore(defaults: defaults)
        XCTAssertEqual(reloaded.count, PlayedIDStore.cap)
        XCTAssertTrue(reloaded.contains("id-2099"))
    }
}
