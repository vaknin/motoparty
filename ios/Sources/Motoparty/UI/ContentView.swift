#if os(iOS)
import SwiftUI

/// One screen: link status, now playing, two big glove-friendly buttons,
/// latency trim. Settings in a sheet.
struct ContentView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                StatusRow()
                NowPlayingCard()
                Spacer(minLength: 0)
                BigButton(title: talkTitle, systemImage: "mic.fill", color: talkColor) {
                    model.talkButton()
                }
                BigButton(title: model.listening ? "LISTENING…" : "MUSIC",
                          systemImage: model.listening ? "waveform" : "music.note",
                          color: model.listening ? .purple : .blue) {
                    model.commandButton()
                }
                TrimRow()
                if let heard = model.lastHeard {
                    Text("Heard: “\(heard)”").font(.footnote).foregroundStyle(.secondary)
                }
                if let said = model.lastAnnouncement {
                    Text("Host: \(said)").font(.footnote).foregroundStyle(.secondary)
                }
                if let problem = model.problem {
                    Text(problem).font(.footnote).foregroundStyle(.red).multilineTextAlignment(.center)
                }
            }
            .padding()
            .navigationTitle("Motoparty")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
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

private struct StatusRow: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(model.link.isConnected ? Color.green : Color.orange)
                .frame(width: 12, height: 12)
            Text(model.link.label).font(.subheadline.weight(.semibold))
            Spacer()
            if let rtt = model.rttMs {
                Text("\(Int(rtt.rounded())) ms").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
        }
    }
}

private struct NowPlayingCard: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let track = model.nowPlaying {
                Text(track.title).font(.title3.weight(.bold)).lineLimit(1)
                Text(track.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                TimelineView(.periodic(from: .now, by: 1)) { _ in
                    let position = model.displayPositionMs() ?? 0
                    ProgressView(value: min(position, Double(track.durationMs)), total: Double(max(track.durationMs, 1)))
                    HStack {
                        Text(clock(position))
                        Spacer()
                        Text(model.musicPlaying ? "Playing" : "Paused")
                        Spacer()
                        Text(clock(Double(track.durationMs)))
                    }
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                }
            } else {
                Text("Nothing playing").font(.title3.weight(.semibold)).foregroundStyle(.secondary)
                Text("Press MUSIC and say “play album …”").font(.footnote).foregroundStyle(.secondary)
            }
            if let downloading = model.downloading {
                Label("Downloading \(downloading)", systemImage: "arrow.down.circle").font(.caption)
            }
            if let queue = model.hostState?.queue, !queue.isEmpty {
                Text("Up next: \(queue[0].title)\(queue.count > 1 ? " +\(queue.count - 1)" : "")")
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    private func clock(_ ms: Double) -> String {
        let s = Int(ms / 1000)
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}

private struct TrimRow: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        HStack {
            Text("Latency trim").font(.subheadline)
            Spacer()
            Button { model.adjustTrim(by: -10) } label: { Image(systemName: "minus.circle.fill").font(.title2) }
            Text("\(Int(settings.latencyTrimMs)) ms")
                .font(.body.monospacedDigit())
                .frame(minWidth: 70)
            Button { model.adjustTrim(by: 10) } label: { Image(systemName: "plus.circle.fill").font(.title2) }
        }
        .buttonStyle(.plain)
    }
}

struct BigButton: View {
    let title: String
    let systemImage: String
    let color: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.system(size: 34, weight: .heavy, design: .rounded))
                .frame(maxWidth: .infinity, minHeight: 120)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.roundedRectangle(radius: 24))
        .tint(color)
    }
}
#endif
