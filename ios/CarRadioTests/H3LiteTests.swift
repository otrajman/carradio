// H3LiteTests.swift
// Known-vector tests for the minimal H3 port.
//
// IMPORTANT: every expected value below was generated with official h3-js v4
// (latLngToCell / gridDisk / getPentagons). If H3Lite.swift or
// H3LiteTables.swift is ever modified, RE-VERIFY these vectors against h3-js
// before shipping — cross-platform room names must match exactly.
// (The port was additionally validated against 6,792 h3-js fixtures during
// development; see ios/HANDOFF.md.)

import XCTest
#if canImport(CarRadioCore)
@testable import CarRadioCore
#else
@testable import CarRadio
#endif

final class H3LiteTests: XCTestCase {
    private func assertCell(_ lat: Double, _ lng: Double, _ res: Int, _ expected: String,
                            file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(H3Lite.latLngToCell(lat: lat, lng: lng, res: res), expected, file: file, line: line)
    }

    func testLatLngToCellKnownVectors() {
        // San Francisco
        assertCell(37.7749, -122.4194, 7, "872830828ffffff")
        assertCell(37.7749, -122.4194, 8, "8828308281fffff")
        assertCell(37.7749, -122.4194, 9, "89283082803ffff")
        // New York
        assertCell(40.7128, -74.0060, 7, "872a1072cffffff")
        assertCell(40.7128, -74.0060, 8, "882a107289fffff")
        assertCell(40.7128, -74.0060, 9, "892a1072893ffff")
        // London
        assertCell(51.5074, -0.1278, 7, "87195da49ffffff")
        assertCell(51.5074, -0.1278, 8, "88195da49bfffff")
        assertCell(51.5074, -0.1278, 9, "89195da49b7ffff")
        // Tokyo
        assertCell(35.6762, 139.6503, 7, "872f5a363ffffff")
        assertCell(35.6762, 139.6503, 8, "882f5a363bfffff")
        assertCell(35.6762, 139.6503, 9, "892f5a363bbffff")
        // Sydney (southern hemisphere)
        assertCell(-33.8688, 151.2093, 7, "87be0e35cffffff")
        assertCell(-33.8688, 151.2093, 8, "88be0e35cbfffff")
        assertCell(-33.8688, 151.2093, 9, "89be0e35cbbffff")
        // São Paulo
        assertCell(-23.5505, -46.6333, 7, "87a8100c0ffffff")
        assertCell(-23.5505, -46.6333, 8, "88a8100c03fffff")
        assertCell(-23.5505, -46.6333, 9, "89a8100c02fffff")
        // Reykjavik (near a pentagon home face)
        assertCell(64.1466, -21.9426, 7, "87075dd4bffffff")
        assertCell(64.1466, -21.9426, 8, "88075dd4bdfffff")
        assertCell(64.1466, -21.9426, 9, "89075dd4b8bffff")
        // Poles
        assertCell(90, 0, 7, "870326233ffffff")
        assertCell(90, 0, 9, "890326233abffff")
        assertCell(-90, 0, 7, "87f29380effffff")
        assertCell(-90, 0, 9, "89f29380e0fffff")
        // Null Island
        assertCell(0, 0, 7, "87754e64dffffff")
        assertCell(0, 0, 8, "88754e6499fffff")
        assertCell(0, 0, 9, "89754e64993ffff")
    }

    func testLatLngToCellInvalidInput() {
        XCTAssertNil(H3Lite.latLngToCell(lat: 37.0, lng: -122.0, res: -1))
        XCTAssertNil(H3Lite.latLngToCell(lat: 37.0, lng: -122.0, res: 16))
        XCTAssertNil(H3Lite.latLngToCell(lat: .nan, lng: 0, res: 9))
        XCTAssertNil(H3Lite.latLngToCell(lat: 0, lng: .infinity, res: 9))
    }

    func testGridDiskK0AndInvalid() {
        XCTAssertEqual(H3Lite.gridDisk("872830828ffffff", k: 0), ["872830828ffffff"])
        XCTAssertEqual(H3Lite.gridDisk("not-a-cell", k: 1), ["not-a-cell"])
    }

    func testGridDiskSanFranciscoRes7() {
        // gridDisk("872830828ffffff", 1) from h3-js, sorted.
        let expected: Set<String> = [
            "872830828ffffff", "872830829ffffff", "87283082affffff",
            "87283082bffffff", "87283082cffffff", "87283082dffffff",
            "87283082effffff",
        ]
        let got = H3Lite.gridDisk("872830828ffffff", k: 1)
        XCTAssertEqual(Set(got), expected)
        XCTAssertEqual(got.count, 7, "hexagon k=1 disk must have exactly 7 cells")
        XCTAssertEqual(got.first, "872830828ffffff", "origin cell must come first")
    }

    func testGridDiskSanFranciscoRes9() {
        let expected: Set<String> = [
            "89283082803ffff", "89283082807ffff", "8928308280bffff",
            "8928308280fffff", "89283082813ffff", "89283082817ffff",
            "8928308281bffff",
        ]
        XCTAssertEqual(Set(H3Lite.gridDisk("89283082803ffff", k: 1)), expected)
    }

    func testGridDiskPentagonRes7() {
        // First res-7 pentagon from h3-js getPentagons(7); its k=1 disk has
        // only 6 cells (pentagons have 5 neighbors).
        let expected: Set<String> = [
            "870800000ffffff", "870800002ffffff", "870800003ffffff",
            "870800004ffffff", "870800005ffffff", "870800006ffffff",
        ]
        let got = H3Lite.gridDisk("870800000ffffff", k: 1)
        XCTAssertEqual(Set(got), expected)
        XCTAssertEqual(got.count, 6, "pentagon k=1 disk must have exactly 6 cells")
    }

    func testPentagonRegionLatLngToCell() {
        // Near the res-7 pentagon home (64.7, 10.536) — exercises pentagon
        // digit adjustments in the encoder.
        XCTAssertEqual(H3Lite.latLngToCell(lat: 64.7, lng: 10.5362, res: 7), "870800000ffffff")
    }

    func testSubscribeSetIsStableAcrossDiskOrdering() {
        // The protocol treats the disk as a SET of rooms; verify set semantics.
        let a = Set(H3Lite.gridDisk("872830828ffffff", k: 1))
        let b = Set(H3Lite.gridDisk("872830828ffffff", k: 1))
        XCTAssertEqual(a, b)
    }
}
