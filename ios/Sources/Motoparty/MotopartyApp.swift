// Every file in this target is iOS-only. On Linux (`swift build` / `swift test`)
// the target compiles to an empty module; xtool builds it against the iOS SDK.
#if os(iOS)
import SwiftUI

@main
struct MotopartyApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .environmentObject(model.settings)
                // The brand orange of both phones, on a dark screen like the
                // Pixel's (readable in a tank bag, easy on a night ride).
                .tint(Brand.orange)
                .preferredColorScheme(.dark)
                .onAppear { model.start() }
        }
    }
}
#endif
