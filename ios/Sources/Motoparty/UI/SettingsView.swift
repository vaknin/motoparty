#if os(iOS)
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
                    Stepper(value: $settings.latencyTrimMs, in: -500...1_000, step: 10) {
                        Text("Latency trim: \(Int(settings.latencyTrimMs)) ms")
                    }
                    Stepper(value: $settings.commandMaxSeconds, in: 3...15, step: 1) {
                        Text("Command max length: \(Int(settings.commandMaxSeconds)) s")
                    }
                }

                Section {
                    actionPicker("Single press", $settings.playPauseAction)
                    actionPicker("Double press", $settings.nextTrackAction)
                    actionPicker("Triple press", $settings.previousTrackAction)
                    Toggle("Explicit “pause” also counts", isOn: $settings.pauseCommandTriggers)
                } header: {
                    Text("AirPods / headset buttons")
                } footer: {
                    Text("iOS also sends “pause” when an AirPod leaves the ear, so that is ignored by default.")
                }

                Section("Music") {
                    Button("Pause / resume") { model.musicControl(model.musicPlaying ? .pause : .resume) }
                    Button("Next track") { model.musicControl(.next) }
                    Button("Previous track") { model.musicControl(.previous) }
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
