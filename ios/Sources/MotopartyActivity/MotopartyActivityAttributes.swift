#if os(iOS)
import ActivityKit
import MotopartyCore

/// The Live Activity's type, shared by the app (which starts and updates it)
/// and the widget extension (which draws it): ActivityKit pairs the two by
/// this type, so both link this one module. Nothing is fixed for the life of
/// the activity; everything shown is in the content state.
public struct MotopartyActivityAttributes: ActivityAttributes {
    public typealias ContentState = LiveActivityState

    public init() {}
}
#endif
