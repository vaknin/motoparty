#if os(iOS)
import AVFoundation
import Foundation
import os
import Speech

/// Speech recognition inside a talk (PROTOCOL.md "Commands"). It opens no
/// audio of its own: the talk's capture (`VoiceEngine`) tees its 16 kHz mic
/// buffers, before the Opus encoder so DTX never cuts them, into `append`.
///
/// Recognition runs as a chain of short requests, one per phrase: when a
/// result is final, or no new partial text has arrived for
/// `endOfPhraseSeconds`, that request ends, its text goes to `onPhrase`, and
/// the next request starts at once, for as long as the talk is open.
///
/// Recognition failing must never touch the talk: every error is logged and
/// the chain restarts (backing off while it keeps failing). Main-queue API,
/// except `append`, which any thread may call and which never blocks.
final class Transcriber {
    /// One recognised phrase (trimmed, non-empty). Called on the main queue.
    var onPhrase: ((String) -> Void)?

    /// No new partial text for this long ends the phrase.
    var endOfPhraseSeconds: TimeInterval = 1.2

    /// Every request's audio (append, endAudio) happens on this queue, in
    /// order, so a buffer can never land in a request that has already ended.
    private let feedQueue = DispatchQueue(label: "motoparty.recognition", qos: .userInitiated)
    /// The request buffers go to; touched only on `feedQueue`.
    private var feed: SFSpeechAudioBufferRecognitionRequest?
    /// Buffers queued on `feedQueue` but not yet appended. Past the cap new
    /// ones are dropped, so a stalled recogniser costs words, never memory or
    /// capture time.
    private let backlog = OSAllocatedUnfairLock(initialState: 0)
    private static let maxBacklog = 50

    // Main queue.
    private var recognizer: SFSpeechRecognizer?
    private var task: SFSpeechRecognitionTask?
    /// Which request the task callbacks belong to; results of an ended one are stale.
    private var segment = 0
    private var segmentStartedAt = Date.distantPast
    private var lastText = ""
    private var phraseTimer: Timer?
    private var restartTimer: Timer?
    private var failures = 0

    var isRunning: Bool { recognizer != nil }

    static func requestAuthorization(_ done: @escaping (Bool) -> Void) {
        SFSpeechRecognizer.requestAuthorization { status in
            DispatchQueue.main.async { done(status == .authorized) }
        }
    }

    /// Starts recognising for one talk. Never throws: without a recogniser
    /// the talk simply has no commands.
    func start(language: String) {
        stop()
        guard SFSpeechRecognizer.authorizationStatus() == .authorized else {
            Log.voice.error("speech recognition not authorised: no commands in this talk")
            return
        }
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: language)) else {
            Log.voice.error("no speech recognizer for \(language, privacy: .public): no commands in this talk")
            return
        }
        if !recognizer.supportsOnDeviceRecognition {
            // No coverage on a ride means server recognition fails anyway.
            Log.voice.error("no on-device model for \(language, privacy: .public); using server recognition")
        }
        self.recognizer = recognizer
        failures = 0
        Log.voice.info("recognising in talk (\(language, privacy: .public), onDevice=\(recognizer.supportsOnDeviceRecognition))")
        beginSegment()
    }

    /// The talk closed: drop whatever phrase is in progress.
    func stop() {
        guard recognizer != nil else { return }
        recognizer = nil
        endSegment()
        Log.voice.info("recognition stopped")
    }

    /// One 16 kHz mono buffer of the talk's mic, from the capture queue. Only
    /// counts and hops: never waits for the recogniser.
    func append(_ buffer: AVAudioPCMBuffer) {
        let admitted = backlog.withLock { n -> Bool in
            guard n < Self.maxBacklog else { return false }
            n += 1
            return true
        }
        guard admitted else { return }
        feedQueue.async { [weak self] in
            guard let self else { return }
            self.backlog.withLock { $0 -= 1 }
            self.feed?.append(buffer)
        }
    }

    // MARK: - Segments (main queue)

    private func beginSegment() {
        guard let recognizer else { return }
        guard recognizer.isAvailable else { return retryLater("recognizer unavailable") }

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.taskHint = .search
        request.addsPunctuation = false
        // Biases the wake word, which the parser needs spelled one of three ways.
        request.contextualStrings = ["Moto party", "motoparty"]
        if recognizer.supportsOnDeviceRecognition { request.requiresOnDeviceRecognition = true }

        segment += 1
        let id = segment
        segmentStartedAt = Date()
        lastText = ""
        feedQueue.async { [weak self] in self?.feed = request }
        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            let text = result?.bestTranscription.formattedString
            let isFinal = result?.isFinal ?? false
            DispatchQueue.main.async { self?.result(segment: id, text: text, isFinal: isFinal, error: error) }
        }
    }

    private func result(segment id: Int, text: String?, isFinal: Bool, error: Error?) {
        guard id == segment, recognizer != nil else { return }
        if let text, !text.isEmpty, text != lastText {
            lastText = text
            failures = 0
            phraseTimer?.invalidate()
            phraseTimer = Timer.scheduledTimer(withTimeInterval: endOfPhraseSeconds, repeats: false) { [weak self] _ in
                self?.phraseEnded()
            }
        }
        if isFinal { return phraseEnded() }
        guard let error else { return }
        // "No speech detected" after a quiet stretch is the usual one; anything
        // else is logged the same way. The talk goes on either way.
        let heard = lastText
        endSegment()
        deliver(heard)
        if Date().timeIntervalSince(segmentStartedAt) < 1 {
            retryLater(error.localizedDescription)
        } else {
            Log.voice.info("recognition ended (\(error.localizedDescription, privacy: .public)); next phrase")
            beginSegment()
        }
    }

    /// Final result, or the speaker paused: take the phrase and start the next.
    private func phraseEnded() {
        let heard = lastText
        endSegment()
        deliver(heard)
        beginSegment()
    }

    private func deliver(_ text: String) {
        let phrase = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !phrase.isEmpty else { return }
        onPhrase?(phrase)
    }

    /// Ends the current request: its task stops and its buffers go nowhere.
    private func endSegment() {
        segment += 1
        phraseTimer?.invalidate()
        phraseTimer = nil
        restartTimer?.invalidate()
        restartTimer = nil
        task?.cancel()
        task = nil
        lastText = ""
        feedQueue.async { [weak self] in
            self?.feed?.endAudio()
            self?.feed = nil
        }
    }

    /// Failing straight away again (no model, recogniser busy): wait 1, 2, 4 …
    /// up to 10 s before the next try instead of spinning.
    private func retryLater(_ why: String) {
        failures += 1
        let delay = min(10, pow(2, Double(failures - 1)))
        Log.voice.error("recognition failed (\(why, privacy: .public)); retrying in \(delay, privacy: .public) s")
        restartTimer?.invalidate()
        restartTimer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
            guard let self, self.recognizer != nil else { return }
            self.restartTimer = nil
            self.beginSegment()
        }
    }
}
#endif
