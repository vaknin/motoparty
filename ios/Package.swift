// swift-tools-version: 6.0

import PackageDescription

// Layout follows `xtool new`: exactly one automatic library product (the app).
// xtool wraps it in a generated executable target and bundles it as
// Motoparty.app. On Linux, `swift build` / `swift test` build COpus,
// MotopartyCore and the tests; every file in Sources/Motoparty is wrapped in
// `#if os(iOS)`, so the app target compiles to an empty module there.
let package = Package(
    name: "Motoparty",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "Motoparty", targets: ["Motoparty"]),
    ],
    targets: [
        // libopus 1.5.2, portable float build. Sources vendored by
        // scripts/fetch-opus.sh; settings in Sources/COpus/config.h.
        .target(
            name: "COpus",
            exclude: ["COPYING", "VERSION"],
            cSettings: [
                .define("HAVE_CONFIG_H", to: "1"),
                .headerSearchPath("."),
                .headerSearchPath("celt"),
                .headerSearchPath("silk"),
                .headerSearchPath("silk/float"),
                .headerSearchPath("src"),
            ]
        ),
        // Pure Swift + Foundation: protocol, clock sync, jitter buffer, Opus
        // wrapper, music anchor math. Tested on Linux.
        .target(
            name: "MotopartyCore",
            dependencies: ["COpus"]
        ),
        // The iOS app (SwiftUI, AVFoundation, Network, Speech, MediaPlayer).
        // Swift 5 language mode: this code is only compiled by xtool against
        // the iOS SDK, and Swift 6 strict concurrency across AVFoundation /
        // Network callbacks would turn every unchecked detail into an error.
        .target(
            name: "Motoparty",
            dependencies: ["MotopartyCore"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "MotopartyCoreTests",
            dependencies: ["MotopartyCore"]
        ),
    ]
)
