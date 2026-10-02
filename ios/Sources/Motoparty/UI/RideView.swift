#if os(iOS)
import MotopartyCore
import SwiftUI
import UIKit

/// The riding screen. Everything above scrolls when it does not fit (small
/// phones, large text: audit UI5); the one big glove-friendly TALK button and
/// the volume level stay pinned at the bottom, in the thumb zone. A command is
/// the first phrase of a talk this phone opened. Settings in a sheet.
struct RideView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                ScrollView {
                    VStack(spacing: 12) {
                        PermissionsCard()
                        ProblemBanner()
                        // While a phrase can still be a command, the list of
                        // them takes the place of the (paused) music, as on
                        // the Pixel.
                        if commandsInPlace {
                            CommandsCard()
                                .transition(.opacity)
                        } else {
                            NowPlayingCard(artSize: Self.artSize(viewport: geometry.size.height))
                                .transition(.opacity)
                        }
                        StatusLines()
                        if !commandsInPlace { VoiceCommands() }
                    }
                    .padding(.horizontal)
                    .padding(.vertical, 8)
                    .animation(.easeInOut(duration: 0.2), value: commandsInPlace)
                }
                .scrollBounceBehavior(.basedOnSize)
            }
            // The cover's colour, faintly, behind it all (Android's `Ambient.kt`).
            .background { AmbientGlow(url: model.nowPlaying == nil ? nil : model.hostState?.music?.art) }
            .safeAreaInset(edge: .bottom, spacing: 0) { TalkDock() }
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
                    .environmentObject(model.stats)
                    .tint(Brand.orange)
                    .preferredColorScheme(.dark)
            }
        }
    }

    private var commandsInPlace: Bool { model.talkOpen && model.commandWindow }

    /// The cover is as big as the room above TALK allows: nil (a small cover
    /// beside the title) when there is little, up to 200 pt when there is a lot.
    static func artSize(viewport: CGFloat) -> CGFloat? {
        let room = viewport - 340
        return room < 120 ? nil : min(200, room)
    }
}

// MARK: - Talk

/// TALK, pinned above the tab bar, with the app volume under it.
private struct TalkDock: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        let phase = TalkPhase(requested: model.talkRequested, open: model.talkOpen,
                              live: model.talkLive, mode: model.talkMode)
        VStack(spacing: 8) {
            TalkButton(phase: phase, liveSince: model.talkLiveSince, linked: model.link.isConnected) {
                model.talkButton()
            }
            HStack(spacing: 12) {
                VolumeIndicator()
                Spacer(minLength: 0)
                if model.link.isConnected {
                    Text("Hold volume up: talk")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
            }
        }
        .padding(.horizontal)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .background(.bar)
        // A press, the talk going live, and its end: felt through a glove.
        // (iOS mutes haptics while a microphone records, so "live" is only
        // felt in a talk on the rider's mic; the earcon says it in every talk.)
        .sensoryFeedback(trigger: phase) { old, new in
            if new.isLive || new.isConnecting { return .impact(weight: .heavy) }
            return old.isLive ? .impact(weight: .medium) : nil
        }
    }
}

