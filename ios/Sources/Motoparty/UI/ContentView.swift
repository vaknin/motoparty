#if os(iOS)
import MotopartyCore
import SwiftUI

/// Three tabs: Ride (talk, now playing), Search (browse and
/// queue music by touch, PROTOCOL.md "Browsing") and Queue.
struct ContentView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        TabView {
            RideView()
                .tabItem { Label("Ride", systemImage: "dot.radiowaves.left.and.right") }
            SearchView()
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
            QueueView()
                .tabItem { Label("Queue", systemImage: "list.bullet") }
                .badge(model.hostState?.queue.count ?? 0)
        }
    }
}

// MARK: - Shared pieces

/// Cover art from a URL the phone fetches itself (over the host's hotspot),
/// or a music-note tile while it loads or when there is none.
struct Artwork: View {
    let url: String?
    let size: CGFloat

    var body: some View {
        AsyncImage(url: url.flatMap(URL.init(string:))) { phase in
            if let image = phase.image {
                image.resizable().scaledToFill()
            } else {
                ZStack {
                    Rectangle().fill(.quaternary)
                    Image(systemName: "music.note")
                        .font(.system(size: size * 0.4, weight: .semibold))
                        .foregroundStyle(.secondary)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: max(6, size * 0.1), style: .continuous))
    }
}

/// Shown instead of browsing controls while there is no host.
struct NotConnectedHint: View {
    var body: some View {
        Label("Not connected: browsing works once the host is found", systemImage: "wifi.slash")
            .font(.footnote)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

enum TimeText {
    /// "m:ss".
    static func clock(_ ms: Double) -> String {
        let s = max(0, Int(ms / 1000))
        return String(format: "%d:%02d", s / 60, s % 60)
    }

    /// Non-empty parts joined with " · ".
    static func joined(_ parts: String?...) -> String {
        parts.compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
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
