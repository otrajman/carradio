// PelotonTheme.swift
// Race-number / road-sign instrument for a phone on the handlebars. Daylight-first
// (riders are outdoors); dark "asphalt" follows the system. Safety orange = you're on
// air; pack green = the pack is talking. Same tokens as Android's PelotonTheme.kt.

import SwiftUI
import UIKit

enum PelotonPalette {
    static let background = dynamic(light: 0xF3F0E8, dark: 0x0E0F11)
    static let surface = dynamic(light: 0xFFFFFF, dark: 0x1A1B1F)
    static let ink = dynamic(light: 0x111214, dark: 0xF2F0EA)
    static let muted = dynamic(light: 0x66656B, dark: 0x8E8D94)
    static let line = dynamic(light: 0xD8D2C4, dark: 0x2C2D33)
    static let signal = dynamic(light: 0xFF4F1F, dark: 0xFF6A3D)
    static let onSignal = Color(hex: 0x111214)
    static let pack = dynamic(light: 0x0B8F63, dark: 0x34D399)

    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xff) / 255,
            green: CGFloat((hex >> 8) & 0xff) / 255,
            blue: CGFloat(hex & 0xff) / 255,
            alpha: 1
        )
    }
}

extension Color {
    init(hex: UInt32) { self.init(uiColor: UIColor(hex: hex)) }
}

struct Eyebrow: View {
    let text: String
    var color: Color = PelotonPalette.muted

    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 12, weight: .bold))
            .kerning(2.5)
            .foregroundStyle(color)
    }
}

/// 64 pt+ glove-sized button.
struct BigButton: View {
    let label: String
    let background: Color
    let foreground: Color
    var border: Color? = nil
    var height: CGFloat = 64
    var fontSize: CGFloat = 18
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: fontSize, weight: .black))
                .kerning(1)
                .foregroundStyle(foreground)
                .frame(maxWidth: .infinity, minHeight: height)
                .background(background, in: RoundedRectangle(cornerRadius: 14))
                .overlay {
                    if let border {
                        RoundedRectangle(cornerRadius: 14).stroke(border, lineWidth: 2)
                    }
                }
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
    }
}
