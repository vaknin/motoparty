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
                        ForEach(settings.languages, id: \.self) { tag in
                            Text(Self.languageName(tag)).tag(tag)
                        }
                    }
                }

                Section {
                    Toggle(isOn: $settings.keepScreenOn) {
                        Text("Keep screen on while riding")
                        Text("While the Ride tab is showing. Uses more battery")
                    }
                    Toggle(isOn: $settings.liveBeep) {
                        Text("Beep when the mic is live")
                        Text("A short beep once a talk's microphone is on")
                    }
                } header: {
                    Text("On the road")
                }

                SyncOffsetSection()

                Section("Link") {
                    LabeledContent("Status", value: model.linkLabel)
                    Button("Reconnect") { model.reconnect() }
                }

                Section("Motoparty") {
                    LabeledContent("Version", value: Self.version)
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

    /// "1.4 (27)": the app's version and build, from its Info.plist.
    static let version: String = {
        let info = Bundle.main.infoDictionary ?? [:]
        let short = info["CFBundleShortVersionString"] as? String
        let build = info["CFBundleVersion"] as? String
        switch (short, build) {
        case let (short?, build?) where build != short: return "\(short) (\(build))"
        case let (short?, _): return short
        case let (nil, build?): return build
        default: return "–"
        }
    }()

    /// "English (United States)" for `en-US`; the tag itself when it has no name.
    static func languageName(_ tag: String) -> String {
        Locale.current.localizedString(forIdentifier: tag) ?? tag
    }
}

/// The music sync offset of the output in use now: one per Bluetooth device,
/// one shared by this phone's speaker and wired outputs (`LatencyTrims`, as
/// on the Pixel). Reads `LinkStats` for the route, so it redraws with it.
private struct SyncOffsetSection: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var stats: LinkStats

    var body: some View {
        let route = stats.outputRoute
        let trim = settings.trims.of(route)
        Section {
            // Through the model, so a playing track re-syncs to the new offset.
            Stepper(value: Binding(get: { trim }, set: { model.setTrim($0) }),
                    in: LatencyTrims.minMs...LatencyTrims.maxMs, step: LatencyTrims.stepMs) {
                LabeledContent {
                    Text("\(Int(trim)) ms").monospacedDigit()
                } label: {
                    Text("Music sync offset")
                    Text(route.name).lineLimit(1)
                }
            }
        } header: {
            Text("Tuning")
        } footer: {
            Text("For the output in use now; each Bluetooth device keeps its own. If the music in your ears is behind the rider's, raise it; if it is ahead, lower it. Each step is 10 ms.")
        }
    }
}

/// With `SyncOffsetSection`, the only views that read `LinkStats`, so its
/// once-a-second numbers redraw these rows and nothing else.
private struct DiagnosticsRows: View {
    @EnvironmentObject private var stats: LinkStats

    var body: some View {
        LabeledContent("Round trip", value: stats.rttMs.map { "\(Int($0)) ms" } ?? "–")
        LabeledContent("Music drift", value: stats.driftMs.map { "\(Int($0)) ms" } ?? "–")
        LabeledContent("Audio output", value: stats.audioRoute.isEmpty ? "–" : stats.audioRoute)
    }
}
#endif
