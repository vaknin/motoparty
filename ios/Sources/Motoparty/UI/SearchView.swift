#if os(iOS)
import MotopartyCore
import SwiftUI

/// Search the host's catalog: songs play (or queue), albums and playlists
/// open a track list, artists their page (PROTOCOL.md "Browsing"). The
/// system's search field and scope bar; with an empty field, the recent
/// searches and recently played. The pushed pages live in
/// `AppModel.searchPath`, so a Ride tap can open one (2026-10-02).
struct SearchView: View {
    @EnvironmentObject private var model: AppModel
    @State private var query = ""
    @State private var kind: SearchKind = .songs
    /// The last `AppModel.searchBoxFill` put in the box.
    @State private var filledSerial = 0

    var body: some View {
        NavigationStack(path: $model.searchPath) {
            results
                .navigationTitle("Search")
                .navigationBarTitleDisplayMode(.inline)
                .navigationDestination(for: BrowseTarget.self) { target in
                    switch target {
                    case .collection(let collection): CollectionView(collection: collection)
                    case .artist(let artist): ArtistView(artist: artist)
                    }
                }
                .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always),
                            prompt: Text(SearchWording.prompt))
                .searchScopes($kind, activation: .onSearchPresentation) {
                    ForEach(SearchKind.allCases, id: \.self) { Text($0.label).tag($0) }
                }
                .onSubmit(of: .search) { model.search(kind, query: query) }
                .onChange(of: kind) { _, newKind in
                    // Not for a kind a Ride tap set along with its search.
                    if newKind != model.searchedKind || query != model.searchedQuery {
                        model.search(newKind, query: query)
                    }
                }
                // On appear too: the tab may first show because of the tap.
                .task(id: model.searchBoxFill) { fillBox() }
                .autocorrectionDisabled()
        }
    }

    /// A Ride tap searched: the box shows its words and kind (once per tap,
    /// so coming back to the tab keeps what the passenger typed since).
    private func fillBox() {
        guard let fill = model.searchBoxFill, fill.serial != filledSerial else { return }
        filledSerial = fill.serial
        query = fill.query
        kind = fill.kind
    }

    private var connected: Bool { model.link.isConnected }

    @ViewBuilder
    private var results: some View {
        let list = model.searchResults
        let history = model.history
        if query.isEmpty, !list.loading, !(history.searches.isEmpty && history.played.isEmpty) {
            historyList(history)
        } else if list.loading {
            ProgressView("Searching…").frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let error = list.error {
            ContentUnavailableView {
                Label(error, systemImage: "exclamationmark.triangle")
            } description: {
                Text(SearchWording.retryHint)
            } actions: {
                Button { model.retrySearch() } label: {
                    Text("Try again").frame(minWidth: 120, minHeight: 32)
                }
                .buttonStyle(.bordered)
                .disabled(!connected || model.searchedQuery.isEmpty)
            }
        } else if list.items.isEmpty {
            if list.requested {
                ContentUnavailableView(SearchWording.nothingFound(model.searchedQuery), systemImage: "magnifyingglass",
                                       description: Text("Try other words."))
            } else {
                ContentUnavailableView {
                    Label(SearchWording.emptyTitle, systemImage: "music.magnifyingglass")
                } description: {
                    Text(connected ? SearchWording.emptyDetail : "\(model.linkLabel) Music can be browsed once linked.")
                }
            }
        } else {
            List {
                if !connected {
                    NotConnectedHint().listRowSeparator(.hidden)
                }
                // The box was edited since: say whose results these are, as
                // the Pixel does.
                if let heading = SearchWording.staleHeading(searched: model.searchedQuery, box: query) {
                    Text(heading)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .listRowSeparator(.hidden)
                }
                ForEach(Array(list.items.enumerated()), id: \.offset) { _, item in
                    Group {
                        switch model.searchedKind {
                        case .songs:
                            SongRow(item: item, isCurrent: item.ref == model.nowPlaying?.id,
                                    downloaded: model.hostDownloads.isCached(item.ref),
                                    onPlay: { model.enqueue(.now, songs: [item]) },
                                    onEnqueue: { model.enqueue($0, songs: [item]) })
                        case .artists:
                            NavigationLink(value: BrowseTarget.artist(item)) { ArtistRow(item: item) }
                        case .albums, .playlists:
                            NavigationLink(value: BrowseTarget.collection(item)) {
                                ResultRow(item: item,
                                          subtitle: TrackTime.joined([item.artist, item.count.map(QueueText.songs)]))
                            }
                        }
                    }
                    .disabled(!connected)
                }
            }
            .listStyle(.plain)
            .scrollDismissesKeyboard(.immediately)
        }
    }
}

