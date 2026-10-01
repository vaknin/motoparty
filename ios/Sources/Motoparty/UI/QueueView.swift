#if os(iOS)
import MotopartyCore
import SwiftUI

/// Now playing and the host's upcoming queue (`state.queue`). Every edit is a
/// `music.edit`. A removed row goes at once and stays gone while the host's
/// next `state` is on its way (`QueueRemovals`, audit UI3). A dragged row is
/// not reordered here: the host's next `state` brings the new order.
struct QueueView: View {
    @EnvironmentObject private var model: AppModel
    /// The empty queue's "Search" button.
    let openSearch: () -> Void
    @State private var confirmClear = false
    @State private var removals = QueueRemovals()
    /// Counts the edits sent, for the haptic.
    @State private var edits = 0

    private var queue: [HostState.QueueItem] { model.hostState?.queue ?? [] }
    private var connected: Bool { model.link.isConnected }

    var body: some View {
        let queue = queue
        let rows = removals.visible(queue)
        NavigationStack {
            Group {
                if model.nowPlaying == nil && rows.isEmpty {
                    ContentUnavailableView {
                        Label("The queue is empty", systemImage: "music.note.list")
                    } description: {
                        Text("Songs you add on the Search tab, or on the rider's phone, show up here.")
                    } actions: {
                        Button(action: openSearch) {
                            Label("Search", systemImage: "magnifyingglass")
                                .fontWeight(.semibold)
                                .foregroundStyle(Brand.onOrange)
                                .frame(minWidth: 120, minHeight: 32)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                } else {
                    list(rows, queue: queue)
                }
            }
            .navigationTitle("Queue")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Clear", role: .destructive) { confirmClear = true }
                        .disabled(rows.isEmpty || !connected)
                }
            }
            .confirmationDialog("Clear the queue?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Clear queue", role: .destructive) {
                    model.editQueue(.clear)
                    edits += 1
                }
            } message: {
                Text(QueueText.clearMessage(rows.count))
            }
        }
        .sensoryFeedback(.success, trigger: edits)
        .onChange(of: queue) { old, new in
            withAnimation { removals.queueChanged(from: old, to: new) }
        }
        // A removal the host never confirmed (the queue had moved, the link
        // dropped) brings its row back.
        .task(id: removals.nextExpiryMs) {
            guard let at = removals.nextExpiryMs else { return }
            let wait = max(0, at - MonotonicClock.nowMs()) + 50
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000))
            guard !Task.isCancelled else { return }
            withAnimation { _ = removals.expire(nowMs: MonotonicClock.nowMs()) }
        }
    }

    private func list(_ rows: [QueueRow], queue: [HostState.QueueItem]) -> some View {
        List {
            if let track = model.nowPlaying {
                Section(model.musicPlaying ? "Now playing" : "Paused") {
                    NowPlayingRow(track: track) { edits += 1 }
                }
            }
            Section(QueueText.upNext(rows.count)) {
                if rows.isEmpty {
                    Text("Nothing after this song.").foregroundStyle(.secondary)
                }
                ForEach(Array(rows.enumerated()), id: \.element.key) { position, row in
                    QueueEntry(number: position + 1, item: row.item,
                               jump: { jump(row, queue: queue) },
                               remove: { remove([row], queue: queue) })
                        .disabled(!connected)
                }
                .onDelete { offsets in
                    remove(offsets.filter(rows.indices.contains).map { rows[$0] }, queue: queue)
                }
                .deleteDisabled(!connected)
                .onMove { source, destination in
                    move(source, to: destination, rows: rows, queue: queue)
                }
                .moveDisabled(!connected)
            }
            if !connected {
                NotConnectedHint().listRowBackground(Color.clear)
            }
        }
        .animation(.default, value: rows.map(\.key))
    }

    private func jump(_ row: QueueRow, queue: [HostState.QueueItem]) {
        model.editQueue(.jump, index: removals.wireIndex(of: row, in: queue), id: row.item.id)
        edits += 1
    }

    /// A drag of one row (`source` holds one offset). `destination` is the
    /// insertion offset in `rows` before the move; the row's place afterwards
    /// is one less when it moves down. The host applies the removals in
    /// flight first, so its queue then is `rows`, and that place is `to`.
    private func move(_ source: IndexSet, to destination: Int, rows: [QueueRow], queue: [HostState.QueueItem]) {
        guard source.count == 1, let from = source.first, rows.indices.contains(from) else { return }
        let to = destination > from ? destination - 1 : destination
        guard to != from else { return }
        let row = rows[from]
        model.editQueue(.move, index: removals.wireIndex(of: row, in: queue), id: row.item.id, to: to)
        edits += 1
    }

    /// Each remove names the row's index in the queue the host will have when
    /// it reads it: its own, less the removals sent before it.
    private func remove(_ removed: [QueueRow], queue: [HostState.QueueItem]) {
        guard !removed.isEmpty else { return }
        withAnimation {
            for row in removed.sorted(by: { $0.index < $1.index }) {
                model.editQueue(.remove, index: removals.wireIndex(of: row, in: queue), id: row.item.id)
                removals.remove(row, nowMs: MonotonicClock.nowMs())
            }
        }
        edits += 1
    }
}

/// The current track, with a play/pause button of its own.
private struct NowPlayingRow: View {
    @EnvironmentObject private var model: AppModel
    let track: MusicLoad
    let pressed: () -> Void

    private var playingHere: Bool { model.musicPlaying && !model.musicHeldForRoute }

    var body: some View {
        HStack(spacing: 12) {
            Artwork(url: model.hostState?.music?.art, size: 56)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title).font(.body.weight(.semibold)).lineLimit(1)
                Text(TrackTime.joined([track.artist, TrackTime.clock(Double(track.durationMs))]))
                    .font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 0)
            Button {
                model.playPauseButton()
                pressed()
            } label: {
                Image(systemName: playingHere ? "pause.fill" : "play.fill")
                    .font(.title2)
                    .contentTransition(.symbolEffect(.replace))
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
            }
            .buttonStyle(GlyphButtonStyle())
            .foregroundStyle(.tint)
            .disabled(!model.link.isConnected)
            .accessibilityLabel(playingHere ? "Pause" : "Play")
        }
        .padding(.vertical, 4)
    }
}

/// One upcoming song: a tap plays it now, the ✕ removes it (a real 44 pt
/// button: swipes are hard with gloves; the swipe works too).
private struct QueueEntry: View {
    let number: Int
    let item: HostState.QueueItem
    let jump: () -> Void
    let remove: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            Button(action: jump) {
                HStack(spacing: 12) {
                    Text("\(number)")
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                        .frame(minWidth: 22)
                    Artwork(url: item.art, size: 44)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.title).font(.body.weight(.medium)).lineLimit(1)
                        let detail = TrackTime.joined([item.artist, item.durationMs.map { TrackTime.clock(Double($0)) }])
                        if !detail.isEmpty {
                            Text(detail).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .padding(.vertical, 4)
                .contentShape(Rectangle())
            }
            .buttonStyle(RowButtonStyle())
            .accessibilityElement(children: .combine)
            .accessibilityHint("Plays it now")
            Button(action: remove) {
                Image(systemName: "xmark")
                    .font(.body.weight(.semibold))
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(GlyphButtonStyle())
            .foregroundStyle(.secondary)
            .accessibilityLabel("Remove \(item.title)")
        }
        .listRowInsets(EdgeInsets(top: 2, leading: 12, bottom: 2, trailing: 6))
    }
}
#endif
