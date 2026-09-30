#if os(iOS)
import AVFoundation
import Foundation
import MotopartyCore
import os

/// Full-duplex talk audio: AVAudioEngine with voice processing (echo
/// cancellation, AGC, noise suppression) on the mic.
///
/// Capture: inputNode → AVAudioSinkNode (render-cycle sized buffers, unlike a
/// tap's 100 ms minimum) → channel 0 → capture queue → resample to 16 kHz →
/// 320-sample frames → Opus → `sendAudio` (or `skipFrame` for DTX). Each
/// 16 kHz buffer also goes to `tee` (speech recognition inside the talk)
/// before the encoder, so DTX never cuts what the recogniser hears.
/// Playback: AVAudioSourceNode (16 kHz mono) pulls 20 ms frames from the
/// JitterBuffer and decodes them with Opus in the render callback, then
/// Apple's peak limiter (AUPeakLimiter) → main mixer. The app volume
/// (`AppVolume`, see `setGain`) is the mixer's output volume below unity and
/// the limiter's pre-gain above it, so the top level's boost never clips.
///
/// Receive only (`startReceiveOnly`, a host-mic talk, PROTOCOL.md "Host-mic
/// talk"): the same playback chain and nothing else. The engine never touches
/// `inputNode` (so no input unit, no voice processing, no microphone and no
/// permission), and it runs in the media session (`.listen`). `onLive` then
/// fires on the first render callback: the output is pulling.
///
/// A new AVAudioEngine is built for every talk so voice-processing state never
/// leaks into music mode. Call `start` after SessionController.activate(.talk),
/// `startReceiveOnly` after activate(.listen).
/// Within a talk, a configuration change restarts that same engine (see
/// `configurationChanged`); the jitter buffer and decoder survive it.
final class VoiceEngine {
    enum EngineError: Error { case noInput, format, restartLoop }
    enum Mode: String {
        /// Mic capture + send + playback (own-mic talk).
        case duplex
        /// Playback only (host-mic talk).
        case receiveOnly = "receive only"
    }

    /// Called on the main queue if the engine had to be restarted or died.
    var onFailure: ((Error) -> Void)?
    /// The talk is live: in `duplex`, the capture sink delivered its first
    /// buffer of this `start` (the microphone is really delivering, which is
    /// what the "live" earcon means, Android F7/F9a `LiveCue`); in
    /// `receiveOnly`, the playback source was first pulled for output. Called
    /// on the main queue, exactly once per start and re-armed by the next one.
    var onLive: (() -> Void)?

    /// The mode of the current (or last) talk. Main queue.
    private(set) var mode: Mode = .duplex

    private var engine: AVAudioEngine?
    private var configObserver: NSObjectProtocol?
    private var sendAudio: ((Data) -> Void)?
    private var skipFrame: (() -> Void)?
    private var tee: ((AVAudioPCMBuffer) -> Void)?
    private var limiter: AVAudioUnitEffect?
    private var sink: AVAudioSinkNode?
    /// The input format the capture sink is connected with (duplex only).
    private var inputFormat: AVAudioFormat?
    /// The output node's format at the last (re)start (receive only).
    private var outputFormat: AVAudioFormat?
    /// This engine touched `inputNode` (voice processing to undo at teardown).
    private var usesInput = false

    // Configuration-change restarts (main queue).
    private var startedAtMs: Double = 0
    private var restartCount = 0
    private var recentRestartsMs: [Double] = []
    private var pendingRestart: DispatchWorkItem?
    private var rebuiltAfterFailure = false
    /// The app volume's talk gains; kept across restarts (main queue).
    private var outputVolume: Float = 1
    private var boostDb: Float = 0

