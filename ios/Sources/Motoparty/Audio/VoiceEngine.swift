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
/// 320-sample frames → Opus → `sendAudio` (or `skipFrame` for DTX).
/// Playback: AVAudioSourceNode (16 kHz mono) pulls 20 ms frames from the
/// JitterBuffer and decodes them with Opus in the render callback.
///
/// A new AVAudioEngine is built for every talk so voice-processing state never
/// leaks into music mode. Call `start` after SessionController.activate(.talk).
final class VoiceEngine {
    enum EngineError: Error { case noInput, format }

    /// Called on the main queue if the engine had to be restarted or died.
    var onFailure: ((Error) -> Void)?

    private var engine: AVAudioEngine?
    private var configObserver: NSObjectProtocol?
    private var sendAudio: ((Data) -> Void)?
    private var skipFrame: (() -> Void)?

    private let format16k = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000,
                                          channels: 1, interleaved: false)!

    // Capture side, touched only on captureQueue.
    private let captureQueue = DispatchQueue(label: "motoparty.capture", qos: .userInteractive)
    private var converter: AVAudioConverter?
    private var captureMonoFormat: AVAudioFormat?
    private var fifo: [Float] = []
    private var encoder: OpusVoiceEncoder?

    // Receive side, shared by the network queue and the render thread.
    private struct Rx {
        var running = false
        var jitter = JitterBuffer()
        var decoder: OpusVoiceDecoder?
        var pcm: [Float] = []
        var readIndex = 0
    }
    private let rx = OSAllocatedUnfairLock(uncheckedState: Rx())

    var isRunning: Bool { engine?.isRunning ?? false }

    func start(sendAudio: @escaping (Data) -> Void, skipFrame: @escaping () -> Void) throws {
        stop()
        self.sendAudio = sendAudio
        self.skipFrame = skipFrame

        let engine = AVAudioEngine()
        let input = engine.inputNode
        try input.setVoiceProcessingEnabled(true)
        let inFormat = input.outputFormat(forBus: 0)
        guard inFormat.sampleRate > 0, inFormat.channelCount > 0 else { throw EngineError.noInput }
        guard inFormat.commonFormat == .pcmFormatFloat32,
              let mono = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: inFormat.sampleRate,
                                       channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: mono, to: format16k) else { throw EngineError.format }

        let decoder = try OpusVoiceDecoder()
        let encoder = try OpusVoiceEncoder()
        captureQueue.sync {
            self.converter = converter
            self.captureMonoFormat = mono
            self.encoder = encoder
            self.fifo.removeAll()
        }
        rx.withLockUnchecked { $0 = Rx(running: true, jitter: JitterBuffer(), decoder: decoder) }

        let interleaved = inFormat.isInterleaved
        let channels = Int(inFormat.channelCount)
        let sink = AVAudioSinkNode { [weak self] _, frameCount, audioBufferList in
            guard let self else { return noErr }
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
        engine.connect(input, to: sink, format: inFormat)

        let source = AVAudioSourceNode(format: format16k) { [weak self] _, _, frameCount, audioBufferList in
            self?.render(frameCount: Int(frameCount), into: audioBufferList)
            return noErr
        }
        engine.attach(source)
        engine.connect(source, to: engine.mainMixerNode, format: format16k)

        configObserver = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main
        ) { [weak self] _ in self?.restartAfterConfigurationChange() }

        engine.prepare()
        try engine.start()
        self.engine = engine
        Log.audio.info("voice engine started: in \(inFormat.sampleRate) Hz × \(channels)")
    }

    func stop() {
        if let configObserver { NotificationCenter.default.removeObserver(configObserver) }
        configObserver = nil
        rx.withLockUnchecked { $0.running = false; $0.pcm.removeAll(); $0.readIndex = 0 }
        if let engine {
            engine.stop()
            try? engine.inputNode.setVoiceProcessingEnabled(false)
        }
        engine = nil
        captureQueue.async { self.fifo.removeAll() }
    }

    /// Network queue → jitter buffer.
    func receive(_ packet: VoicePacket) {
        let now = MonotonicClock.nowMs()
        rx.withLockUnchecked { state in
            guard state.running else { return }
            state.jitter.insert(packet, nowMs: now)
        }
    }

    var jitterStats: (targetMs: Int, stats: JitterBuffer.Stats) {
        rx.withLockUnchecked { ($0.jitter.targetMs, $0.jitter.stats) }
    }

    // MARK: - Capture (captureQueue)

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
        fifo.append(contentsOf: UnsafeBufferPointer(start: data[0], count: Int(out.frameLength)))

        while fifo.count >= VoiceFormat.frameSamples {
            let frame = Array(fifo[0..<VoiceFormat.frameSamples])
            fifo.removeFirst(VoiceFormat.frameSamples)
            guard let encoded = try? encoder.encode(frame) else { continue }
            if encoded.isDTX { skipFrame?() } else { sendAudio?(encoded.data) }
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
            for i in 0..<n { out[i] = state.pcm[state.readIndex + i] }
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

    private func restartAfterConfigurationChange() {
        guard engine != nil, let sendAudio, let skipFrame else { return }
        Log.audio.info("voice engine configuration changed; restarting")
        do {
            try start(sendAudio: sendAudio, skipFrame: skipFrame)
        } catch {
            Log.audio.error("voice engine restart failed: \(error.localizedDescription, privacy: .public)")
            stop()
            onFailure?(error)
        }
    }
}
#endif
