import Foundation

/// Short synthesized cues, returned as 16-bit mono WAV files so the app can
/// play them with AVAudioPlayer(data:) without bundling resources.
public enum EarconSynth {
    public static let sampleRate = 22_050

    /// "live": rising two-tone. "ok": single soft blip. "error": falling pair.
    /// "listen": one high blip, the wake word heard alone (the next phrase is
    /// a command); same note as Android's `Earcons.Kind.LISTEN`.
    public static func wav(for name: String) -> Data {
        switch name {
        case "live": return wav(tones: [(660, 0.07), (0, 0.03), (990, 0.09)])
        case "ok": return wav(tones: [(880, 0.10)])
        case "error": return wav(tones: [(440, 0.12), (0, 0.04), (330, 0.18)])
        case "end": return wav(tones: [(990, 0.07), (0, 0.03), (660, 0.09)])
        case "listen": return wav(tones: [(1175, 0.12)])
        default: return wav(tones: [(880, 0.05)])
        }
    }

    /// Sequence of (frequency Hz, seconds); frequency 0 = silence.
    public static func wav(tones: [(Double, Double)], amplitude: Double = 0.35) -> Data {
        var samples: [Int16] = []
        for (freq, seconds) in tones {
            let n = Int(seconds * Double(sampleRate))
            let fade = max(1, min(n / 4, sampleRate / 200)) // ≤5 ms ramps, no clicks
            for i in 0..<n {
                guard freq > 0 else { samples.append(0); continue }
                let env = min(1, Double(min(i, n - 1 - i)) / Double(fade))
                let v = sin(2 * .pi * freq * Double(i) / Double(sampleRate)) * amplitude * env
                samples.append(Int16(max(-1, min(1, v)) * Double(Int16.max)))
            }
        }
        return WAV.pcm16Mono(samples, sampleRate: sampleRate)
    }
}

public enum WAV {
    public static func pcm16Mono(_ samples: [Int16], sampleRate: Int) -> Data {
        var d = Data()
        func u32(_ v: UInt32) { d.append(contentsOf: [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8(v >> 24)]) }
        func u16(_ v: UInt16) { d.append(contentsOf: [UInt8(v & 0xFF), UInt8(v >> 8)]) }
        let dataBytes = UInt32(samples.count * 2)
        d.append(contentsOf: Array("RIFF".utf8)); u32(36 + dataBytes)
        d.append(contentsOf: Array("WAVE".utf8))
        d.append(contentsOf: Array("fmt ".utf8)); u32(16); u16(1); u16(1)
        u32(UInt32(sampleRate)); u32(UInt32(sampleRate * 2)); u16(2); u16(16)
        d.append(contentsOf: Array("data".utf8)); u32(dataBytes)
        for s in samples { u16(UInt16(bitPattern: s)) }
        return d
    }
}