    private let format16k = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000,
                                          channels: 1, interleaved: false)!

    // Capture side, touched only on captureQueue.
    private let captureQueue = DispatchQueue(label: "motoparty.capture", qos: .userInteractive)
    private var converter: AVAudioConverter?
    private var captureMonoFormat: AVAudioFormat?
    private var fifo: [Float] = []
    private var encoder: OpusVoiceEncoder?
    private var captureTee: ((AVAudioPCMBuffer) -> Void)?
    /// This talk's capture numbers (captureQueue only), for `talk stats:`.
    private var tx = TxStats()

    /// Per-talk capture counters. `peaks` is preallocated once: the capture
    /// queue only writes into it.
    private struct TxStats {
        var talkStartMs: Double = 0
        var captured = 0
        var sent = 0
        var dtx = 0
        var firstSendLogged = false
        /// Loudest capture sample of the talk, Int16 scale.
        var peak = 0
        /// `mic trace:` — loudest sample per 100 ms of 16 kHz capture.
        var peaks = [Int](repeating: 0, count: VoiceEngine.traceBuckets)
        var traceSamples = 0
        var traceLogged = false
    }
    /// `mic trace:` covers the first `traceBuckets` × 100 ms = 4 s of capture.
    fileprivate static let traceBuckets = 40
    private static let traceBucketSamples = VoicePacket.sampleRate / 10

    // Receive side, shared by the network queue and the render thread.
    private struct Rx {
        var running = false
        var jitter = JitterBuffer()
        var decoder: OpusVoiceDecoder?
        var pcm: [Float] = []
        var readIndex = 0
        /// Talk start (`start`), for `voice rx: first packet`; nil = no talk.
        var talkStartMs: Double?
        var firstRxLogged = false
        /// Loudest decoded sample played, before the mixer and limiter (0...1).
        var peakOut: Float = 0
    }
    private let rx = OSAllocatedUnfairLock(uncheckedState: Rx())

    /// False until the running start is live (sink buffer or first render).
    /// Read and set on the audio thread, cleared on the main queue by `build`.
    private let liveUp = OSAllocatedUnfairLock(initialState: false)

    var isRunning: Bool { engine?.isRunning ?? false }

    /// `tee` gets every 16 kHz mono capture buffer on the capture queue. It
    /// must return at once (hand the buffer off, never wait): it runs in line
    /// with the encoder.
    func start(sendAudio: @escaping (Data) -> Void, skipFrame: @escaping () -> Void,
               tee: ((AVAudioPCMBuffer) -> Void)? = nil) throws {
        try begin(.duplex, sendAudio: sendAudio, skipFrame: skipFrame, tee: tee)
    }

    /// A host-mic talk: play received voice, capture nothing, send nothing
    /// (the voice socket's keepalives go on by themselves).
    func startReceiveOnly() throws {
        try begin(.receiveOnly, sendAudio: nil, skipFrame: nil, tee: nil)
    }

    private func begin(_ mode: Mode, sendAudio: ((Data) -> Void)?, skipFrame: (() -> Void)?,
                       tee: ((AVAudioPCMBuffer) -> Void)?) throws {
        stop()
        self.mode = mode
        let now = MonotonicClock.nowMs()
        captureQueue.sync {
            tx.talkStartMs = now
            tx.captured = 0; tx.sent = 0; tx.dtx = 0; tx.peak = 0
            tx.firstSendLogged = false
            for i in tx.peaks.indices { tx.peaks[i] = 0 }
            tx.traceSamples = 0
            tx.traceLogged = false
        }
        rx.withLockUnchecked { $0.talkStartMs = now; $0.firstRxLogged = false; $0.peakOut = 0 }
        restartCount = 0
        recentRestartsMs.removeAll()
        rebuiltAfterFailure = false
        self.sendAudio = sendAudio
        self.skipFrame = skipFrame
        self.tee = tee
        try build()
    }

    /// A fresh engine with voice processing on and the whole graph. Only
    /// `start` (a new talk) and, once per talk, a restart that could not bring
    /// the existing engine back call this: toggling voice processing
    /// reconfigures the I/O unit, which itself posts
    /// `AVAudioEngineConfigurationChange` (on the first device run, restarting
    /// through here on every change was an endless loop, ~0.7 s per lap).
    private func build() throws {
        tearDown()
        let engine = AVAudioEngine()
        // Receive only never names `inputNode`: that alone would create the
        // input unit, which the media session has no input for.
        var inFormat: AVAudioFormat?
        if mode == .duplex {
            let input = engine.inputNode
            usesInput = true
            try input.setVoiceProcessingEnabled(true)
            // Voice processing ducks all other audio by default (iOS 17+),
            // and that includes this app's own earcons and announcements.
            input.voiceProcessingOtherAudioDuckingConfiguration =
                .init(enableAdvancedDucking: false, duckingLevel: .min)
            inFormat = input.outputFormat(forBus: 0)
        }

        let decoder = try OpusVoiceDecoder()
        let encoder = mode == .duplex ? try OpusVoiceEncoder() : nil
        captureQueue.sync { self.encoder = encoder }
        rx.withLockUnchecked {
            $0 = Rx(running: true, jitter: JitterBuffer(), decoder: decoder,
                    talkStartMs: $0.talkStartMs, firstRxLogged: $0.firstRxLogged, peakOut: $0.peakOut)
        }
        liveUp.withLock { $0 = false }

        if let inFormat { try connectCapture(engine: engine, inFormat: inFormat) }

        let liveOnRender = mode == .receiveOnly
        let source = AVAudioSourceNode(format: format16k) { [weak self] _, _, frameCount, audioBufferList in
            guard let self else { return noErr }
            if liveOnRender { self.noteLive() }
            self.render(frameCount: Int(frameCount), into: audioBufferList)
            return noErr
        }
        engine.attach(source)
        let limiter = AVAudioUnitEffect(audioComponentDescription: AudioComponentDescription(
            componentType: kAudioUnitType_Effect, componentSubType: kAudioUnitSubType_PeakLimiter,
            componentManufacturer: kAudioUnitManufacturer_Apple, componentFlags: 0, componentFlagsMask: 0))
        engine.attach(limiter)
        engine.connect(source, to: limiter, format: format16k)
        engine.connect(limiter, to: engine.mainMixerNode, format: format16k)
        self.limiter = limiter
        applyGain(engine: engine)

        configObserver = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main
        ) { [weak self] _ in self?.configurationChanged() }

        self.engine = engine
        engine.prepare()
        do {
            try engine.start()
        } catch {
            tearDown()
            throw error
        }
        startedAtMs = MonotonicClock.nowMs()
        let out = engine.outputNode.outputFormat(forBus: 0)
        outputFormat = out
        if let inFormat {
            Log.audio.info("voice engine started: in \(inFormat.sampleRate) Hz × \(inFormat.channelCount), out \(out.sampleRate) Hz × \(out.channelCount), \(self.gainDescription, privacy: .public)")
        } else {
            Log.audio.info("voice engine started: receive only (no input), out \(out.sampleRate) Hz × \(out.channelCount), \(self.gainDescription, privacy: .public)")
        }
    }

    /// (Re)connects inputNode → a new capture sink for `inFormat` and the
    /// converter behind it. The engine must be stopped. The encoder, the
    /// receive side and voice processing are left alone.
    private func connectCapture(engine: AVAudioEngine, inFormat: AVAudioFormat) throws {
        guard inFormat.sampleRate > 0, inFormat.channelCount > 0 else { throw EngineError.noInput }
        guard inFormat.commonFormat == .pcmFormatFloat32,
              let mono = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: inFormat.sampleRate,
                                       channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: mono, to: format16k) else { throw EngineError.format }
        let tee = self.tee
        captureQueue.sync {
            self.converter = converter
            self.captureMonoFormat = mono
            self.captureTee = tee
            self.fifo.removeAll()
        }

        if let old = sink {
            engine.disconnectNodeOutput(engine.inputNode)
            engine.detach(old)
            sink = nil
        }
        let interleaved = inFormat.isInterleaved
        let channels = Int(inFormat.channelCount)
        let sink = AVAudioSinkNode { [weak self] _, frameCount, audioBufferList in
            guard let self else { return noErr }
            self.noteLive()
            let buffers = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: audioBufferList))
            guard let first = buffers.first, let raw = first.mData else { return noErr }
            let n = Int(frameCount)
            let src = raw.assumingMemoryBound(to: Float.self)
            let step = interleaved ? channels : 1
            var samples = [Float](repeating: 0, count: n)
            for i in 0..<n { samples[i] = src[i * step] }
            self.captureQueue.async { self.process(samples) }
            return noErr
        }
        engine.attach(sink)
        engine.connect(engine.inputNode, to: sink, format: inFormat)
        self.sink = sink
        inputFormat = inFormat
    }

    /// End of talk: stops the engine and turns voice processing off, so
    /// nothing of it leaks into music mode.
    func stop() {
        pendingRestart?.cancel()
        pendingRestart = nil
        logTalkStats()
        tearDown()
    }

    /// One line per talk at its end, mirroring Android's `TalkStats.line()`
    /// plus the routes and peaks (Int16 scale). Main queue; nothing if no
    /// talk was started since the last one.
    private func logTalkStats() {
        let (started, target, stats, peakOut) = rx.withLockUnchecked { state -> (Double?, Int, JitterBuffer.Stats, Float) in
            let r = (state.talkStartMs, state.jitter.targetMs, state.jitter.stats, state.peakOut)
            state.talkStartMs = nil
            return r
        }
        guard started != nil else { return }
        let t = captureQueue.sync { tx }
        let duplex = mode == .duplex
        let route = AVAudioSession.sharedInstance().currentRoute
        let out = route.outputs.map { "\($0.portType.rawValue)/\($0.portName)" }.joined(separator: "+")
        let inp = route.inputs.map { "\($0.portType.rawValue)/\($0.portName)" }.joined(separator: "+")
        let peakOutI16 = Int((min(1, peakOut) * 32_767).rounded())
        Log.audio.info("talk stats: \(self.mode.rawValue, privacy: .public), tx \(t.sent, privacy: .public) sent of \(t.captured, privacy: .public) captured (\(t.dtx, privacy: .public) DTX), rx \(stats.received, privacy: .public) received, \(stats.played, privacy: .public) played, \(stats.lost, privacy: .public) lost, \(stats.late, privacy: .public) late, \(stats.fec, privacy: .public) FEC, \(stats.concealed, privacy: .public) PLC, \(stats.shed, privacy: .public) shed, jitter target \(target, privacy: .public) ms, depth mean \(stats.meanDepthMs, privacy: .public) max \(stats.maxDepthMs, privacy: .public) ms, out \(out.isEmpty ? "none" : out, privacy: .public), in \(inp.isEmpty ? "none" : inp, privacy: .public), peak in \(t.peak, privacy: .public), peak out \(peakOutI16, privacy: .public), \(self.gainDescription, privacy: .public)")
        // No mic, no trace (a host-mic talk captures nothing here).
        if duplex, !t.traceLogged { logMicTrace(t) }
    }

    /// `mic trace: peaks/100ms: …` — the loudest capture sample (Int16 scale)
    /// per 100 ms over the first 4 s, or what there was of it at talk end.
    private func logMicTrace(_ t: TxStats) {
        let filled = min(Self.traceBuckets, (t.traceSamples + Self.traceBucketSamples - 1) / Self.traceBucketSamples)
        let peaks = t.peaks.prefix(filled).map(String.init).joined(separator: " ")
        Log.audio.info("mic trace: peaks/100ms: \(peaks, privacy: .public)")
    }

    private func tearDown() {
        if let configObserver { NotificationCenter.default.removeObserver(configObserver) }
        configObserver = nil
        rx.withLockUnchecked { $0.running = false; $0.pcm.removeAll(); $0.readIndex = 0 }
        if let engine {
            engine.stop()
            if usesInput { try? engine.inputNode.setVoiceProcessingEnabled(false) }
        }
        engine = nil
        usesInput = false
        limiter = nil
        sink = nil
        inputFormat = nil
        outputFormat = nil
        captureQueue.async { self.fifo.removeAll(); self.captureTee = nil }
    }

    /// The app volume for talk playback (`AppVolume.Gains`): `volume` 0...1 on
    /// the main mixer, `boostDb` >= 0 as the limiter's pre-gain. Kept for the
    /// next `start` too. Main queue.
    func setGain(volume: Float, boostDb: Float) {
        outputVolume = min(1, max(0, volume))
        self.boostDb = max(0, boostDb)
        if let engine { applyGain(engine: engine) }
    }

    /// `app gain -33 dB (mixer 0.022), boost 0 dB`: the app volume talk plays
    /// at, for the log. `peak out` is measured before it, so a quiet talk
    /// with a healthy `peak out` is this gain.
    private var gainDescription: String {
        let db = outputVolume > 0 ? String(format: "%.0f dB", 20 * log10(outputVolume)) : "muted"
        return "app gain \(db) (mixer \(String(format: "%.3f", outputVolume))), boost \(String(format: "%.0f", boostDb)) dB"
    }

    private func applyGain(engine: AVAudioEngine) {
        engine.mainMixerNode.outputVolume = outputVolume
        guard let limiter else { return }
        // Parameters are safe to set while the unit renders.
        let status = AudioUnitSetParameter(limiter.audioUnit, kLimiterParam_PreGain, kAudioUnitScope_Global,
                                           0, boostDb, 0)
        if status != noErr { Log.audio.error("limiter pre-gain: \(status)") }
    }

    /// Network queue → jitter buffer.
    func receive(_ packet: VoicePacket) {
        let now = MonotonicClock.nowMs()
        let firstAfterMs = rx.withLockUnchecked { state -> Double? in
            guard state.running else { return nil }
            state.jitter.insert(packet, nowMs: now)
            guard packet.kind == .audio, !state.firstRxLogged, let start = state.talkStartMs else { return nil }
            state.firstRxLogged = true
            return now - start
        }
        if let firstAfterMs {
            Log.audio.info("voice rx: first packet +\(Int(firstAfterMs.rounded()), privacy: .public) ms after start")
        }
    }

    var jitterStats: (targetMs: Int, stats: JitterBuffer.Stats) {
        rx.withLockUnchecked { ($0.jitter.targetMs, $0.jitter.stats) }
    }

    // MARK: - Capture (sink thread, then captureQueue)

    /// First buffer of this start, on the audio thread: the capture sink's
    /// (the microphone is delivering) or, receive only, the source's first
    /// render (the output is pulling). Must never block that thread, so it only
    /// flips a flag and hops to the main queue — `onLive` is read and run
    /// there, like `onFailure` (Android does the same in `VoiceEngine.onCaptureUp`).
    private func noteLive() {
        let first = liveUp.withLock { up -> Bool in
            if up { return false }
            up = true
            return true
        }
        guard first else { return }
        DispatchQueue.main.async { [weak self] in self?.onLive?() }
    }

    private func process(_ samples: [Float]) {
        guard let converter, let mono = captureMonoFormat, let encoder else { return }
        let n = samples.count
        guard n > 0, let inBuffer = AVAudioPCMBuffer(pcmFormat: mono, frameCapacity: AVAudioFrameCount(n)) else { return }
        inBuffer.frameLength = AVAudioFrameCount(n)
        samples.withUnsafeBufferPointer { src in
            inBuffer.floatChannelData![0].update(from: src.baseAddress!, count: n)
        }
        let capacity = AVAudioFrameCount(Double(n) * 16_000 / mono.sampleRate) + 64
        guard let out = AVAudioPCMBuffer(pcmFormat: format16k, frameCapacity: capacity) else { return }
        var supplied = false
        var error: NSError?
        let status = converter.convert(to: out, error: &error) { _, outStatus in
            if supplied {
                outStatus.pointee = .noDataNow
                return nil
            }
            supplied = true
            outStatus.pointee = .haveData
            return inBuffer
        }
        guard status != .error, let data = out.floatChannelData else { return }
        // A fresh buffer every call, so the recogniser may keep it.
        if out.frameLength > 0 { captureTee?(out) }
        let converted = UnsafeBufferPointer(start: data[0], count: Int(out.frameLength))
        notePeaks(converted)
        fifo.append(contentsOf: converted)

        while fifo.count >= VoiceFormat.frameSamples {
            let frame = Array(fifo[0..<VoiceFormat.frameSamples])
            fifo.removeFirst(VoiceFormat.frameSamples)
            guard let encoded = try? encoder.encode(frame) else { continue }
            tx.captured += 1
            if encoded.isDTX {
                tx.dtx += 1
                skipFrame?()
            } else {
                tx.sent += 1
                if !tx.firstSendLogged {
                    tx.firstSendLogged = true
                    let ms = Int((MonotonicClock.nowMs() - tx.talkStartMs).rounded())
                    Log.audio.info("voice tx: first packet +\(ms, privacy: .public) ms")
                }
                sendAudio?(encoded.data)
            }
        }
    }

    /// Talk peak and the first 4 s of `mic trace:` peaks, Int16 scale
    /// (captureQueue). Logs the trace once its 40 buckets are full.
    private func notePeaks(_ samples: UnsafeBufferPointer<Float>) {
        for sample in samples {
            let level = Int((min(1, abs(sample)) * 32_767).rounded())
            if level > tx.peak { tx.peak = level }
            if !tx.traceLogged {
                let bucket = tx.traceSamples / Self.traceBucketSamples
                if bucket < Self.traceBuckets {
                    if level > tx.peaks[bucket] { tx.peaks[bucket] = level }
                    tx.traceSamples += 1
                }
            }
        }
        if !tx.traceLogged, tx.traceSamples >= Self.traceBuckets * Self.traceBucketSamples {
            tx.traceLogged = true
            logMicTrace(tx)
        }
    }

    // MARK: - Playback (render thread)

    private func render(frameCount n: Int, into audioBufferList: UnsafeMutablePointer<AudioBufferList>) {
        let buffers = UnsafeMutableAudioBufferListPointer(audioBufferList)
        guard let first = buffers.first, let raw = first.mData else { return }
        let out = raw.assumingMemoryBound(to: Float.self)
        let now = MonotonicClock.nowMs()
        rx.withLockUnchecked { state in
            guard state.running, let decoder = state.decoder else {
                for i in 0..<n { out[i] = 0 }
                return
            }
            while state.pcm.count - state.readIndex < n {
                let action = state.jitter.pull(nowMs: now)
                state.pcm.append(contentsOf: decoder.render(action))
            }
            var peak = state.peakOut
            for i in 0..<n {
                let v = state.pcm[state.readIndex + i]
                out[i] = v
                peak = max(peak, abs(v))
            }
            state.peakOut = peak
            state.readIndex += n
            if state.readIndex >= 4_096 {
                state.pcm.removeFirst(state.readIndex)
                state.readIndex = 0
            }
        }
        // Mono format: one buffer. Copy into any extra buffers just in case.
        for extra in buffers.dropFirst() {
            if let d = extra.mData { memcpy(d, raw, min(Int(extra.mDataByteSize), n * 4)) }
        }
    }

    // MARK: - Route changes

    /// A configuration change within this long of our own (re)start, with the
    /// engine still running, is the echo of that start (voice processing and
    /// the session settling), not something to act on.
    private static let ownStartGraceMs: Double = 1_000
    /// At most `maxRestarts` restarts within `restartWindowMs`; one more and
    /// the talk's mic is given up (`onFailure`) rather than looping.
    private static let restartWindowMs: Double = 10_000
    private static let maxRestarts = 6

    /// `AVAudioEngineConfigurationChange` (main queue): the route, sample rate
    /// or channel count under the engine changed, and the engine has usually
    /// stopped itself. Apple's answer is to start the *same* engine again,
    /// reconnecting only what the change actually broke — never to rebuild it
    /// (that toggles voice processing, which posts this notification again).
    ///
    /// Receive only has no voice processing to echo and no input to lose, so
    /// a change with the engine still running is always ignored: the main
    /// mixer converts to whatever the output became. A stopped engine is
    /// restarted in place under the same backoff and cap (a headset leaving
    /// or arriving stops it); the cap still ends a loop instead of spinning.
    private func configurationChanged() {
        guard let engine else { return }
        let now = MonotonicClock.nowMs()
        let sinceStart = Int((now - startedAtMs).rounded())
        if engine.isRunning {
            if mode == .receiveOnly || now - startedAtMs < Self.ownStartGraceMs
                || sameFormat(engine.inputNode.outputFormat(forBus: 0), inputFormat) {
                Log.audio.info("voice engine configuration changed +\(sinceStart) ms after start, still running: ignored")
                return
            }
        }
        guard pendingRestart == nil else {
            Log.audio.info("voice engine configuration changed again; restart already pending")
            return
        }
        recentRestartsMs.removeAll { now - $0 > Self.restartWindowMs }
        if recentRestartsMs.count >= Self.maxRestarts {
            Log.audio.error("voice engine: \(self.recentRestartsMs.count) restarts in \(Int(Self.restartWindowMs / 1000)) s; giving up")
            stop()
            onFailure?(EngineError.restartLoop)
            return
        }
        // Back off: at once for the first, then 100, 200, 400 … ms (cap 2 s),
        // so the session can settle before we ask anything of it again.
        let n = recentRestartsMs.count
        let delay = n == 0 ? 0 : min(2.0, 0.05 * pow(2, Double(n)))
        let work = DispatchWorkItem { [weak self] in self?.restartInPlace() }
        pendingRestart = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func restartInPlace() {
        pendingRestart = nil
        guard let engine else { return }
        let now = MonotonicClock.nowMs()
        restartCount += 1
        recentRestartsMs.append(now)
        let duplex = mode == .duplex
        // Receive only: never `inputNode` (it would create an input unit).
        let format = duplex ? engine.inputNode.outputFormat(forBus: 0) : engine.outputNode.outputFormat(forBus: 0)
        let formatChanged = !sameFormat(format, duplex ? inputFormat : outputFormat)
        let side = duplex ? "in" : "out"
        Log.audio.info("voice engine configuration changed; restart #\(self.restartCount) (\(self.recentRestartsMs.count) in \(Int(Self.restartWindowMs / 1000)) s), \(side, privacy: .public) \(format.sampleRate) Hz × \(format.channelCount)\(formatChanged ? ", \(side) format changed" : "", privacy: .public)")
        do {
            if engine.isRunning { engine.stop() }
            if formatChanged {
                if duplex {
                    try connectCapture(engine: engine, inFormat: format)
                } else {
                    // The mixer → output link takes the hardware's new format.
                    engine.connect(engine.mainMixerNode, to: engine.outputNode, format: nil)
                    outputFormat = format
                }
            }
            engine.prepare()
            try engine.start()
            startedAtMs = MonotonicClock.nowMs()
            Log.audio.info("voice engine restarted in place")
        } catch {
            Log.audio.error("voice engine in-place restart failed: \(error.localizedDescription, privacy: .public)")
            // Once per talk: a whole new engine (voice processing toggles, so
            // a change may follow; the restart cap above still holds).
            guard !rebuiltAfterFailure else {
                stop()
                onFailure?(error)
                return
            }
            rebuiltAfterFailure = true
            do {
                try build()
                Log.audio.info("voice engine rebuilt after a failed restart")
            } catch {
                Log.audio.error("voice engine restart failed: \(error.localizedDescription, privacy: .public)")
                stop()
                onFailure?(error)
            }
        }
    }

    private func sameFormat(_ a: AVAudioFormat, _ b: AVAudioFormat?) -> Bool {
        guard let b else { return false }
        return a.sampleRate == b.sampleRate && a.channelCount == b.channelCount
            && a.commonFormat == b.commonFormat && a.isInterleaved == b.isInterleaved
    }
}
#endif