/// Idle: orange "TALK". The press is on its way, or the headset is still
/// switching: amber "Connecting…" with a pulsing mic. Live: red "END TALK",
/// who is heard through which mic, and how long the talk has run.
private struct TalkButton: View {
    let phase: TalkPhase
    let liveSince: Date?
    let linked: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: phase.isLive ? "waveform" : "mic.fill")
                    .font(.system(size: 30, weight: .bold))
                    .symbolEffect(.pulse, isActive: phase.isConnecting)
                    .contentTransition(.symbolEffect(.replace))
                    .frame(height: 34)
                Text(phase.buttonTitle)
                    .font(.largeTitle.weight(.heavy))
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                caption
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, minHeight: 128)
        }
        .buttonStyle(TalkButtonStyle(background: background, foreground: foreground))
        .opacity(linked ? 1 : 0.5)
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
        .animation(.easeInOut(duration: 0.2), value: phase)
        .accessibilityLabel(phase.accessibilityLabel)
        .accessibilityValue(phase.caption ?? (linked ? "" : "Not connected"))
        .accessibilityHint(linked ? "Holding volume up does the same with the phone in a pocket" : "")
    }

    @ViewBuilder
    private var caption: some View {
        if !linked {
            Text("Not connected")
        } else if let text = phase.caption {
            HStack(spacing: 6) {
                Text(text)
                if let liveSince {
                    Text("·")
                    Text(liveSince, style: .timer).monospacedDigit()
                }
            }
        } else if phase.isConnecting {
            Text("Press again to cancel")
        } else {
            Text("Say a command first, or just talk")
        }
    }

    private var background: Color {
        switch phase {
        case .idle: Brand.orange
        case .connecting: Brand.waiting
        case .live: Brand.live
        }
    }

    private var foreground: Color {
        phase.isLive ? .white : Brand.onOrange
    }
}

private struct TalkButtonStyle: ButtonStyle {
    let background: Color
    let foreground: Color

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(foreground)
            .background(background, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
            .brightness(configuration.isPressed ? -0.12 : 0)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
            .contentShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
    }
}

/// What the first phrase of a talk this phone opened may be (PROTOCOL.md
/// "Commands"), the same list as the Pixel's, as chips; with smart commands
/// on (`hello.interpret`), some of what those understand too. Folded away by
/// who knows them; the choice is remembered.
private struct VoiceCommands: View {
    @AppStorage("voiceCommandsExpanded") private var expanded = true

