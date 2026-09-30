#if os(iOS)
import MotopartyCore
import SwiftUI

enum AppTab: Hashable {
    case ride, search, queue
}

/// Three tabs: Ride (talk, now playing), Search (browse and
/// queue music by touch, PROTOCOL.md "Browsing") and Queue. Search and Queue
/// carry a mini player above the tab bar; a tap on it goes back to Ride.
/// From iOS 26.1 it is the tab view's bottom accessory; before, a bar inset
/// into each of the two tabs. Never both.
struct ContentView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var tab: AppTab = .ride

    var body: some View {
        TabView(selection: $tab) {
            RideView()
                .tabItem { Label("Ride", systemImage: "dot.radiowaves.left.and.right") }
                .tag(AppTab.ride)
            SearchView()
                .modifier(MiniPlayerInset { tab = .ride })
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
                .tag(AppTab.search)
            QueueView(openSearch: { tab = .search })
                .modifier(MiniPlayerInset { tab = .ride })
                .tabItem { Label("Queue", systemImage: "list.bullet") }
                .badge(QueueText.badge(model.hostState?.queue.count ?? 0).map { Text(verbatim: $0) })
                .tag(AppTab.queue)
        }
        .modifier(MiniPlayerAccessory(shown: tab != .ride && MiniPlayer.hasContent(model), model: model) { tab = .ride })
        .onChange(of: scenePhase) { _, phase in
            // Back from the Settings app, perhaps with a permission granted.
            if phase == .active { model.refreshPermissions() }
        }
    }
}

/// iOS 17 to 26.0: the mini player as a bar at the bottom of one tab.
private struct MiniPlayerInset: ViewModifier {
    let open: () -> Void

    func body(content: Content) -> some View {
        if #available(iOS 26.1, *) {
            content
        } else {
            content.safeAreaInset(edge: .bottom, spacing: 0) { MiniPlayer(open: open) }
        }
    }
}

/// iOS 26.1 and later: the mini player in the glass capsule above the tab
/// bar, on Search and Queue while there is a track or a talk. 26.1 and not
/// 26.0, because only `tabViewBottomAccessory(isEnabled:)` can take the
/// capsule away again (26.0's accessory is always there, also empty).
private struct MiniPlayerAccessory: ViewModifier {
    let shown: Bool
    let model: AppModel
    let open: () -> Void

    func body(content: Content) -> some View {
        if #available(iOS 26.1, *) {
            content.tabViewBottomAccessory(isEnabled: shown) {
                AccessoryMiniPlayer(open: open).environmentObject(model)
            }
        } else {
            content
        }
    }
}

/// The accessory sits beside the tab bar (`.inline`) once the bar is
/// minimised, with room for one short line.
@available(iOS 26.0, *)
private struct AccessoryMiniPlayer: View {
    @Environment(\.tabViewBottomAccessoryPlacement) private var placement
    let open: () -> Void

    var body: some View {
        MiniPlayer(style: placement == .inline ? .inlineAccessory : .accessory, open: open)
    }
}

// MARK: - Shared pieces

/// Shown instead of browsing controls while there is no link.
struct NotConnectedHint: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Label("\(model.linkLabel) Music can be browsed once linked.", systemImage: "wifi.slash")
            .font(.footnote)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

extension SearchKind {
    var label: String {
        switch self {
        case .songs: "Songs"
        case .albums: "Albums"
        case .playlists: "Playlists"
        }
    }
}

/// What plays, on the tabs that are not Ride: cover, title, a talk chip while
/// a talk is open, play/pause. Nothing while there is neither track nor talk.
struct MiniPlayer: View {
    enum Style {
        /// A bar of its own above the tab bar.
        case bar
        /// In the tab view's bottom accessory, which brings the background.
        case accessory
        /// The same, beside a minimised tab bar: no artist, the chip a glyph.
        case inlineAccessory
    }

    @EnvironmentObject private var model: AppModel
    var style = Style.bar
    let open: () -> Void
    @State private var pressed = 0

    /// A track or a talk: what either form of the mini player is shown for.
    static func hasContent(_ model: AppModel) -> Bool {
        model.nowPlaying != nil || model.talkOpen
    }

    private var playingHere: Bool { model.musicPlaying && !model.musicHeldForRoute }

    var body: some View {
        if Self.hasContent(model) {
            switch style {
            case .bar:
                content
                    .padding(.leading, 12)
                    .padding(.trailing, 4)
                    .padding(.vertical, 6)
                    .background(.bar)
                    .overlay(alignment: .top) { Divider() }
                    .dynamicTypeSize(...DynamicTypeSize.accessibility1)
            case .accessory, .inlineAccessory:
                // The capsule has a fixed height: two lines fit up to xxxLarge.
                content
                    .padding(.leading, 12)
                    .padding(.trailing, 4)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            }
        }
    }

    private var content: some View {
        HStack(spacing: style == .inlineAccessory ? 8 : 12) {
            Button(action: open) {
                HStack(spacing: style == .inlineAccessory ? 8 : 12) {
                    Artwork(url: model.nowPlaying == nil ? nil : model.hostState?.music?.art,
                            size: style == .bar ? 40 : style == .accessory ? 32 : 24)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(model.nowPlaying?.title ?? "Nothing playing")
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                        if style != .inlineAccessory, let artist = model.nowPlaying?.artist, !artist.isEmpty {
                            Text(artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                    if model.talkOpen { talkChip }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(RowButtonStyle())
            .accessibilityElement(children: .combine)
            .accessibilityHint("Opens the Ride tab")
            Button {
                model.playPauseButton()
                pressed += 1
            } label: {
                Image(systemName: playingHere ? "pause.fill" : "play.fill")
                    .font(style == .bar ? .title2 : .title3)
                    .contentTransition(.symbolEffect(.replace))
                    .frame(width: style == .inlineAccessory ? 40 : 48, height: style == .bar ? 48 : 40)
                    .contentShape(Rectangle())
            }
            .buttonStyle(GlyphButtonStyle())
            .disabled(model.nowPlaying == nil || !model.link.isConnected)
            .accessibilityLabel(playingHere ? "Pause" : "Play")
            .sensoryFeedback(.success, trigger: pressed)
        }
    }

    /// "LIVE" / "Connecting…" on the talk's colour; only the microphone where
    /// there is no room for the word.
    private var talkChip: some View {
        let text = model.talkLive ? "LIVE" : "Connecting…"
        return Group {
            if style == .inlineAccessory {
                Image(systemName: "mic.fill").accessibilityLabel(text)
            } else {
                Label(text, systemImage: "mic.fill").lineLimit(1)
            }
        }
        .font(.caption.weight(.bold))
        .padding(.horizontal, style == .inlineAccessory ? 6 : 8)
        .padding(.vertical, 4)
        .foregroundStyle(model.talkLive ? Color.white : Brand.onOrange)
        .background(model.talkLive ? Brand.live : Brand.waiting, in: Capsule())
    }
}
#endif
