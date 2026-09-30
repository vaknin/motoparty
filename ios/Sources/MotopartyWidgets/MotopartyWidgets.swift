#if os(iOS)
import ActivityKit
import MotopartyActivity
import MotopartyCore
import SwiftUI
import WidgetKit

/// The widget extension (PlugIns/MotopartyWidgets.appex): one Live Activity,
/// no home-screen widget. Display only: a tap opens the app.
@main
struct MotopartyWidgets: WidgetBundle {
    var body: some Widget {
        RideActivity()
    }
}

/// The app's colours (`Motoparty/UI/Theme.swift`; the extension cannot link
/// the app).
private enum Brand {
    static let orange = Color(red: 1, green: 0x7A / 255.0, blue: 0x2F / 255.0)
    static let live = Color(red: 0xE5 / 255.0, green: 0x48 / 255.0, blue: 0x4D / 255.0)
    static let waiting = Color(red: 0xFB / 255.0, green: 0xBF / 255.0, blue: 0x24 / 255.0)
}

private extension LiveActivityState {
    var inTalk: Bool { mode == .connecting || mode == .talking }

    var tint: Color {
        switch mode {
        case .talking: Brand.live
        case .connecting: Brand.waiting
        case .idle, .paused, .playing: Brand.orange
        }
    }

    /// What the activity is about: a talk, music, or the link alone.
    var symbol: String {
        switch mode {
        case .talking, .connecting: "mic.fill"
        case .playing, .paused: "music.note"
        case .idle: "dot.radiowaves.left.and.right"
        }
    }

    /// Playing or paused; nil when that is not the point.
    var transportSymbol: String? {
        switch mode {
        case .playing: "waveform"
        case .paused: "pause.fill"
        case .idle, .connecting, .talking: nil
        }
    }

    var spoken: String {
        switch mode {
        case .playing: "Playing \(title), \(detail)"
        case .paused: "Paused: \(title), \(detail)"
        case .idle, .connecting, .talking: detail
        }
    }
}

struct RideActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: MotopartyActivityAttributes.self) { context in
            LockScreenView(state: context.state)
                .activityBackgroundTint(Color.black.opacity(0.75))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            let state = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Image(systemName: state.symbol)
                        .font(.title2)
                        .foregroundStyle(state.tint)
                        .frame(maxHeight: .infinity)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Trailing(state: state)
                        .font(.title3)
                        .frame(maxHeight: .infinity)
                }
                DynamicIslandExpandedRegion(.center) {
                    Lines(state: state)
                }
            } compactLeading: {
                Image(systemName: state.symbol).foregroundStyle(state.tint)
            } compactTrailing: {
                Trailing(state: state)
            } minimal: {
                Image(systemName: state.symbol).foregroundStyle(state.tint)
            }
            .keylineTint(state.tint)
        }
    }
}

/// The banner on the lock screen (and on a phone without a Dynamic Island).
private struct LockScreenView: View {
    let state: LiveActivityState

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: state.symbol)
                .font(.title3)
                .foregroundStyle(state.tint)
                .frame(width: 40, height: 40)
                .background(state.tint.opacity(0.18), in: Circle())
            Lines(state: state)
            Spacer(minLength: 0)
            Trailing(state: state).font(.title3)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .foregroundStyle(.white)
    }
}

/// During a talk the talk comes first and the track second; otherwise the
/// track and its artist (or "Motoparty" and how the link is).
private struct Lines: View {
    let state: LiveActivityState

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(state.inTalk ? state.detail : state.title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(state.inTalk ? state.tint : Color.white)
                .lineLimit(1)
            let second = state.inTalk ? (state.title == "Motoparty" ? "" : state.title) : state.detail
            if !second.isEmpty {
                Text(second).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(state.spoken)
    }
}

/// The talk's running clock, or playing / paused.
private struct Trailing: View {
    let state: LiveActivityState

    var body: some View {
        if state.mode == .talking, let since = state.talkSince {
            // A timer text takes all the width it is offered: hold it to
            // "00:00".
            Text(since, style: .timer)
                .monospacedDigit()
                .multilineTextAlignment(.trailing)
                .frame(maxWidth: 48)
                .foregroundStyle(state.tint)
        } else if state.mode == .connecting {
            Image(systemName: "ellipsis").foregroundStyle(state.tint)
        } else if let symbol = state.transportSymbol {
            Image(systemName: symbol).foregroundStyle(state.tint)
        }
    }
}
#endif
