// GeoMath.swift
// Spherical geometry + unit conversions per docs/PROTOCOL.md §2 and §5.
// Pure Foundation — no UIKit.

import Foundation

public enum GeoMath {
    /// Mean Earth radius in meters (used for haversine + destination points).
    public static let earthRadiusM = 6_371_000.0

    public static let metersPerMile = 1_609.34
    public static let mphPerMps = 2.236936292054402

    /// Great-circle distance in meters between two WGS84 points.
    public static func haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double) -> Double {
        let p1 = lat1 * .pi / 180
        let p2 = lat2 * .pi / 180
        let dp = (lat2 - lat1) * .pi / 180
        let dl = (lng2 - lng1) * .pi / 180
        let a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        let c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusM * c
    }

    /// Initial bearing from point 1 to point 2, degrees clockwise from true north [0, 360).
    public static func bearingDegrees(lat1: Double, lng1: Double, lat2: Double, lng2: Double) -> Double {
        let p1 = lat1 * .pi / 180
        let p2 = lat2 * .pi / 180
        let dl = (lng2 - lng1) * .pi / 180
        let y = sin(dl) * cos(p2)
        let x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        let deg = atan2(y, x) * 180 / .pi
        return (deg + 360).truncatingRemainder(dividingBy: 360)
    }

    /// Destination point given start, bearing (degrees) and distance (meters).
    public static func destination(lat: Double, lng: Double, bearingDeg: Double, distanceM: Double) -> (lat: Double, lng: Double) {
        let delta = distanceM / earthRadiusM
        let theta = bearingDeg * .pi / 180
        let p1 = lat * .pi / 180
        let l1 = lng * .pi / 180
        let p2 = asin(sin(p1) * cos(delta) + cos(p1) * sin(delta) * cos(theta))
        let l2 = l1 + atan2(sin(theta) * sin(delta) * cos(p1), cos(delta) - sin(p1) * sin(p2))
        var lngOut = l2 * 180 / .pi
        // normalize to [-180, 180)
        lngOut = (lngOut + 540).truncatingRemainder(dividingBy: 360) - 180
        return (p2 * 180 / .pi, lngOut)
    }

    /// PROTOCOL §5.5: sender-owned forward radius from speed.
    /// mph = mps × 2.2369…; miles = 0.5 + (mph − 15) × (2.5 / 60), clamped to
    /// [0.5, 3.0]; radius = miles × 1609.34. (15 mph → 0.5 mi, 75 mph → 3 mi.)
    public static func senderRadiusMeters(speedMps: Double) -> Double {
        let mph = speedMps * mphPerMps
        var miles = 0.5 + (mph - 15.0) * (2.5 / 60.0)
        miles = min(3.0, max(0.5, miles))
        return miles * metersPerMile
    }

    /// PROTOCOL §5.4: heading alignment dot product (headings in degrees).
    public static func headingDot(_ aDeg: Double, _ bDeg: Double) -> Double {
        let a = aDeg * .pi / 180
        let b = bDeg * .pi / 180
        return sin(a) * sin(b) + cos(a) * cos(b)
    }
}
