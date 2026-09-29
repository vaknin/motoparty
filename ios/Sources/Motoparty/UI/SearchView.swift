#if os(iOS)
import MotopartyCore
import SwiftUI

/// Search the host's catalog: songs play (or queue), albums and playlists
/// open a track list (PROTOCOL.md "Browsing").
struct SearchView: View {
    @EnvironmentObject private var model: AppModel
    @State private var query = ""
    @State private var kind: SearchKind = .songs

    var body: some View {
        NavigationStack {
            results
                .safeAreaInset(edge: .top, spacing: 0) { header }
                .navigationTitle("Search")
                .navigationBarTitleDisplayMode(.inline)
                .navigationDestination(for: ResultItem.self) { CollectionView(collection: $0) }
        }
    }

    private var connected: Bool { model.link.isConnected }

    private var header: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Search YouTube Music", text: $query)
                    .submitLabel(.search)
                    .autocorrectionDisabled()
                    .onSubmit { model.search(kind, query: query) }
                if !query.isEmpty {
                    Button { query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Clear")
                }
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 48)
            .background(.quaternary, in: RoundedRectangle(cornerRadius: 12, style: .continuous))

            Picker("Kind", selection: $kind) {
                ForEach(SearchKind.allCases, id: \.self) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            .onChange(of: kind) { _, newKind in model.search(newKind, query: query) }

            if !connected { NotConnectedHint() }
        }
        .disabled(!connected)
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(.bar)
    }

    @ViewBuilder
    private var results: some View {
        let list = model.searchResults
        let history = model.history
        if query.isEmpty, !list.loading, !(history.searches.isEmpty && history.played.isEmpty) {
            historyList(history)
        } else if list.loading {
            ProgressView("Searching…").frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let error = list.error {
            ContentUnavailableView(error, systemImage: "exclamationmark.triangle")
        } else if list.items.isEmpty {
            if list.requested {
                ContentUnavailableView("No results", systemImage: "magnifyingglass",
                                       description: Text("Try other words."))
            } else {
                ContentUnavailableView("Search YouTube Music", systemImage: "music.magnifyingglass",
                                       description: Text("Songs play right away; albums and playlists open."))
            }
        } else {
            List {
                ForEach(Array(list.items.enumerated()), id: \.offset) { _, item in
                    Group {
                        if model.searchedKind == .songs {
                            SongRow(item: item,
                                    onPlay: { model.enqueue(.now, songs: [item]) },
                                    onEnqueue: { model.enqueue($0, songs: [item]) })
                        } else {
                            NavigationLink(value: item) {
                                ResultRow(item: item,
                                          subtitle: TimeText.joined(item.artist, item.count.map { "\($0) songs" }))
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
            if !history.searches.isEmpty {
                Section {
                    ForEach(history.searches, id: \.self) { entry in
                        Button { rerun(entry) } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "clock.arrow.circlepath").foregroundStyle(.secondary)
                                Text(entry.query).lineLimit(1)
                                Spacer(minLength: 0)
                                Text(entry.kind.label).font(.caption).foregroundStyle(.secondary)
                            }
                            .frame(minHeight: 36)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(!connected)
                    }
                } header: {
                    HStack {
                        Text("Recent searches")
                        Spacer()
                        Button("Clear") { model.clearRecentSearches() }
                            .font(.subheadline)
                            .textCase(nil)
                    }
                }
            }
            if !history.played.isEmpty {
                Section("Recently played") {
                    ForEach(history.played, id: \.id) { track in
                        Button { model.playAgain(track) } label: {
                            HStack(spacing: 12) {
                                Artwork(url: track.art, size: 48)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(track.title).font(.body.weight(.medium)).lineLimit(1)
                                    Text(TimeText.joined(track.artist, TimeText.clock(Double(track.durationMs))))
                                        .font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                                }
                                Spacer(minLength: 0)
                            }
                            .padding(.vertical, 4)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(!connected)
                    }
                }
            }
        }
        .listStyle(.plain)
        .scrollDismissesKeyboard(.immediately)
    }

    /// Puts the entry back in the box and searches it; a kind change searches
    /// through the picker's `onChange`.
    private func rerun(_ entry: BrowseHistory.Search) {
        query = entry.query
        if kind == entry.kind {
            model.search(kind, query: entry.query)
        } else {
            kind = entry.kind
        }
    }
}

/// Art thumbnail, title and one line of detail.
private struct ResultRow: View {
    let item: ResultItem
    let subtitle: String
    var fallbackArt: String?

    var body: some View {
        HStack(spacing: 12) {
            Artwork(url: item.art ?? fallbackArt, size: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title).font(.body.weight(.medium)).lineLimit(1)
                if !subtitle.isEmpty {
                    Text(subtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }
}

/// A song: tap plays, the trailing menu (or a swipe) queues. The menu is
/// always visible because swipes are hard with gloves.
private struct SongRow: View {
    let item: ResultItem
    var fallbackArt: String?
    let onPlay: () -> Void
    let onEnqueue: (EnqueueMode) -> Void
    @State private var tapped = 0

    var body: some View {
        HStack(spacing: 8) {
            Button { tap(onPlay) } label: {
                ResultRow(item: item,
                          subtitle: TimeText.joined(item.artist, item.durationMs.map { TimeText.clock(Double($0)) }),
                          fallbackArt: fallbackArt)
            }
            .buttonStyle(.plain)
            Menu {
                Button { tap { onEnqueue(.next) } } label: { Label("Play next", systemImage: "text.line.first.and.arrowtriangle.forward") }
                Button { tap { onEnqueue(.end) } } label: { Label("Add to queue", systemImage: "text.append") }
            } label: {
                Image(systemName: "ellipsis.circle").font(.title2).frame(width: 44, height: 44).contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("More")
        }
        .swipeActions(edge: .leading) {
            Button { tap { onEnqueue(.next) } } label: { Label("Play next", systemImage: "text.line.first.and.arrowtriangle.forward") }
                .tint(.orange)
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
                    } actions: {
                        Button("Try again") { model.browse(collection) }.disabled(!connected)
                    }
                } else {
                    ForEach(Array(list.items.enumerated()), id: \.offset) { index, item in
                        SongRow(item: item, fallbackArt: collection.art,
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
            Artwork(url: collection.art, size: 200)
                .shadow(radius: 8, y: 4)
            Text(collection.title)
                .font(.title2.bold())
                .multilineTextAlignment(.center)
            let detail = TimeText.joined(collection.artist, list.items.isEmpty ? nil : "\(list.items.count) songs")
            if !detail.isEmpty {
                Text(detail).font(.subheadline).foregroundStyle(.secondary)
            }
            HStack(spacing: 12) {
                Button { model.enqueue(.now, songs: list.items, from: collection) } label: {
                    Label("Play", systemImage: "play.fill").frame(maxWidth: .infinity, minHeight: 36)
                }
                .buttonStyle(.borderedProminent)
                Button { model.enqueue(.end, songs: list.items, from: collection) } label: {
                    Label("Add to queue", systemImage: "text.append").frame(maxWidth: .infinity, minHeight: 36)
                }
                .buttonStyle(.bordered)
            }
            .controlSize(.large)
            .disabled(list.items.isEmpty || !connected)
            .padding(.top, 4)
            if !connected { NotConnectedHint() }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }
}
#endif
