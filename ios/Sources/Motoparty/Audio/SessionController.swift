#if os(iOS)
import AVFoundation
import Foundation
import MotopartyCore

/// Which AVAudioSession configuration is active.
enum AudioRoute: String {
    /// `.playback`: AirPods on A2DP (stereo, full quality). Mic closed.
    case media
    /// `.playAndRecord` + `.voiceChat`: talk (voice processing) on the best
    /// headset (Bluetooth HFP, else a wired or USB headset; `TalkRoute`),
    /// and, in a talk this phone opened, the speech recognition of its first
    /// phrase, which listens to the talk's mic.
    case talk
    /// A host-mic talk (PROTOCOL.md "Host-mic talk"): the talk's voice plays
    /// in exactly the media configuration, `.playback` / `.default`, no
    /// options. The same category as `.media`, so activating it changes
    /// nothing under the session: the earbuds stay on A2DP (no HFP, no
    /// profile switch, no route change, no separate call volume), there is no
    /// input at all (no microphone, no permission), and `.playback` never
    /// routes to the built-in receiver, so with no headset it is the speaker.
    case listen
}

/// Owns AVAudioSession: switches music (A2DP) ↔ talk (HFP), or keeps A2DP
/// for a host-mic talk (`.listen`), and turns
/// session notifications into callbacks (all on the main queue).
///
/// The A2DP↔HFP switch takes ~1-2 s on AirPods; the mic only opens on demand.
final class SessionController {
    var onInterruptionBegan: (() -> Void)?
    /// `shouldResume`: the system says it is appropriate to resume audio.
    var onInterruptionEnded: ((Bool) -> Void)?
    var onRouteChange: ((AVAudioSession.RouteChangeReason) -> Void)?
    var onMediaServicesReset: (() -> Void)?
    /// AirPods stem press while the mic is open (iOS 17 mute gesture).
    var onMuteGesture: (() -> Void)?

    private let session = AVAudioSession.sharedInstance()
    private(set) var route: AudioRoute?
    private var observers: [NSObjectProtocol] = []
    private var muteObserver: NSObjectProtocol?

