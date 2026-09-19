#if os(iOS)
import AVFoundation
import Foundation
import Speech

/// One voice command: records from the current input (AirPods mic over HFP,
/// after SessionController.activate(.command)), recognises on-device, and
/// completes with the text once the speaker pauses, the max length is hit, or
/// `finish()` is called. Main-queue API.
final class Transcriber {
    enum TranscriberError: LocalizedError {
        case unavailable(String)
        var errorDescription: String? {
            switch self { case .unavailable(let why): why }
        }
    }

    /// Silence after the last partial result that ends the utterance.
    var endOfSpeechSeconds: TimeInterval = 1.2

    private var engine: AVAudioEngine?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var completion: ((String?) -> Void)?
    private var lastText = ""
    private var silenceTimer: Timer?
    private var maxTimer: Timer?
    private var finalTimer: Timer?

    var isRunning: Bool { completion != nil }

    static func requestAuthorization(_ done: @escaping (Bool) -> Void) {
        SFSpeechRecognizer.requestAuthorization { status in
            DispatchQueue.main.async { done(status == .authorized) }
        }
    }

    func start(language: String, maxSeconds: TimeInterval, completion: @escaping (String?) -> Void) throws {
        cancel()
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: language)) else {
            throw TranscriberError.unavailable("No speech recognizer for \(language)")
        }
        guard recognizer.isAvailable else {
            throw TranscriberError.unavailable("Speech recognizer for \(language) is unavailable")
        }

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.taskHint = .search
        if recognizer.supportsOnDeviceRecognition {
            request.requiresOnDeviceRecognition = true
        } else {
            // No coverage on a ride means server recognition fails anyway.
            Log.voice.error("no on-device model for \(language, privacy: .public); using server recognition")
        }

        let engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0 else { throw TranscriberError.unavailable("No microphone input") }
        input.installTap(onBus: 0, bufferSize: 1_024, format: format) { buffer, _ in
            request.append(buffer)
        }
        engine.prepare()
        try engine.start()

        self.engine = engine
        self.request = request
        self.completion = completion
        lastText = ""

        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            let text = result?.bestTranscription.formattedString
            let isFinal = result?.isFinal ?? false
            DispatchQueue.main.async {
                guard let self, self.isRunning else { return }
                if let text, !text.isEmpty {
                    self.lastText = text
                    self.armSilenceTimer()
                }
                if isFinal || error != nil { self.complete() }
            }
        }

        maxTimer = Timer.scheduledTimer(withTimeInterval: maxSeconds, repeats: false) { [weak self] _ in
            self?.finish()
        }
        Log.voice.info("listening (\(language, privacy: .public), onDevice=\(request.requiresOnDeviceRecognition))")
    }

    /// Stop recording and wait (briefly) for the final result.
    func finish() {
        guard isRunning else { return }
        stopAudio()
        request?.endAudio()
        finalTimer?.invalidate()
        finalTimer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: false) { [weak self] _ in
            self?.complete()
        }
    }

    /// Abort without calling the completion.
    func cancel() {
        completion = nil
        cleanup()
    }

    // MARK: - Private

    private func armSilenceTimer() {
        silenceTimer?.invalidate()
        silenceTimer = Timer.scheduledTimer(withTimeInterval: endOfSpeechSeconds, repeats: false) { [weak self] _ in
            self?.finish()
        }
    }

    private func complete() {
        guard let completion else { return }
        self.completion = nil
        let text = lastText.trimmingCharacters(in: .whitespacesAndNewlines)
        cleanup()
        completion(text.isEmpty ? nil : text)
    }

    private func stopAudio() {
        if let engine {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
        }
        engine = nil
    }

    private func cleanup() {
        stopAudio()
        silenceTimer?.invalidate()
        maxTimer?.invalidate()
        finalTimer?.invalidate()
        silenceTimer = nil
        maxTimer = nil
        finalTimer = nil
        task?.cancel()
        task = nil
        request = nil
    }
}
#endif
