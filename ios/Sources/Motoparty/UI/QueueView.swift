#if os(iOS)
import MotopartyCore
import SwiftUI

/// Now playing and the host's upcoming queue (`state.queue`). Every edit is a
/// `music.edit`; the list changes when the host's next `state` arrives.
struct QueueView: View {
    @EnvironmentObject private var model: AppModel
    @State private var confirmClear = false

    private var queue: [HostState.QueueItem] { model.hostState?.queue ?? [] }
    private var connected: Bool { model.link.isConnected }

    var body: some View {
        NavigationStack {
            Group {
                if model.nowPlaying == nil && queue.isEmpty {
                    ContentUnavailableView("Queue is empty", systemImage: "music.note.list",
                                           description: Text("Add songs from Search."))
                } else {
                    list
                }
            }
            .navigationTitle("Queue")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Clear", role: .destructive) { confirmClear = true }
                        .disabled(queue.isEmpty || !connected)
                }
            }
            .confirmationDialog("Clear the upcoming queue?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Clear queue", role: .destructive) { model.editQueue(.clear) }
            }
        }
    }

    private var list: some View {
        List {
            if let track = model.nowPlaying {
                Section("Now playing") {
                    HStack(spacing: 12) {
                        Artwork(url: model.hostState?.music?.art, size: 56)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(track.title).font(.body.weight(.semibold)).lineLimit(1)
                            Text(track.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                        Image(systemName: model.musicPlaying ? "speaker.wave.2.fill" : "pause.fill")
                            .foregroundStyle(.tint)
                    }
                    .padding(.vertical, 4)
                }
            }
            Section("Up next") {
                if queue.isEmpty {
                    Text("Queue is empty").foregroundStyle(.secondary)
                }
                ForEach(Array(queue.enumerated()), id: \.offset) { index, item in
                    Button { model.editQueue(.jump, index: index, id: item.id) } label: {
                        HStack(spacing: 12) {
                            Text("\(index + 1)")
                                .font(.subheadline.monospacedDigit())
                                .foregroundStyle(.secondary)
                                .frame(minWidth: 28)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.title).font(.body.weight(.medium)).lineLimit(1)
                                if !item.artist.isEmpty {
                                    Text(item.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                                }
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(.vertical, 6)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!connected)
                }
                .onDelete { offsets in
                    // Highest first: each remove names its index in the queue
                    // the host still has, so later ones must not shift earlier ones.
                    for index in offsets.sorted(by: >) where queue.indices.contains(index) {
                        model.editQueue(.remove, index: index, id: queue[index].id)
                    }
                }
                .deleteDisabled(!connected)
            }
            if !connected {
                NotConnectedHint().listRowBackground(Color.clear)
            }
        }
    }
}
#endif
