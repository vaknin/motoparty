#if os(iOS)
import MotopartyCore
import SwiftUI

/// Now playing and the host's upcoming queue (`state.queue`). Every edit is a
/// `music.edit` (an Undo also a `music.enqueue`) and shows at once, before
/// the host's next `state` confirms it (`QueueEdits`, audit UI3): a removed
/// row goes, a dragged row stays where it was dropped, an undone removal is
/// back in its place. A removal or a clear brings a banner with Undo for 10 s,
/// like Android's snackbar (`QueueUndo`).
struct QueueView: View {
    @EnvironmentObject private var model: AppModel
    /// The empty queue's "Search" button.
    let openSearch: () -> Void
    @State private var confirmClear = false
    @State private var edits = QueueEdits()
    @State private var undo: QueueUndo?
    /// Counts the edits sent, for the haptic.
    @State private var sent = 0

    private var queue: [HostState.QueueItem] { model.hostState?.queue ?? [] }
    private var connected: Bool { model.link.isConnected }

    var body: some View {
        let queue = queue
        let rows = edits.visible(queue)
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
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if let undo {
                    UndoBanner(text: undo.text) { self.undo(undo, queue: queue) }
                        .disabled(!connected)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
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
                    sent += 1
                    show(QueueUndo.cleared(rows.map(\.item), nowMs: MonotonicClock.nowMs()))
                }
            } message: {
                Text(QueueText.clearMessage(rows.count))
            }
        }
        .sensoryFeedback(.success, trigger: sent)
        .onChange(of: queue) { old, new in
            withAnimation { edits.queueChanged(from: old, to: new) }
        }
        // An edit the host never confirmed (the queue had moved, the link
        // dropped) is undone on screen.
        .task(id: edits.nextExpiryMs) {
            guard let at = edits.nextExpiryMs else { return }
            let wait = max(0, at - MonotonicClock.nowMs()) + 50
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000))
            guard !Task.isCancelled else { return }
            withAnimation { _ = edits.expire(nowMs: MonotonicClock.nowMs()) }
        }
        // The banner goes by itself after `QueueUndo.durationMs`.
        .task(id: undo?.untilMs) {
            guard let until = undo?.untilMs else { return }
            let wait = max(0, until - MonotonicClock.nowMs())
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000))
            guard !Task.isCancelled, undo?.untilMs == until else { return }
            show(nil)
        }
    }

    private func list(_ rows: [QueueRow], queue: [HostState.QueueItem]) -> some View {
        List {
            if let track = model.nowPlaying {
                Section(model.musicPlaying ? "Now playing" : "Paused") {
                    NowPlayingRow(track: track) { sent += 1 }
                }
            }
            Section(QueueText.upNext(rows.count)) {
                if rows.isEmpty {
                    Text("Nothing after this song.").foregroundStyle(.secondary)
                }
                ForEach(Array(rows.enumerated()), id: \.element.key) { position, row in
                    QueueEntry(number: position + 1, item: row.item,
                               jump: { jump(row) },
                               remove: { remove([row], queue: queue) })
                        .disabled(!connected)
                }
                .onDelete { offsets in
                    remove(offsets.filter(rows.indices.contains).map { rows[$0] }, queue: queue)
                }
                // onMove belongs to the ForEach, so it goes before the
                // modifiers that return a plain View.
                .onMove { source, destination in
                    move(source, to: destination, queue: queue)
                }
                .deleteDisabled(!connected)
                .moveDisabled(!connected)
            }
            if !connected {
                NotConnectedHint().listRowBackground(Color.clear)
            }
        }
        .animation(.default, value: rows.map(\.key))
    }

    /// `row` is a row of `edits.visible`, so its index is the one the host
    /// will have when it reads this edit.
    private func jump(_ row: QueueRow) {
        model.editQueue(.jump, index: row.index, id: row.item.id)
        sent += 1
    }

    /// A drag of one row (`source` holds one offset). It stays where it was
    /// dropped until the host's `state` has it there too.
    private func move(_ source: IndexSet, to destination: Int, queue: [HostState.QueueItem]) {
        guard source.count == 1, let from = source.first else { return }
        withAnimation {
            if let command = edits.move(from: from, insertBefore: destination, in: queue, nowMs: MonotonicClock.nowMs()) {
                send([command])
            }
        }
    }

    private func remove(_ rows: [QueueRow], queue: [HostState.QueueItem]) {
        let now = MonotonicClock.nowMs()
        withAnimation {
            let removed = edits.remove(rows, in: queue, nowMs: now)
            send(removed.map(\.command))
            if !removed.isEmpty { show(QueueUndo.removed(removed, nowMs: now)) }
        }
    }

    private func undo(_ undo: QueueUndo, queue: [HostState.QueueItem]) {
        withAnimation {
            send(undo.undo(&edits, queue: queue, nothingLoaded: model.nowPlaying == nil, nowMs: MonotonicClock.nowMs()))
        }
        show(nil)
    }

    private func show(_ banner: QueueUndo?) {
        withAnimation(.easeOut(duration: 0.2)) { undo = banner }
    }

    private func send(_ commands: [QueueCommand]) {
        guard !commands.isEmpty else { return }
        for command in commands {
            switch command {
            case let .edit(op, index, id, to):
                model.editQueue(op, index: index, id: id, to: to)
            case let .enqueueEnd(tracks):
                model.enqueue(.end, tracks: tracks)
            }
        }
        sent += 1
    }
}

/// "Removed: <title>" and Undo, at the bottom of the tab: Android's snackbar.
private struct UndoBanner: View {
    let text: String
    let undo: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text(text)
                .font(.subheadline)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: undo) {
                Text("Undo")
                    .font(.body.weight(.semibold))
                    .frame(minWidth: 64, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(GlyphButtonStyle())
            .foregroundStyle(Brand.orange)
        }
        .padding(.leading, 16)
        .padding(.trailing, 6)
        .padding(.vertical, 4)
        .background(Brand.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.white.opacity(0.08)))
        .shadow(color: .black.opacity(0.4), radius: 8, y: 2)
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
        .accessibilityElement(children: .contain)
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