extension SearchView {
    /// With an empty search box: recent searches (tap re-runs) and recently
    /// played tracks (tap plays now). Kept on this phone only.
    private func historyList(_ history: BrowseHistory) -> some View {
        List {
            if !connected {
                NotConnectedHint().listRowSeparator(.hidden)
            }
            if !history.searches.isEmpty {
                Section {
                    ForEach(history.searches, id: \.self) { entry in
                        Button { rerun(entry) } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "clock.arrow.circlepath").foregroundStyle(.secondary)
                                    .accessibilityHidden(true)
                                Text(entry.query).lineLimit(1)
                                Spacer(minLength: 0)
                                Text(entry.kind.label).font(.caption).foregroundStyle(.secondary)
                            }
                            .frame(minHeight: 40)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(RowButtonStyle())
                        .disabled(!connected)
                    }
                } header: {
                    HStack {
                        Text("Recent searches")
                        Spacer()
                        Button("Clear") { model.clearRecentSearches() }
                            .font(.subheadline)
                            .textCase(nil)
                            .accessibilityLabel("Clear recent searches")
                    }
                }
            }
            if !history.played.isEmpty {
                Section("Recently played") {
                    ForEach(history.played, id: \.id) { track in
                        PlayedRow(track: track, isCurrent: track.id == model.nowPlaying?.id,
                                  play: { model.playAgain(track) },
                                  onEnqueue: { model.enqueue($0, tracks: [track.enqueueTrack]) })
                        .disabled(!connected)
                    }
                }
            }
        }
        .listStyle(.plain)
        .scrollDismissesKeyboard(.immediately)
    }

    /// Puts the entry back in the box and searches it; a kind change searches
    /// through the scope's `onChange`.
    private func rerun(_ entry: BrowseHistory.Search) {
        query = entry.query
        if kind == entry.kind {
            model.search(kind, query: entry.query)
        } else {
            kind = entry.kind
        }
    }
}

/// A recently played track: a tap plays it now, the trailing menu (or a
/// swipe) queues it, as a song result does. The one that is playing is
/// marked and its tap does nothing (a tap would restart it, and end a talk:
/// UI10); it can still be queued.
private struct PlayedRow: View {
    let track: BrowseHistory.Track
    let isCurrent: Bool
    let play: () -> Void
    let onEnqueue: (EnqueueMode) -> Void
    @State private var tapped = 0

    var body: some View {
        HStack(spacing: 4) {
            Button { tap(play) } label: {
                HStack(spacing: 12) {
                    Artwork(url: track.art, size: 48)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(track.title).font(.body.weight(.medium)).lineLimit(1)
                            .foregroundStyle(isCurrent ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
                        Text(TrackTime.joined([isCurrent ? "Now playing" : nil, track.artist,
                                               TrackTime.clock(Double(track.durationMs))]))
                            .font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    if isCurrent {
                        Image(systemName: "speaker.wave.2.fill").foregroundStyle(.tint).accessibilityHidden(true)
                    }
                }
                .padding(.vertical, 4)
                .contentShape(Rectangle())
            }
            .buttonStyle(RowButtonStyle())
            .disabled(isCurrent)
            .accessibilityElement(children: .combine)
            .accessibilityHint(isCurrent ? "" : "Plays it now")
            EnqueueMenu(title: track.title) { mode in tap { onEnqueue(mode) } }
        }
        .swipeActions(edge: .leading) {
            Button { tap { onEnqueue(.next) } } label: { Label("Play next", systemImage: "text.line.first.and.arrowtriangle.forward") }
                .tint(Brand.orange)
        }
        .swipeActions(edge: .trailing) {
            Button { tap { onEnqueue(.end) } } label: { Label("Add to queue", systemImage: "text.append") }
                .tint(.blue)
        }
        .sensoryFeedback(.success, trigger: tapped)
    }

    private func tap(_ action: () -> Void) {
        action()
        tapped += 1
    }
}

/// "Play next" / "Add to queue" behind a 48 pt ⋯ button, always visible
/// because swipes are hard with gloves.
private struct EnqueueMenu: View {
    let title: String
    let enqueue: (EnqueueMode) -> Void

