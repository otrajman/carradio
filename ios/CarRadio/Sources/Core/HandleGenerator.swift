// HandleGenerator.swift
// Per-trip phonetic handle: "<Adjective> <Animal>" (PROTOCOL §1).
// Word lists are embedded VERBATIM from docs/handles.json — identical across
// platforms. Do not edit the lists without updating docs/handles.json and
// every other client.
// Pure Foundation — no UIKit.

import Foundation

public enum HandleGenerator {
    /// docs/handles.json "adjectives" — verbatim, order preserved.
    public static let adjectives: [String] = [
        "Neon", "Crimson", "Silver", "Cobalt", "Amber", "Turbo", "Midnight", "Solar",
        "Electric", "Copper", "Ivory", "Onyx", "Scarlet", "Golden", "Azure", "Emerald",
        "Velvet", "Chrome", "Shadow", "Blazing", "Frost", "Thunder", "Drift", "Radiant",
        "Lucky", "Rusty", "Swift", "Quiet", "Wild", "Nova", "Retro", "Phantom",
    ]

    /// docs/handles.json "animals" — verbatim, order preserved.
    public static let animals: [String] = [
        "Falcon", "Otter", "Lynx", "Bison", "Coyote", "Heron", "Marlin", "Puma",
        "Raven", "Stallion", "Badger", "Condor", "Dingo", "Elk", "Fox", "Gazelle",
        "Hawk", "Ibex", "Jaguar", "Kestrel", "Llama", "Moose", "Narwhal", "Osprey",
        "Panther", "Quail", "Rhino", "Sparrow", "Tiger", "Viper", "Wolf", "Wombat",
    ]

    /// Generates a new random handle, e.g. "Neon Falcon".
    public static func generate() -> String {
        var rng = SystemRandomNumberGenerator()
        return generate(using: &rng)
    }

    /// Deterministic variant for tests.
    public static func generate<R: RandomNumberGenerator>(using rng: inout R) -> String {
        let adjective = adjectives.randomElement(using: &rng)!
        let animal = animals.randomElement(using: &rng)!
        return "\(adjective) \(animal)"
    }

    /// True when `handle` is a well-formed "<Adjective> <Animal>" from the lists.
    public static func isValid(_ handle: String) -> Bool {
        let parts = handle.split(separator: " ").map(String.init)
        guard parts.count == 2 else { return false }
        return adjectives.contains(parts[0]) && animals.contains(parts[1])
    }
}