    var body: some View {
        DisclosureGroup(isExpanded: $expanded) {
            CommandsList(inTalk: false)
                .padding(.top, 8)
        } label: {
            Label("Voice commands", systemImage: "text.bubble")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
                .frame(minHeight: 32)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(Brand.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/// The command list in place of now playing, while the talk's first phrase
/// (or the reply to the host's question) can still be one: Android's
/// `CommandsCard`.
private struct CommandsCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Say a command", systemImage: "text.bubble")
                .font(.title3.weight(.semibold))
            CommandsList(inTalk: true)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Brand.card, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

/// Every command as a chip, then (smart commands on) the smart ones.
private struct CommandsList: View {
    @EnvironmentObject private var model: AppModel
    let inTalk: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(inTalk ? "Only the first thing you say: it is done and the talk ends. Anything else is just talk."
                        : "Press TALK and say one of these first: it is done and the talk ends. Anything else is just talk.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
            chips(VoiceCommandChip.all)
            if model.hostInterprets {
                Text("Smart commands are on: say it your own way, like")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 4)
                chips(VoiceCommandChip.smart)
            }
        }
    }

    private func chips(_ list: [VoiceCommandChip]) -> some View {
        FlowLayout(spacing: 6) {
            ForEach(list, id: \.words) { chip in
                CommandChip(chip: chip)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct CommandChip: View {
    let chip: VoiceCommandChip

    var body: some View {
        HStack(spacing: 4) {
            Text(chip.words).fontWeight(.medium)
            if let argument = chip.argument {
                Text(argument).italic().foregroundStyle(.secondary)
            }
            if let note = chip.note {
                Text("· \(note)").foregroundStyle(.secondary)
            }
        }
        .font(.footnote)
        .lineLimit(1)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Color.primary.opacity(0.09), in: Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(chip.accessibilityText)
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
                        .fill(step <= level ? (step > AppVolume.unityLevel ? Brand.orange : Color.primary)
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

// MARK: - Link

/// Green dot and the rider's phone's name when linked, amber and what the
/// link is doing otherwise. (The round trip is in Settings → Diagnostics.)
private struct ConnectionPill: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(model.link.isConnected ? Brand.good : Brand.waiting)
                .frame(width: 8, height: 8)
            Text(title)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 5)
        .background(Color.primary.opacity(0.1), in: Capsule())
        .dynamicTypeSize(...DynamicTypeSize.xxLarge)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(model.linkLabel)
    }

    private var title: String {
        if case .connected(let name) = model.link { return name }
        return model.linkLabel
    }
}

// MARK: - Now playing

private struct NowPlayingCard: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings
    /// nil: a small cover beside the title (little room).
    let artSize: CGFloat?

    private var art: String? { model.nowPlaying == nil ? nil : model.hostState?.music?.art }

    var body: some View {
        VStack(spacing: 12) {
            // The lyrics toggle (on the transport row) puts them where the
            // cover was; the titles, the clock and the buttons stay.
            if settings.showLyrics, let track = model.nowPlaying {
                LyricsPane(store: model.lyrics, trackId: track.id,
                           height: artSize.map { max($0, 180) } ?? 150)
                titles(centered: artSize != nil)
            } else if let artSize {
                Artwork(url: art, size: artSize, cornerRadius: 20)
                    .shadow(color: .black.opacity(0.35), radius: 12, y: 6)
                titles(centered: true)
            } else {
                HStack(spacing: 14) {
                    Artwork(url: art, size: 88)
                    titles(centered: false)
                }
            }
            if let track = model.nowPlaying {
                TrackProgress(durationMs: track.durationMs)
            }
            // Also with nothing loaded: "Searching …" for a spoken play.
            MusicStatusLine(status: model.musicStatus)
            TransportControls()
            UpNextLine()
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background { ArtBackdrop(url: art) }
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
    }

    @ViewBuilder
    private func titles(centered: Bool) -> some View {
        VStack(alignment: centered ? .center : .leading, spacing: 4) {
            if let track = model.nowPlaying {
                Text(track.title)
                    .font(centered ? .title2.bold() : .headline)
                    .lineLimit(2)
                // The artist, and the album when the host named one, open
                // their page on the Search tab (2026-10-02).
                link(track.artist, font: centered ? .body : .subheadline,
                     hint: "Opens the artist's page") {
                    model.openFromRide(.artist(credit: track.artist))
                }
                if let album = track.album, !album.isEmpty {
                    link(album, font: centered ? .subheadline : .footnote, hint: "Opens the album") {
                        model.openFromRide(.album(title: album, credit: track.artist))
                    }
                }
            } else {
                Text("Nothing playing")
                    .font(centered ? .title2.bold() : .headline)
                    .foregroundStyle(.secondary)
                Text("Find something on the Search tab, or press TALK and say “play …”")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .multilineTextAlignment(centered ? .center : .leading)
        .frame(maxWidth: .infinity, alignment: centered ? .center : .leading)
        // Not combined: the artist and album are buttons of their own.
        .accessibilityElement(children: .contain)
    }

    /// A line that reads as text and opens a page: no tint, a chevron, and a
    /// taller tap area than the text.
    private func link(_ text: String, font: Font, hint: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Text(text).lineLimit(1)
                Image(systemName: "chevron.right")
                    .font(.caption2.weight(.semibold))
                    .accessibilityHidden(true)
            }
            .font(font)
            .foregroundStyle(.secondary)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(text.isEmpty)
        .accessibilityHint(hint)
    }
}

/// The bar and the two clocks, from the host's anchor once a second. Only
/// this view redraws for it. The repeat mode is on its button
/// (`TransportControls`).
private struct TrackProgress: View {
    @EnvironmentObject private var model: AppModel
    let durationMs: Int64

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            let position = model.displayPositionMs() ?? 0
            VStack(spacing: 4) {
                ProgressView(value: min(position, Double(durationMs)), total: Double(max(durationMs, 1)))
                HStack {
                    Text(TrackTime.clock(position))
                        .contentTransition(.numericText())
                    Spacer()
                    Text(TrackTime.clock(Double(durationMs)))
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
                .animation(.default, value: Int(position / 1000))
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Position")
            .accessibilityValue("\(TrackTime.clock(position)) of \(TrackTime.clock(Double(durationMs)))")
        }
    }
}

/// Why the clock is not moving: the track is still on its way, or a talk
/// holds the music; else the host's voice search ("Searching …", with a
/// spinner, as on the Pixel). Nothing while playing or plainly paused.
private struct MusicStatusLine: View {
    let status: MusicStatus

    var body: some View {
        if let text = status.text {
            HStack(spacing: 6) {
                if status.spins {
                    ProgressView().controlSize(.small)
                } else if status == .pausedForTalk {
                    Image(systemName: "mic.fill")
                }
                Text(text).lineLimit(1)
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
        }
    }
}

/// Previous / play-pause / next, sent to the host as `music.control`: plain
/// large glyphs, each at least 64 pt to press. The repeat button (off → queue
/// → track) sits at the right end, balanced by the lyrics toggle on the left
/// (an empty slot until 2026-10-02) so play stays in the middle, as on the Pixel.
private struct TransportControls: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings
    @State private var pressed = 0

    /// Not while the music is held because the headset went away (the button
    /// is then Play, for this phone's speaker) or for a call.
    private var playingHere: Bool { model.playingHere }

    /// The repeat button's side, and the lyrics toggle's.
    private static let repeatSize: CGFloat = 44

    var body: some View {
        HStack(spacing: 0) {
            lyricsButton
            Spacer(minLength: 0)
            Button { press { model.musicControl(.previous) } } label: {
                Image(systemName: "backward.fill").font(.system(size: 28)).frame(width: 64, height: 64)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Previous track")
            Spacer(minLength: 0)
            Button { press { model.playPauseButton() } } label: {
                Image(systemName: playingHere ? "pause.fill" : "play.fill")
                    .font(.system(size: 44))
                    .contentTransition(.symbolEffect(.replace))
                    .frame(width: 80, height: 72)
                    .contentShape(Rectangle())
            }
            .disabled(model.nowPlaying == nil)
            .accessibilityLabel(playingHere ? "Pause" : "Play")
            Spacer(minLength: 0)
            Button { press { model.musicControl(.next) } } label: {
                Image(systemName: "forward.fill").font(.system(size: 28)).frame(width: 64, height: 64)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Next track")
            Spacer(minLength: 0)
            repeatButton
        }
        .frame(maxWidth: .infinity)
        .buttonStyle(GlyphButtonStyle())
        .foregroundStyle(.primary)
        .disabled(!model.link.isConnected)
        .sensoryFeedback(.success, trigger: pressed)
    }

    /// Off: a quiet glyph. Queue or track: lit, on a tinted circle; track
    /// shows the "1". It changes when the host's `state` does.
    private var repeatButton: some View {
        let mode = model.repeatSetting
        let on = mode != .off
        return Button { press { model.repeatButton() } } label: {
            Image(systemName: mode == .track ? "repeat.1" : "repeat")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(on ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                .contentTransition(.symbolEffect(.replace))
                .frame(width: Self.repeatSize, height: Self.repeatSize)
                .background { if on { Circle().fill(.tint.opacity(0.2)) } }
                .contentShape(Rectangle())
        }
        .disabled(model.nowPlaying == nil)
        .accessibilityLabel(mode.label)
    }

    /// Lyrics on or off, this phone only, on the left end (the repeat
    /// button's balance); lit like the repeat button when on.
    private var lyricsButton: some View {
        let on = settings.showLyrics
        return Button { press { model.setShowLyrics(!on) } } label: {
            Image(systemName: "quote.bubble")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(on ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                .frame(width: Self.repeatSize, height: Self.repeatSize)
                .background { if on { Circle().fill(.tint.opacity(0.2)) } }
                .contentShape(Rectangle())
        }
        .accessibilityLabel("Lyrics")
        .accessibilityValue(on ? "On" : "Off")
    }

    private func press(_ action: () -> Void) {
        action()
        pressed += 1
    }
}

// MARK: - Lyrics

/// Synced lyrics in the cover's place: the line before, the current one large
/// with its words lit as they are sung, and the two after. "♪" for an
/// instrumental break. The offset buttons move this track's lyrics by 0.2 s.
private struct LyricsPane: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var store: LyricsStore
    let trackId: String
    let height: CGFloat

    var body: some View {
        let shown = store.shown(for: trackId)
        VStack(spacing: 6) {
            switch shown {
            case .loading:
                Spacer(minLength: 0)
                HStack(spacing: 6) {
                    ProgressView().controlSize(.small)
                    Text("Looking for lyrics…")
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
                Spacer(minLength: 0)
            case .none:
                Spacer(minLength: 0)
                Label("No lyrics found", systemImage: "quote.bubble")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer(minLength: 0)
            case .found(let lyrics):
                LyricsLines(lyrics: lyrics, offsetMs: settings.lyricsOffsets.of(trackId))
                    .frame(maxHeight: .infinity)
                offsetRow
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: height)
        .clipped()
    }

    /// −0.2 s / the offset / +0.2 s (negative: the lyrics come sooner).
    private var offsetRow: some View {
        let ms = settings.lyricsOffsets.of(trackId)
        let label = ms == 0 ? "Lyrics timing" : String(format: "%+.1f s", Double(ms) / 1000)
        return HStack(spacing: 4) {
            Button { model.stepLyricsOffset(-1) } label: {
                Image(systemName: "minus").frame(width: 44, height: 32).contentShape(Rectangle())
            }
            .accessibilityLabel("Lyrics sooner")
            Text(label)
                .font(.caption.monospacedDigit())
                .frame(minWidth: 90)
                .accessibilityLabel(ms == 0 ? "Lyrics offset none" : "Lyrics offset \(label)")
            Button { model.stepLyricsOffset(1) } label: {
                Image(systemName: "plus").frame(width: 44, height: 32).contentShape(Rectangle())
            }
            .accessibilityLabel("Lyrics later")
        }
        .font(.footnote.weight(.semibold))
        .buttonStyle(GlyphButtonStyle())
        .foregroundStyle(.secondary)
    }
}

/// The lines around the lyrics position, redrawn every frame only while the
/// pane is up and the music plays (`TrackProgress` keeps its own 1 s clock).
private struct LyricsLines: View {
    @EnvironmentObject private var model: AppModel
    let lyrics: Lyrics
    let offsetMs: Int64

    /// A line on screen, by its index (-1: the "♪" before the first line).
    private struct Shown: Identifiable {
        enum Role { case previous, current, next }
        let id: Int
        let line: LyricLine?
        let role: Role
    }

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !model.playingHere)) { _ in
            let t = LyricsOffsets.lyricsMs(positionMs: model.displayPositionMs() ?? 0, offsetMs: offsetMs)
            let position = lyrics.position(atMs: t)
            VStack(spacing: 8) {
                ForEach(shown(position.line)) { item in
                    lineView(item, sung: item.role == .current ? position.sung : 0)
                        .transition(.asymmetric(insertion: .move(edge: .bottom).combined(with: .opacity),
                                                removal: .move(edge: .top).combined(with: .opacity)))
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .animation(.spring(duration: 0.4), value: position.line)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Lyrics")
            .accessibilityValue(currentText(position.line))
        }
    }

    private func shown(_ current: Int?) -> [Shown] {
        var items: [Shown] = []
        if let current, let previous = lyrics.previous(current) {
            items.append(Shown(id: current - 1, line: previous, role: .previous))
        }
        let base = current ?? -1
        items.append(Shown(id: base, line: current.map { lyrics.lines[$0] }, role: .current))
        for (n, line) in lyrics.upcoming(current, count: 2).enumerated() {
            items.append(Shown(id: base + 1 + n, line: line, role: .next))
        }
        return items
    }

    @ViewBuilder
    private func lineView(_ item: Shown, sung: Int) -> some View {
        let isBreak = item.line?.isBreak ?? true
        switch item.role {
        case .current:
            Group {
                if isBreak {
                    Text("♪")
                } else if let line = item.line {
                    words(line, sung: sung)
                }
            }
            .font(.title2.bold())
            .lineLimit(3)
            .minimumScaleFactor(0.6)
            .multilineTextAlignment(.center)
        case .previous, .next:
            Text(isBreak ? "♪" : item.line?.text ?? "")
                .font(.callout)
                .foregroundStyle(.secondary.opacity(item.role == .previous ? 0.6 : 1))
                .lineLimit(item.role == .previous ? 1 : 2)
                .multilineTextAlignment(.center)
        }
    }

    /// The words sung so far lit, the rest dim.
    private func words(_ line: LyricLine, sung: Int) -> Text {
        line.words.enumerated().reduce(Text("")) { text, word in
            let (n, w) = word
            return text + Text(n == 0 ? w.text : " " + w.text)
                .foregroundStyle(n < sung ? AnyShapeStyle(Brand.orange) : AnyShapeStyle(Color.primary.opacity(0.45)))
        }
    }

    private func currentText(_ current: Int?) -> String {
        guard let current, !lyrics.lines[current].isBreak else { return "Instrumental" }
        return lyrics.lines[current].text
    }
}

/// "Up next: Title  +3", as on the Pixel.
private struct UpNextLine: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        let queue = model.hostState?.queue ?? []
        if model.nowPlaying != nil, let next = queue.first {
            Text("Up next: \(next.title)" + (queue.count > 1 ? "  +\(queue.count - 1)" : ""))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

// MARK: - Status

/// What was just heard or announced (each goes after a few seconds), and why
/// this phone's music is silent.
private struct StatusLines: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        let heard = model.lastHeard
        let said = model.lastAnnouncement
        let held = model.musicHoldLine
        if heard != nil || said != nil || held != nil {
            VStack(alignment: .leading, spacing: 4) {
                if let heard {
                    Label("Heard: “\(heard)”", systemImage: "ear").lineLimit(1)
                }
                if let said {
                    Label("“\(said)”", systemImage: "speaker.wave.2").lineLimit(2)
                }
                if let held {
                    Label(held, systemImage: model.musicHeldForCall ? "phone.fill" : "headphones")
                }
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .transition(.opacity)
        }
    }
}

/// The one problem line: what failed, and a ✕. It also goes by itself when
/// the thing next works (`Notice`).
private struct ProblemBanner: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        if let problem = model.problem {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(Brand.waiting)
                    .padding(.top, 12)
                    .accessibilityHidden(true)
                Text(problem.text)
                    .font(.footnote)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 12)
                Button { model.dismissProblem() } label: {
                    Image(systemName: "xmark")
                        .font(.body.weight(.semibold))
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(GlyphButtonStyle())
                .foregroundStyle(.secondary)
                .accessibilityLabel("Dismiss")
            }
            .padding(.leading, 12)
            .background(Brand.live.opacity(0.22), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
    }
}

/// A refused permission is fixed in the Settings app, not here: say what it
/// costs and give the way there.
private struct PermissionsCard: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.openURL) private var openURL

    var body: some View {
        if model.micDenied || model.speechDenied {
            VStack(alignment: .leading, spacing: 8) {
                Text("Motoparty needs a few permissions").font(.subheadline.weight(.semibold))
                if model.micDenied {
                    Label("Microphone is off: you can only talk through the rider's clip-on mic.", systemImage: "mic.slash")
                }
                if model.speechDenied {
                    Label("Speech recognition is off: spoken commands don't work from this phone.", systemImage: "text.bubble")
                }
                Button {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                } label: {
                    Text("Open Settings").fontWeight(.semibold).foregroundStyle(Brand.onOrange)
                        .frame(maxWidth: .infinity, minHeight: 32)
                }
                .buttonStyle(.borderedProminent)
                .padding(.top, 2)
            }
            .font(.footnote)
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Brand.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
    }
}
#endif
