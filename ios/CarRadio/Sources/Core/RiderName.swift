import Foundation

/// PROTOCOL §16.5 optional rider name — identical to `RiderName` on Android and
/// `cleanRiderName` in the web app. Applied to what a rider types and to every received
/// handle.
public enum RiderName {
    public static let maxLength = 20

    /// Letters, digits, spaces and . ' - only, whitespace collapsed, ≤ 20 characters.
    /// Nil when nothing usable is left (the generated handle is used instead).
    public static func clean(_ raw: String?) -> String? {
        let allowed = CharacterSet.letters
            .union(.decimalDigits)
            .union(CharacterSet(charactersIn: " .'-"))
        let scalars = (raw ?? "").unicodeScalars.map { allowed.contains($0) ? Character($0) : " " }
        let collapsed = String(scalars)
            .split(separator: " ", omittingEmptySubsequences: true)
            .joined(separator: " ")
        let clipped = String(collapsed.unicodeScalars.prefix(maxLength).map(Character.init))
            .trimmingCharacters(in: .whitespaces)
        return clipped.isEmpty ? nil : clipped
    }
}
