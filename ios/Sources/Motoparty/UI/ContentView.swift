#if os(iOS)
import MotopartyCore
import SwiftUI

enum AppTab: Hashable {
    case ride, search, queue
}

/// Three tabs: Ride (talk, now playing), Search (browse and
/// queue music by touch, PROTOCOL.md "Browsing") and Queue. Search and Queue
/// carry a mini player above the tab bar; a tap on it goes back to Ride.
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
                .safeAreaInset(edge: .bottom, spacing: 0) { MiniPlayer { tab = .ride } }
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
                .tag(AppTab.search)
            QueueView(openSearch: { tab = .search })
                .safeAreaInset(edge: .bottom, spacing: 0) { MiniPlayer { tab = .ride } }
                .tabItem { Label("Queue", systemImage: "list.bullet") }
                .badge(QueueText.badge(model.hostState?.queue.count ?? 0).map { Text(verbatim: $0) })
                .tag(AppTab.queue)
        }
        .onChange(of: scenePhase) { _, phase in
            // Back from the Settings app, perhaps with a permission granted.
            if phase == .active { model.refreshPermissions() }
        }
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
    @EnvironmentObject private var model: AppModel
    let open: () -> Void
    @State private var pressed = 0

    private var playingHere: Bool { model.musicPlaying && !model.musicHeldForRoute }

    var body: some View {
        if model.nowPlaying != nil || model.talkOpen {
            HStack(spacing: 12) {
                Button(action: open) {
                    HStack(spacing: 12) {
                        Artwork(url: model.nowPlaying == nil ? nil : model.hostState?.music?.art, size: 40)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(model.nowPlaying?.title ?? "Nothing playing")
                                .font(.subheadline.weight(.semibold))
                                .lineLimit(1)
                            if let artist = model.nowPlaying?.artist, !artist.isEmpty {
                                Text(artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            }
                        }
                        Spacer(minLength: 0)
                        if model.talkOpen {
                            Label(model.talkLive ? "LIVE" : "Connecting…", systemImage: "mic.fill")
                                .font(.caption.weight(.bold))
                                .lineLimit(1)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .foregroundStyle(model.talkLive ? Color.white : Brand.onOrange)
                                .background(model.talkLive ? Brand.live : Brand.waiting, in: Capsule())
                        }
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
                        .font(.title2)
                        .contentTransition(.symbolEffect(.replace))
                        .frame(width: 48, height: 48)
                        .contentShape(Rectangle())
                }
                .buttonStyle(GlyphButtonStyle())
                .disabled(model.nowPlaying == nil || !model.link.isConnected)
                .accessibilityLabel(playingHere ? "Pause" : "Play")
                .sensoryFeedback(.success, trigger: pressed)
            }
            .padding(.leading, 12)
            .padding(.trailing, 4)
            .padding(.vertical, 6)
            .background(.bar)
            .overlay(alignment: .top) { Divider() }
            .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        }
    }
}
#endif
