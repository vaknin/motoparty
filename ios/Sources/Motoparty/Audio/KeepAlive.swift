#if os(iOS)
import AVFoundation
import Foundation

/// Plays digital silence through its own AVAudioEngine whenever nothing else
/// is running, so iOS (UIBackgroundModes: audio) never suspends the app while
/// the screen is locked and music is paused. Stopped during talk, when the
/// voice engine is the active output.
final class KeepAlive {
    private var engine = AVAudioEngine()
    private(set) var isRunning = false
    private var observer: NSObjectProtocol?

    init() {
        build()
    }

    func start() {
        guard !isRunning else { return }
        do {
            engine.prepare()
            try engine.start()
            isRunning = true
        } catch {
            Log.audio.error("keepalive start failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    func stop() {
        guard isRunning else { return }
        engine.stop()
        isRunning = false
    }

    /// After a media-services reset every engine is dead; make a new one.
    func rebuild() {
        let wasRunning = isRunning
        stop()
        build()
        if wasRunning { start() }
    }

    private func build() {
        if let observer { NotificationCenter.default.removeObserver(observer) }
        let engine = AVAudioEngine()
        let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1)!
        let source = AVAudioSourceNode(format: format) { _, _, _, audioBufferList in
            for buffer in UnsafeMutableAudioBufferListPointer(audioBufferList) {
                if let data = buffer.mData { memset(data, 0, Int(buffer.mDataByteSize)) }
            }
            return noErr
        }
        engine.attach(source)
        engine.connect(source, to: engine.mainMixerNode, format: format)
        self.engine = engine
        // Category / route changes stop the engine; restart it if it should run.
        observer = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange,
                                                          object: engine, queue: .main) { [weak self] _ in
            guard let self, self.isRunning else { return }
            self.isRunning = false
            self.start()
        }
    }
}
#endif
