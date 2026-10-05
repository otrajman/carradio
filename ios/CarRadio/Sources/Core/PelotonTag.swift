import Foundation

/// PelotonCB pack identity + channel naming, PROTOCOL §16. Identical on every platform.
///
/// Pack tags ride in the same `convoy` payload field / `messages.convoy_tag` column as §14,
/// but are derived under a "pelotoncb:" namespace so a bike pack code can never collide
/// with a car convoy code, and Car Radio clients drop peloton traffic by the §14 rules.
public enum PelotonTag {

    private static let namespace = "pelotoncb:"
    public static let minCodeLength = 4

    /// Codes compare on ASCII letters + digits only: "Hill-4821" == "hill 4821" == "HILL4821".
    public static func normalizeCode(_ code: String) -> String {
        String(code.lowercased().unicodeScalars.filter {
            ("a"..."z").contains($0) || ("0"..."9").contains($0)
        }.map(Character.init))
    }

    /// Tag for a join-by-code pack; nil when the code is too short to be a pack.
    public static func fromCode(_ code: String?) -> String? {
        let normalized = normalizeCode(code ?? "")
        guard normalized.count >= minCodeLength else { return nil }
        return ConvoyTag.fromCode(namespace + "code:" + normalized)
    }

    /// Reserved pack code: never empty — the server seats three synthetic riders (§16.7).
    public static let demoCode = "DEMO"
    /// Synthetic riders the server seats in the demo pack (added to the roster client-side).
    public static let demoRiders = 3
    public static func isDemo(_ code: String?) -> Bool { normalizeCode(code ?? "") == "demo" }

    /// Tag shared by every rider in open-road (geo) mode.
    public static let openRoad: String = ConvoyTag.fromCode(namespace + "open")!

    public static func packChannel(_ tag: String) -> String { "peloton:pack:\(tag)" }

    public static func geoChannel(_ res8Cell: String) -> String { "peloton:geo:\(res8Cell)" }

    private static let words = [
        "HILL", "SPIN", "DRAFT", "SPRINT", "CLIMB", "GRAVEL", "TEMPO", "CADENCE",
        "ECHELON", "BONK", "CHAIN", "GRUPPO", "DESCENT", "RIDGE", "COL", "PAVE",
    ]

    /// Human-shareable code for a new pack, e.g. "CLIMB-4821".
    public static func generateCode() -> String {
        var rng = SystemRandomNumberGenerator()
        return generateCode(using: &rng)
    }

    public static func generateCode<R: RandomNumberGenerator>(using rng: inout R) -> String {
        words.randomElement(using: &rng)! + "-" + String(Int.random(in: 1000...9999, using: &rng))
    }
}

/// PROTOCOL §16 open-road reach: symmetric radius around the receiver plus a loose
/// same-direction check so an oncoming group passing by doesn't bleed into your pack.
public enum PelotonGeo {
    public static let packRadiusMeters = 500.0
    public static let headingDotThreshold = 0.5     // ±60°: switchbacks still match
    public static let minSpeedForHeadingMps = 3.0   // regroup stops: heading is noise

    public static func inRange(sender: BurstPayload, receiver: GpsState) -> Bool {
        let d = GeoMath.haversineMeters(
            lat1: sender.lat, lng1: sender.lng,
            lat2: receiver.lat, lng2: receiver.lng
        )
        guard d <= packRadiusMeters else { return false }
        let bothMoving = sender.speed >= minSpeedForHeadingMps
            && receiver.speed >= minSpeedForHeadingMps
        guard bothMoving else { return true }
        return GeoMath.headingDot(sender.heading, receiver.heading) >= headingDotThreshold
    }
}
