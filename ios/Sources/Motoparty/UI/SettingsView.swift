#if os(iOS)
import MotopartyCore
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("This phone") {
                    TextField("Name", text: $settings.deviceName)
                    Picker("Speech language", selection: $settings.speechLanguage) {
                        ForEach(AppSettings.languages, id: \.self) { Text($0).tag($0) }
                    }
                    // Through the model, so a playing track re-syncs to the new trim.
                    Stepper(value: Binding(get: { settings.latencyTrimMs },
                                           set: { model.adjustTrim(by: $0 - settings.latencyTrimMs) }),
                            in: -500...1_000, step: 10) {
                        Text("Latency trim: \(Int(settings.latencyTrimMs)) ms")
                    }
                }

                Section {
                    actionPicker("Play / pause", $settings.playPauseAction)
                    actionPicker("Next track", $settings.nextTrackAction)
                    actionPicker("Previous track", $settings.previousTrackAction)
                    Toggle("Explicit “pause” also counts", isOn: $settings.pauseCommandTriggers)
                } header: {
                    Text("Headset buttons")
                } footer: {
                    Text("AirPods: single, double, triple press. Other buds: set their gestures to play/pause, next and previous in their own app. Buds send “pause” when taken out of the ear, so that is ignored by default. Voice commands need no button: in a talk, say “Moto party, next”.")
                }

                Section("Link") {
                    LabeledContent("Status", value: model.link.label)
                    LabeledContent("Round trip", value: model.rttMs.map { "\(Int($0.rounded())) ms" } ?? "–")
                    LabeledContent("Music drift", value: model.driftMs.map { "\(Int($0.rounded())) ms" } ?? "–")
                    LabeledContent("Audio output", value: model.audioRoute)
                    Button("Reconnect") { model.reconnect() }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }

    private func actionPicker(_ title: String, _ binding: Binding<RemoteAction>) -> some View {
        Picker(title, selection: binding) {
            ForEach(RemoteAction.allCases) { Text($0.label).tag($0) }
        }
    }
}
#endif
