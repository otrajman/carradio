// RoomManager.swift
// Realtime room math per docs/PROTOCOL.md §3:
//   - subscribe set: gridDisk(myCell_res7, 1) → 7 rooms
//   - publish set:   own res-7 cell + res-7 cells intersecting the forward ray
//                    (points every 1.2 km out to senderRadius(speed)), ≤ 4 cells
// Room name: "room:<h3_res7_index>".
// Pure Foundation — no UIKit.

import Foundation

public enum RoomManager {
    public static let roomPrefix = "room:"
    public static let subscribeRes = 7
    public static let breadcrumbTriggerRes = 8
    public static let messageRes = 9
    /// Forward-ray sampling interval, meters (PROTOCOL §3).
    public static let rayStepMeters = 1_200.0
    /// Maximum number of publish rooms (PROTOCOL §3).
    public static let maxPublishRooms = 4

    public static func roomName(forCell cell: String) -> String {
        roomPrefix + cell
    }

    public static func res7Cell(lat: Double, lng: Double) -> String? {
        H3Lite.latLngToCell(lat: lat, lng: lng, res: subscribeRes)
    }

    public static func res8Cell(lat: Double, lng: Double) -> String? {
        H3Lite.latLngToCell(lat: lat, lng: lng, res: breadcrumbTriggerRes)
    }

    public static func res9Cell(lat: Double, lng: Double) -> String? {
        H3Lite.latLngToCell(lat: lat, lng: lng, res: messageRes)
    }

    /// Receiver subscribe set: own res-7 cell + k-ring 1 → 7 rooms.
    /// Recompute on every res-7 cell change (PROTOCOL §3).
    public static func subscribeRooms(lat: Double, lng: Double) -> Set<String> {
        guard let cell = res7Cell(lat: lat, lng: lng) else { return [] }
        return Set(H3Lite.gridDisk(cell, k: 1).map(roomName(forCell:)))
    }

    /// Sender publish set: own res-7 cell first, then the res-7 cells of points
    /// sampled every 1.2 km along the heading out to senderRadius(speed),
    /// deduped, capped at 4. Order is deterministic (own cell first, then by
    /// increasing distance).
    public static func publishRooms(lat: Double, lng: Double, heading: Double, speed: Double) -> [String] {
        guard let own = res7Cell(lat: lat, lng: lng) else { return [] }
        var cells: [String] = [own]
        var seen: Set<String> = [own]
        let radius = GeoMath.senderRadiusMeters(speedMps: speed)
        var distance = rayStepMeters
        while distance <= radius, cells.count < maxPublishRooms {
            let point = GeoMath.destination(lat: lat, lng: lng, bearingDeg: heading, distanceM: distance)
            if let cell = res7Cell(lat: point.lat, lng: point.lng), !seen.contains(cell) {
                seen.insert(cell)
                cells.append(cell)
            }
            distance += rayStepMeters
        }
        return cells.map(roomName(forCell:))
    }
}