    var body: some View {
        Menu {
            Button { enqueue(.next) } label: { Label("Play next", systemImage: "text.line.first.and.arrowtriangle.forward") }
            Button { enqueue(.end) } label: { Label("Add to queue", systemImage: "text.append") }
        } label: {
            Image(systemName: "ellipsis.circle").font(.title2).frame(width: 48, height: 48).contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .accessibilityLabel("More options for \(title)")
    }
}

/// Art thumbnail, title and one line of detail.
struct ResultRow: View {
    let item: ResultItem
    let subtitle: String
    var fallbackArt: String?
    var isCurrent = false
    /// On the host's cache (PROTOCOL.md "Browsing" step 6): a small mark
    /// before the subtitle, as on the Pixel, so it plays without coverage.
    var downloaded = false

    var body: some View {
        HStack(spacing: 12) {
            Artwork(url: item.art ?? fallbackArt, size: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title).font(.body.weight(.medium)).lineLimit(1)
                    .foregroundStyle(isCurrent ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
                if downloaded || !subtitle.isEmpty {
                    HStack(spacing: 4) {
                        if downloaded {
                            Image(systemName: "arrow.down.circle.fill")
                                .font(.footnote)
                                .foregroundStyle(.tint)
                                .accessibilityLabel("Downloaded")
                        }
                        Text(subtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
            }
            Spacer(minLength: 0)
            if isCurrent {
                Image(systemName: "speaker.wave.2.fill").foregroundStyle(.tint).accessibilityLabel("Now playing")
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }
}

/// A song: tap plays, the trailing menu (or a swipe) queues. The menu is
/// always visible because swipes are hard with gloves.
struct SongRow: View {
    let item: ResultItem
    var fallbackArt: String?
    var isCurrent = false
    var downloaded = false
    let onPlay: () -> Void
    let onEnqueue: (EnqueueMode) -> Void
    @State private var tapped = 0

    var body: some View {
        HStack(spacing: 4) {
            Button { tap(onPlay) } label: {
                ResultRow(item: item,
                          subtitle: TrackTime.joined([item.artist, item.durationMs.map { TrackTime.clock(Double($0)) }]),
                          fallbackArt: fallbackArt, isCurrent: isCurrent, downloaded: downloaded)
            }
            .buttonStyle(RowButtonStyle())
            .accessibilityElement(children: .combine)
            .accessibilityHint("Plays it now")
            Menu {
                Button { tap { onEnqueue(.next) } } label: { Label("Play next", systemImage: "text.line.first.and.arrowtriangle.forward") }
                Button { tap { onEnqueue(.end) } } label: { Label("Add to queue", systemImage: "text.append") }
            } label: {
                Image(systemName: "ellipsis.circle").font(.title2).frame(width: 48, height: 48).contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("More options for \(item.title)")
        }
        .swipeActions(edge: .leading) {
            Button { tap { onEnqueue(.next) } } label: { Label("Play next", systemImage: "text.line.first.and.arrowtriangle.forward") }
                .tint(Brand.orange)
        }
        .swipeActions(edge: .trailing) {
            Button { tap { onEnqueue(.end) } } label: { Label("Add to queue", systemImage: "text.append") }
                .tint(.blue)
        }
        .sensoryFeedback(.success, trigger: tapped)
    }

    private func tap(_ action: () -> Void) {
        action()
        tapped += 1
    }
}

/// An album or playlist: cover, Play / Add to queue, then its songs. Tapping
/// a song plays the collection from there.
struct CollectionView: View {
    let collection: ResultItem
    @EnvironmentObject private var model: AppModel
    @State private var tapped = 0

    /// The collection's songs, once the host has answered for this one.
    private var list: ResultList {
        model.browsedCollection == collection ? model.collectionResults : ResultList()
    }

    private var connected: Bool { model.link.isConnected }

    var body: some View {
        let list = list
        List {
            Section { header(list) }
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            Section {
                if list.loading {
                    ProgressView().frame(maxWidth: .infinity).padding()
                } else if let error = list.error {
                    ContentUnavailableView {
                        Label(error, systemImage: "exclamationmark.triangle")
                    } description: {
                        Text(SearchWording.retryHint)
                    } actions: {
                        Button("Try again") { model.browse(collection) }
                            .buttonStyle(.bordered)
                            .disabled(!connected)
                    }
                    .listRowSeparator(.hidden)
                } else if list.items.isEmpty {
                    // Answered, and there is nothing in it (audit UI9).
                    if list.requested {
                        ContentUnavailableView {
                            Label(SearchWording.emptyCollection, systemImage: "music.note.list")
                        } actions: {
                            Button("Try again") { model.browse(collection) }
                                .buttonStyle(.bordered)
                                .disabled(!connected)
                        }
                        .listRowSeparator(.hidden)
                    }
                } else {
                    ForEach(Array(list.items.enumerated()), id: \.offset) { index, item in
                        SongRow(item: item, fallbackArt: collection.art, isCurrent: item.ref == model.nowPlaying?.id,
                                downloaded: model.hostDownloads.isCached(item.ref),
                                onPlay: { model.enqueue(.now, songs: Array(list.items[index...]), from: collection) },
                                onEnqueue: { model.enqueue($0, songs: [item], from: collection) })
                    }
                    .disabled(!connected)
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle(collection.title)
        .navigationBarTitleDisplayMode(.inline)
        .sensoryFeedback(.success, trigger: tapped)
        .task(id: collection) {
            // Coming back to the same collection keeps its songs.
            if model.browsedCollection != collection || model.collectionResults.error != nil
                || (!model.collectionResults.loading && model.collectionResults.items.isEmpty) {
                model.browse(collection)
            }
        }
    }

    private func header(_ list: ResultList) -> some View {
        VStack(spacing: 10) {
            Artwork(url: collection.art, size: 200, cornerRadius: 16)
                .shadow(radius: 8, y: 4)
            Text(collection.title)
                .font(.title2.bold())
                .multilineTextAlignment(.center)
            let detail = TrackTime.joined([collection.artist, list.items.isEmpty ? nil : QueueText.songs(list.items.count)])
            if !detail.isEmpty {
                Text(detail).font(.subheadline).foregroundStyle(.secondary)
            }
            HStack(spacing: 12) {
                Button {
                    model.enqueue(.now, songs: list.items, from: collection)
                    tapped += 1
                } label: {
                    Label("Play", systemImage: "play.fill")
                        .fontWeight(.semibold)
                        .foregroundStyle(Brand.onOrange)
                        .frame(maxWidth: .infinity, minHeight: 36)
                }
                .buttonStyle(.borderedProminent)
                Button {
                    model.enqueue(.end, songs: list.items, from: collection)
                    tapped += 1
                } label: {
                    Label("Add to queue", systemImage: "text.append").frame(maxWidth: .infinity, minHeight: 36)
                }
                .buttonStyle(.bordered)
            }
            .controlSize(.large)
            .disabled(list.items.isEmpty || !connected)
            .padding(.top, 4)
            if !list.items.isEmpty { downloadButton(list.items) }
            if !connected { NotConnectedHint() }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }

    /// Every song of it into the host's cache, for patchy coverage, as on the
    /// Pixel: shows the progress and stops on a second tap; "Downloaded" once
    /// every song is there (PROTOCOL.md "Browsing" step 6).
    private func downloadButton(_ songs: [ResultItem]) -> some View {
        let button = model.hostDownloads.button(ref: collection.ref, songs: songs.map(\.ref))
        return Button {
            model.downloadButton(collection, songs: songs)
            tapped += 1
        } label: {
            HStack(spacing: 8) {
                switch button.phase {
                case .running: ProgressView().controlSize(.small)
                case .done: Image(systemName: "checkmark.circle.fill")
                case .retry: Image(systemName: "arrow.clockwise")
                case .idle: Image(systemName: "arrow.down.circle")
                }
                Text(button.label).lineLimit(1).monospacedDigit()
            }
            .frame(maxWidth: .infinity, minHeight: 36)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .disabled(button.isDone || !connected)
        .accessibilityHint(button.isRunning ? "Stops the download" : "")
    }
}
#endif