    init() {
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(forName: AVAudioSession.interruptionNotification, object: session,
                                        queue: .main) { [weak self] n in self?.interruption(n) })
        observers.append(nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: session,
                                        queue: .main) { [weak self] n in self?.routeChanged(n) })
        observers.append(nc.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: session,
                                        queue: .main) { [weak self] _ in
            self?.route = nil
            self?.onMediaServicesReset?()
        })
    }

    deinit {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        if let muteObserver { NotificationCenter.default.removeObserver(muteObserver) }
    }

    private static let mediaSampleRate: Double = 48_000
    /// iOS's default I/O buffer, 1024 frames (there is no "unset").
    private static let mediaIOBufferDuration: TimeInterval = 1024.0 / 48_000

    func activate(_ newRoute: AudioRoute) throws {
        switch newRoute {
        case .media, .listen:
            try session.setCategory(.playback, mode: .default, options: [])
            // The talk session's preferences outlive its category: back to
            // the music rate and the default I/O buffer.
            try? session.setPreferredSampleRate(Self.mediaSampleRate)
            try? session.setPreferredIOBufferDuration(Self.mediaIOBufferDuration)
        case .talk:
            // `.defaultToSpeaker`: with no headset the talk (and its earcons)
            // plays on the loudspeaker, not the quiet earpiece. A Bluetooth
            // HFP, wired or USB headset still wins (`preferTalkInput`); voice
            // processing cancels the speaker's echo.
            try session.setCategory(.playAndRecord, mode: .voiceChat,
                                    options: [.allowBluetoothHFP, .defaultToSpeaker])
            try? session.setPreferredSampleRate(16_000)
            try? session.setPreferredIOBufferDuration(0.01)
        }
        try session.setActive(true)
        route = newRoute
        if newRoute == .talk { preferTalkInput() }
        avoidReceiver()
        Log.audio.info("session → \(newRoute.rawValue, privacy: .public), out: \(self.outputName, privacy: .public) (\(self.describe, privacy: .public))")
    }

    /// Re-applies the current route (after an interruption or a media reset).
    func reactivate() {
        guard let route else { return }
        do { try activate(route) } catch {
            Log.audio.error("reactivate failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    var outputName: String { session.currentRoute.outputs.first?.portName ?? "none" }
    /// The output as far as the music sync offset cares (`LatencyTrims`).
    var outputRoute: OutputRoute {
        let out = session.currentRoute.outputs.first
        return OutputRoute.of(portType: out?.portType.rawValue, uid: out?.uid ?? "", name: out?.portName ?? "")
    }
    /// `category/mode, out <type/name>+…, in <type/name>+…` for the log.
    var describe: String {
        let route = session.currentRoute
        let out = route.outputs.map { "\($0.portType.rawValue)/\($0.portName)" }.joined(separator: "+")
        let inp = route.inputs.map { "\($0.portType.rawValue)/\($0.portName)" }.joined(separator: "+")
        return "\(session.category.rawValue)/\(session.mode.rawValue), out \(out.isEmpty ? "none" : out), in \(inp.isEmpty ? "none" : inp)"
    }
    var inputName: String { session.currentRoute.inputs.first?.portName ?? "none" }
    var outputLatencyMs: Double { session.outputLatency * 1000 }
    var hasHeadphones: Bool {
        session.currentRoute.outputs.contains { [.bluetoothA2DP, .bluetoothHFP, .bluetoothLE, .headphones, .usbAudio].contains($0.portType) }
    }
    /// The output is Bluetooth HFP: the earbuds are in call mode. Read just
    /// before the switch back to media (`AnnounceGate`: only an HFP talk has
    /// a profile switch to wait for) and at each route change after it.
    var outputIsBluetoothHFP: Bool {
        session.currentRoute.outputs.contains { $0.portType == .bluetoothHFP }
    }
    /// The output's port type, for the `announce gate:` line.
    var outputType: String { session.currentRoute.outputs.first?.portType.rawValue ?? "none" }

    /// `.granted` / `.denied` / `.undetermined` — checked before opening talk,
    /// because a missing permission means "cannot", not "won't".
    var recordPermission: AVAudioApplication.recordPermission { AVAudioApplication.shared.recordPermission }

    func requestRecordPermission(_ completion: @escaping (Bool) -> Void) {
        AVAudioApplication.requestRecordPermission { granted in
            DispatchQueue.main.async { completion(granted) }
        }
    }

    /// While the mic is open, an AirPods stem press toggles the system mute
    /// state instead of sending a remote command. Treat it as a button and
    /// keep the input unmuted. (Untested: Spike 2.)
    ///
    /// `setInputMuteStateChangeHandler` is macOS-only — iOS does the muting
    /// itself and only reports it through this notification — so the press is
    /// read from the report and the input is immediately unmuted again. The
    /// unmute posts a second notification, which the `muted` guard drops, so
    /// one press stays one action.
    func armMuteGesture() {
        guard muteObserver == nil else { return }
        muteObserver = NotificationCenter.default.addObserver(
            forName: AVAudioApplication.inputMuteStateChangeNotification, object: nil, queue: .main
        ) { [weak self] n in
            let muted = (n.userInfo?[AVAudioApplication.muteStateKey] as? NSNumber)?.boolValue ?? false
            guard muted else { return }
            try? AVAudioApplication.shared.setInputMuted(false)
            self?.onMuteGesture?()
        }
    }

    func disarmMuteGesture() {
        if let muteObserver { NotificationCenter.default.removeObserver(muteObserver) }
        muteObserver = nil
        try? AVAudioApplication.shared.setInputMuted(false)
    }

    // MARK: - Notifications

    /// Belt and braces for `.defaultToSpeaker`: in talk, never the built-in
    /// receiver. Only overrides when the receiver really is the output, so a
    /// headset is never pulled off; `.none` clears it for any other route.
    private func avoidReceiver() {
        guard route == .talk else { return }
        let onReceiver = session.currentRoute.outputs.contains { $0.portType == .builtInReceiver }
        guard onReceiver else { return }
        do {
            try session.overrideOutputAudioPort(.speaker)
            Log.audio.info("talk output was the receiver; overridden to the speaker")
        } catch {
            Log.audio.error("speaker override failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// The talk's microphone, and with it its output (`TalkRoute.plan`): the
    /// earbuds on HFP, else a wired headset, else a USB one, else the phone
    /// (speaker). Run when the talk session comes up and whenever a device
    /// comes or goes during it. nil clears the preference, so a headset that
    /// was unplugged leaves no stale choice behind.
    private func preferTalkInput() {
        guard route == .talk else { return }
        let inputs = session.availableInputs ?? []
        let ports = inputs.map { TalkRoute.Port(kind: Self.kind(of: $0.portType), uid: $0.uid) }
        let onSpeaker = session.currentRoute.outputs.contains { $0.portType == .builtInSpeaker }
        let plan = TalkRoute.plan(available: ports, outputOnSpeaker: onSpeaker)
        let port = plan.input.flatMap { chosen in inputs.first { $0.uid == chosen.uid } }
        do {
            try session.setPreferredInput(port)
        } catch {
            Log.audio.error("preferred input failed: \(error.localizedDescription, privacy: .public)")
        }
        if plan.clearSpeakerOverride {
            // The speaker override takes the mic with it (built-in speaker
            // *and* microphone): off, so the headset carries both ways.
            try? session.overrideOutputAudioPort(.none)
        }
        let kind = plan.input?.kind ?? .other
        let name = port?.portName ?? "phone default"
        Log.audio.info("talk input: \(kind.word, privacy: .public) (\(name, privacy: .public)), \(inputs.count) available")
    }

    private static func kind(of type: AVAudioSession.Port) -> TalkRoute.Kind {
        switch type {
        case .bluetoothHFP: return .bluetoothHFP
        case .headsetMic: return .headsetMic
        case .usbAudio: return .usbAudio
        default: return .other
        }
    }

    private func interruption(_ n: Notification) {
        guard let info = n.userInfo,
              let raw = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        switch type {
        case .began:
            Log.audio.info("interruption began")
            onInterruptionBegan?()
        case .ended:
            let optionsRaw = info[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let resume = AVAudioSession.InterruptionOptions(rawValue: optionsRaw).contains(.shouldResume)
            Log.audio.info("interruption ended, shouldResume=\(resume)")
            onInterruptionEnded?(resume)
        @unknown default:
            break
        }
    }

    private func routeChanged(_ n: Notification) {
        guard let raw = n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: raw) else { return }
        Log.audio.info("route change \(raw): out=\(self.outputName, privacy: .public) in=\(self.inputName, privacy: .public)")
        // A headset plugged in or unplugged mid-talk: the talk picks its
        // input again (a wired headset takes over from the phone's mic; gone,
        // the earbuds or the phone and the speaker), and never the earpiece.
        if route == .talk, TalkRoute.replans(reason: raw) {
            preferTalkInput()
            avoidReceiver()
        }
        onRouteChange?(reason)
    }
}
#endif
