#if os(iOS)
import MotopartyCore
import SwiftUI

/// An artist search result: a round picture and the name (2026-10-02).
struct ArtistRow: View {
    let item: ResultItem

    var body: some View {
        HStack(spacing: 12) {
            Artwork(url: item.art, size: 48, cornerRadius: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title).font(.body.weight(.medium)).lineLimit(1)
                Text("Artist").font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }
}

/// An artist's page, as on Spotify and the Pixel (PROTOCOL.md "Browsing"
/// step 2a, 2026-10-02): picture and name, Play / Add to queue of the top
/// songs, the top songs (a tap plays from there), then the albums and
/// singles, each opening the album page. One `music.browse` brings it all.
struct ArtistView: View {
    let artist: ResultItem
    @EnvironmentObject private var model: AppModel
    @State private var tapped = 0

    /// The page, once the host has answered for this artist.
    private var list: ResultList {
        model.browsedArtist == artist ? model.artistResults : ResultList()
    }

    private var connected: Bool { model.link.isConnected }

    var body: some View {
        let list = list
        List {
            Section { header(list) }
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            if list.loading {
                ProgressView().frame(maxWidth: .infinity).padding()
                    .listRowSeparator(.hidden)
            } else if let error = list.error {
                ContentUnavailableView {
                    Label(error, systemImage: "exclamationmark.triangle")
                } description: {
                    Text(SearchWording.retryHint)
                } actions: {
                    Button("Try again") { model.browseArtist(artist) }
                        .buttonStyle(.bordered)
                        .disabled(!connected)
                }
                .listRowSeparator(.hidden)
            } else if list.isEmpty {
                if list.requested {
                    ContentUnavailableView {
                        Label(SearchWording.emptyArtist, systemImage: "music.mic")
                    } actions: {
                        Button("Try again") { model.browseArtist(artist) }
                            .buttonStyle(.bordered)
                            .disabled(!connected)
                    }
                    .listRowSeparator(.hidden)
                }
            } else {
                if !list.items.isEmpty {
                    Section(SearchWording.topSongs) {
                        ForEach(Array(list.items.enumerated()), id: \.offset) { index, item in
                            SongRow(item: item, isCurrent: item.ref == model.nowPlaying?.id,
                                    downloaded: model.hostDownloads.isCached(item.ref),
                                    onPlay: { model.enqueue(.now, songs: Array(list.items[index...])) },
                                    onEnqueue: { model.enqueue($0, songs: [item]) })
                        }
                        .disabled(!connected)
                    }
                }
                if !list.albums.isEmpty {
                    Section(SearchWording.albumsAndSingles) {
                        ForEach(Array(list.albums.enumerated()), id: \.offset) { _, album in
                            NavigationLink(value: BrowseTarget.collection(album)) {
                                ResultRow(item: album,
                                          subtitle: TrackTime.joined([album.artist, album.count.map(QueueText.songs)]))
                            }
                        }
                        .disabled(!connected)
                    }
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle(artist.title)
        .navigationBarTitleDisplayMode(.inline)
        .sensoryFeedback(.success, trigger: tapped)
        .task(id: artist) {
            // Back from one of its albums keeps the page; it is asked again
            // when another artist took the list meanwhile (a Ride tap), or it
            // failed or came back empty.
            if model.browsedArtist != artist || model.artistResults.error != nil
                || (!model.artistResults.loading && model.artistResults.isEmpty) {
                model.browseArtist(artist)
            }
        }
    }

    private func header(_ list: ResultList) -> some View {
        VStack(spacing: 10) {
            Artwork(url: artist.art, size: 160, cornerRadius: 80)
                .shadow(radius: 8, y: 4)
            Text(artist.title)
                .font(.title2.bold())
                .multilineTextAlignment(.center)
            HStack(spacing: 12) {
                Button {
                    model.enqueue(.now, songs: list.items)
                    tapped += 1
                } label: {
                    Label("Play", systemImage: "play.fill")
                        .fontWeight(.semibold)
                        .foregroundStyle(Brand.onOrange)
                        .frame(maxWidth: .infinity, minHeight: 36)
                }
                .buttonStyle(.borderedProminent)
                Button {
                    model.enqueue(.end, songs: list.items)
                    tapped += 1
                } label: {
                    Label("Add to queue", systemImage: "text.append").frame(maxWidth: .infinity, minHeight: 36)
                }
                .buttonStyle(.bordered)
            }
            .controlSize(.large)
            .disabled(list.items.isEmpty || !connected)
            .accessibilityHint("The top songs")
            .padding(.top, 4)
            if !connected { NotConnectedHint() }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }
}
#endif
