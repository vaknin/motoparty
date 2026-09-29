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
}
#endif
