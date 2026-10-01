import Foundation

/// The colour of the cover behind the Ride screen: a soft glow at `alpha`
/// over the black background. Only a backdrop: buttons, the link dot, TALK
/// and LIVE keep the fixed colours of `Brand`. The rule that picks and tames
/// the colour is Android's (`ui/Ambient.kt`, `ambientTint`), on 0xRRGGBB
/// integers, so it is tested here; RideView only draws it.
public enum Ambient {
    /// How strong the glow is at its strongest point.
    public static let alpha = 0.25
    /// The brightest the tinted background may get (relative luminance). At
    /// this, secondary text keeps about 7:1 and the red of a warning 3.5:1.
    public static let maxLuminance = 0.024
    /// The iPhone's background (dark scheme): black.
    public static let surface: UInt32 = 0x000000

    /// A swatch greyer than this (HSL saturation) is no colour: no glow.
    static let minSaturation = 0.12
    static let maxSaturation = 0.70
    static let minLightness = 0.30
    static let maxLightness = 0.50

    /// The tint for a cover, as 0xRRGGBB, or nil when it has no colour to
    /// speak of. `swatches` are its colours in order of preference: the first
    /// one that is not grey wins. Its saturation is capped and its lightness
    /// brought into a middle band, so a near-black cover still glows and a
    /// neon one does not shout; then it is darkened until `alpha` of it over
    /// `surface` stays under `maxLuminance`.
    public static func tint(_ swatches: [UInt32?], surface: UInt32 = surface) -> UInt32? {
        guard let hsl = swatches.lazy.compactMap({ $0 }).map(hsl).first(where: { $0.s >= minSaturation }) else {
            return nil
        }
        let s = min(hsl.s, maxSaturation)
        var l = min(max(hsl.l, minLightness), maxLightness)
        var rgb = fromHSL(h: hsl.h, s: s, l: l)
        while luminance(blend(rgb, alpha: alpha, over: surface)) > maxLuminance && l > 0.02 {
            l -= 0.02
            rgb = fromHSL(h: hsl.h, s: s, l: l)
        }
        return rgb
    }

    /// A cover's colours in order of preference, from its pixels (0xRRGGBB,
    /// a small downscaled copy is plenty): colours that are bright and
    /// saturated first, as Android's palette puts its vibrant swatches first,
    /// then the rest by how much of the cover they fill. Pixels are grouped
    /// into 4-bit-per-channel boxes; each box gives its average colour.
    public static func swatches(_ pixels: [UInt32], limit: Int = 6) -> [UInt32] {
        var boxes: [UInt32: (count: Int, r: Int, g: Int, b: Int)] = [:]
        for p in pixels {
            let r = Int(p >> 16 & 0xFF), g = Int(p >> 8 & 0xFF), b = Int(p & 0xFF)
            let key = UInt32(r >> 4) << 8 | UInt32(g >> 4) << 4 | UInt32(b >> 4)
            let box = boxes[key] ?? (0, 0, 0, 0)
            boxes[key] = (box.count + 1, box.r + r, box.g + g, box.b + b)
        }
        let total = max(1, pixels.count)
        let colours = boxes.values.map { box -> (rgb: UInt32, score: Double) in
            let rgb = UInt32(box.r / box.count) << 16 | UInt32(box.g / box.count) << 8 | UInt32(box.b / box.count)
            let c = hsl(rgb)
            let share = Double(box.count) / Double(total)
            // A colour needs a few percent of the cover to count as its own.
            let vibrant = share >= 0.02 && c.s >= 0.35 && c.l >= 0.2 && c.l <= 0.8
            return (rgb, (vibrant ? 1 : 0) + share)
        }
        return colours.sorted { $0.score > $1.score }.prefix(limit).map(\.rgb)
    }

    /// `top` at `alpha` over the opaque `bottom`, per channel, as the screen
    /// composites it.
    public static func blend(_ top: UInt32, alpha: Double, over bottom: UInt32) -> UInt32 {
        func channel(_ shift: UInt32) -> UInt32 {
            let t = Double(top >> shift & 0xFF), b = Double(bottom >> shift & 0xFF)
            return UInt32(t * alpha + b * (1 - alpha) + 0.5)
        }
        return channel(16) << 16 | channel(8) << 8 | channel(0)
    }

    /// WCAG relative luminance of an sRGB colour, 0 (black) … 1 (white).
    public static func luminance(_ rgb: UInt32) -> Double {
        func linear(_ shift: UInt32) -> Double {
            let c = Double(rgb >> shift & 0xFF) / 255
            return c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }

    /// WCAG contrast ratio of two colours, 1 … 21.
    public static func contrast(_ a: UInt32, _ b: UInt32) -> Double {
        let la = luminance(a), lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /// Hue in degrees, saturation and lightness 0…1.
    static func hsl(_ rgb: UInt32) -> (h: Double, s: Double, l: Double) {
        let r = Double(rgb >> 16 & 0xFF) / 255, g = Double(rgb >> 8 & 0xFF) / 255, b = Double(rgb & 0xFF) / 255
        let high = max(r, g, b), low = min(r, g, b)
        let d = high - low
        let l = (high + low) / 2
        if d == 0 { return (0, 0, l) }
        var h: Double
        if high == r {
            h = ((g - b) / d).truncatingRemainder(dividingBy: 6)
            if h < 0 { h += 6 }
        } else if high == g {
            h = (b - r) / d + 2
        } else {
            h = (r - g) / d + 4
        }
        return (h * 60, d / (1 - abs(2 * l - 1)), l)
    }

    static func fromHSL(h: Double, s: Double, l: Double) -> UInt32 {
        let c = (1 - abs(2 * l - 1)) * s
        var sector = (h / 60).truncatingRemainder(dividingBy: 2)
        if sector < 0 { sector += 2 }
        let x = c * (1 - abs(sector - 1))
        let m = l - c / 2
        let (r, g, b): (Double, Double, Double)
        switch Int(h / 60) {
        case 0: (r, g, b) = (c, x, 0)
        case 1: (r, g, b) = (x, c, 0)
        case 2: (r, g, b) = (0, c, x)
        case 3: (r, g, b) = (0, x, c)
        case 4: (r, g, b) = (x, 0, c)
        default: (r, g, b) = (c, 0, x)
        }
        func channel(_ v: Double) -> UInt32 { UInt32(min(255, max(0, Int((v + m) * 255 + 0.5)))) }
        return channel(r) << 16 | channel(g) << 8 | channel(b)
    }
}
