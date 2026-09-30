#if os(iOS)
import MotopartyCore
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @AppStorage("diagnosticsExpanded") private var diagnosticsExpanded = false

    var body: some View {
        NavigationStack {
            Form {
                Section("This phone") {
                    LabeledContent("Name") {
                        TextField("iPhone", text: $settings.deviceName)
                            .multilineTextAlignment(.trailing)
                    }
                    Picker("Speech language", selection: $settings.speechLanguage) {
                        ForEach(AppSettings.languages, id: \.self) { tag in
                            Text(Self.languageName(tag)).tag(tag)
                        }
                    }
                }

                Section {
                    // Through the model, so a playing track re-syncs to the new offset.
                    Stepper(value: Binding(get: { settings.latencyTrimMs },
                                           set: { model.adjustTrim(by: $0 - settings.latencyTrimMs) }),
                            in: -500...1_000, step: 10) {
                        LabeledContent("Music sync offset") {
                            Text("\(Int(settings.latencyTrimMs)) ms").monospacedDigit()
                        }
                    }
                } footer: {
                    Text("If the music in your ears is behind the rider's, raise it; if it is ahead, lower it. Each step is 10 ms.")
                }

                Section("Link") {
                    LabeledContent("Status", value: model.linkLabel)
                    Button("Reconnect") { model.reconnect() }
                }

                Section {
                    DisclosureGroup("Diagnostics", isExpanded: $diagnosticsExpanded) {
                        DiagnosticsRows()
                        // Audit M2: off = the offset alone, for the click-track test.
                        Toggle("Compensate output latency",
                               isOn: Binding(get: { settings.compensateOutputLatency },
                                             set: { model.setCompensateOutputLatency($0) }))
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }

    /// "English (United States)" for `en-US`; the tag itself when it has no name.
    static func languageName(_ tag: String) -> String {
        Locale.current.localizedString(forIdentifier: tag) ?? tag
    }
}

/// The only view that reads `LinkStats`, so its once-a-second numbers redraw
/// these three rows and nothing else.
private struct DiagnosticsRows: View {
    @EnvironmentObject private var stats: LinkStats

    var body: some View {
        LabeledContent("Round trip", value: stats.rttMs.map { "\(Int($0)) ms" } ?? "–")
        LabeledContent("Music drift", value: stats.driftMs.map { "\(Int($0)) ms" } ?? "–")
        LabeledContent("Audio output", value: stats.audioRoute.isEmpty ? "–" : stats.audioRoute)
    }
}
#endif
