#if os(iOS)
import AVFoundation
import Foundation

/// Which AVAudioSession configuration is active.
enum AudioRoute: String {
    /// `.playback`: AirPods on A2DP (stereo, full quality). Mic closed.
    case media
    /// `.playAndRecord` + `.voiceChat` + Bluetooth HFP: talk (voice processing),
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

    func activate(_ newRoute: AudioRoute) throws {
        switch newRoute {
        case .media, .listen:
            try session.setCategory(.playback, mode: .default, options: [])
        case .talk:
            // `.defaultToSpeaker`: with no headset the talk (and its earcons)
            // plays on the loudspeaker, not the quiet earpiece. A Bluetooth
            // HFP or wired headset still wins; voice processing cancels the
            // speaker's echo.
            try session.setCategory(.playAndRecord, mode: .voiceChat,
                                    options: [.allowBluetoothHFP, .defaultToSpeaker])
            try? session.setPreferredSampleRate(16_000)
            try? session.setPreferredIOBufferDuration(0.01)
        }
        try session.setActive(true)
        if newRoute == .talk { preferBluetoothInput() }
        route = newRoute
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
        session.currentRoute.outputs.contains { [.bluetoothA2DP, .bluetoothHFP, .bluetoothLE, .headphones].contains($0.portType) }
    }

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

    private func preferBluetoothInput() {
        let bt = session.availableInputs?.first { $0.portType == .bluetoothHFP }
        if let bt { try? session.setPreferredInput(bt) }
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
        // A headset dropped mid-talk: back to the speaker, not the earpiece.
        if reason == .oldDeviceUnavailable { avoidReceiver() }
        onRouteChange?(reason)
    }
}
#endif
