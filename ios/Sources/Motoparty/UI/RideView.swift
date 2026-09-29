#if os(iOS)
import MotopartyCore
import SwiftUI

/// The riding screen: link pill, now playing with transport controls, and the
/// one big glove-friendly TALK button (a command is the first phrase of a talk
/// this phone opened). Settings in a sheet.
struct RideView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                NowPlayingCard()
                if let downloading = model.downloading {
                    Label("Downloading \(downloading)…", systemImage: "arrow.down.circle")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                Spacer(minLength: 0)
                VoiceCommands()
                BigButton(title: talkTitle, systemImage: "mic.fill", color: talkColor) {
                    model.talkButton()
                }
                HStack(spacing: 12) {
                    VolumeIndicator()
                    if model.link.isConnected {
                        Text("Hold volume up: talk")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                StatusLines()
            }
            .padding(.horizontal)
            .padding(.bottom, 8)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) { ConnectionPill() }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel("Settings")
                }
            }
            .sheet(isPresented: $showSettings) {
                SettingsView()
                    .environmentObject(model)
                    .environmentObject(model.settings)
            }
        }
    }

    private var talkTitle: String {
        if model.talkOpen { return "END TALK" }
        if model.talkRequested { return "TALK…" }
        return "TALK"
    }

    private var talkColor: Color {
        if model.talkOpen { return .red }
        if model.talkRequested { return .yellow }
        return .orange
    }
}

/// What the first phrase of a talk this phone opened may be (PROTOCOL.md
/// "Commands"), the same list as the Pixel's.
private struct VoiceCommands: View {
    private static let lines = [
        "play <song> · play album / artist / playlist <name>",
        "pause · resume",
        "next · previous",
        "louder · quieter",
        "what's playing",
        "shuffle",
        "over (ends the talk)",
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Voice commands").font(.subheadline.weight(.semibold))
            Text("Press TALK and say one of these first; after that it's just talk.")
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 1) {
                ForEach(Self.lines, id: \.self) { Text($0).lineLimit(1).minimumScaleFactor(0.8) }
            }
        }
        .font(.footnote)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// The app volume level (`AppVolume`): a speaker, a 16-step bar and
/// "12/16". While linked it is what everything plays at (the system volume
/// sits parked at 15/16); unlinked it is dimmed, the level the next link
/// starts at.
private struct VolumeIndicator: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        let level = model.volumeLevel
        let linked = model.link.isConnected
        HStack(spacing: 5) {
            Image(systemName: icon(level))
                .font(.caption)
                .frame(width: 18)
            HStack(spacing: 1.5) {
                ForEach(1...AppVolume.maxLevel, id: \.self) { step in
                    RoundedRectangle(cornerRadius: 1)
                        .fill(step <= level ? (step > AppVolume.unityLevel ? Color.orange : Color.primary)
                                            : Color.secondary.opacity(0.25))
                        .frame(width: 3, height: 10)
                }
            }
            Text("\(level)/\(AppVolume.maxLevel)")
                .font(.caption.monospacedDigit())
        }
        .foregroundStyle(linked ? .primary : .secondary)
        .opacity(linked ? 1 : 0.6)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("App volume \(level) of \(AppVolume.maxLevel)\(linked ? "" : ", applies when linked")")
    }

    private func icon(_ level: Int) -> String {
        switch level {
        case 0: "speaker.slash.fill"
        case 1...5: "speaker.wave.1.fill"
        case 6...11: "speaker.wave.2.fill"
        default: "speaker.wave.3.fill"
        }
    }
}

/// Green dot and host name when connected, orange and what the link is doing
/// otherwise; the round trip in small print.
private struct ConnectionPill: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(model.link.isConnected ? Color.green : Color.orange)
                .frame(width: 8, height: 8)
            Text(title).font(.subheadline.weight(.semibold)).lineLimit(1)
            if model.link.isConnected, let rtt = model.rttMs {
                Text("\(Int(rtt.rounded())) ms").font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 5)
        .background(.thinMaterial, in: Capsule())
    }

    private var title: String {
        if case .connected(let name) = model.link { return name }
        return model.link.label
    }
}

private struct NowPlayingCard: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 14) {
                Artwork(url: model.nowPlaying == nil ? nil : model.hostState?.music?.art, size: 88)
                VStack(alignment: .leading, spacing: 4) {
                    if let track = model.nowPlaying {
                        Text(track.title).font(.headline).lineLimit(2)
                        Text(track.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    } else {
                        Text("Nothing playing").font(.headline).foregroundStyle(.secondary)
                        Text("Search, or press TALK and say “play album …”")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            if let track = model.nowPlaying {
                TimelineView(.periodic(from: .now, by: 1)) { _ in
                    let position = model.displayPositionMs() ?? 0
                    VStack(spacing: 4) {
                        ProgressView(value: min(position, Double(track.durationMs)), total: Double(max(track.durationMs, 1)))
                        HStack {
                            Text(TimeText.clock(position))
                            Spacer()
                            Text(TimeText.clock(Double(track.durationMs)))
                        }
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                    }
                }
                MusicStatusLine(status: model.musicStatus)
            }
            TransportControls()
        }
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

/// Why the clock is not moving: the track is still on its way, or a talk
/// holds the music. Nothing while playing or plainly paused.
private struct MusicStatusLine: View {
    let status: MusicStatus

    var body: some View {
        if let text = status.text {
            HStack(spacing: 6) {
                switch status {
                case .loading: ProgressView().controlSize(.small)
                case .pausedForTalk: Image(systemName: "mic.fill")
                case .none: EmptyView()
                }
                Text(text)
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
        }
    }
}

/// Previous / play-pause / next, sent to the host as `music.control`.
private struct TransportControls: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        HStack(spacing: 28) {
            Button { model.musicControl(.previous) } label: {
                Image(systemName: "backward.fill").font(.title2).frame(width: 56, height: 56)
            }
            .buttonStyle(.bordered)
            .accessibilityLabel("Previous track")
            Button { model.musicControl(model.musicPlaying ? .pause : .resume) } label: {
                Image(systemName: model.musicPlaying ? "pause.fill" : "play.fill")
                    .font(.largeTitle).frame(width: 72, height: 72)
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.nowPlaying == nil)
            .accessibilityLabel(model.musicPlaying ? "Pause" : "Play")
            Button { model.musicControl(.next) } label: {
                Image(systemName: "forward.fill").font(.title2).frame(width: 56, height: 56)
            }
            .buttonStyle(.bordered)
            .accessibilityLabel("Next track")
        }
        .buttonBorderShape(.circle)
        .disabled(!model.link.isConnected)
    }
}

private struct StatusLines: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if let heard = model.lastHeard {
                Text("Heard: “\(heard)”").foregroundStyle(.secondary).lineLimit(1)
            }
            if let said = model.lastAnnouncement {
                Text("Host: \(said)").foregroundStyle(.secondary).lineLimit(1)
            }
            if let problem = model.problem {
                Text(problem).foregroundStyle(.red).lineLimit(2)
            }
        }
        .font(.footnote)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
#endif
