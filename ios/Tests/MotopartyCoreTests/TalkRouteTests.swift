import XCTest
@testable import MotopartyCore

/// `TalkRoute`: which microphone an own-mic talk takes, in Android's
/// `AudioRouter.choose` order (Bluetooth, wired, USB, then the phone).
final class TalkRouteTests: XCTestCase {
    private let builtIn = TalkRoute.Port(kind: .other, uid: "Built-In Microphone")
    private let buds = TalkRoute.Port(kind: .bluetoothHFP, uid: "AA:BB:CC:DD:EE:FF-tsco")
    private let wired = TalkRoute.Port(kind: .headsetMic, uid: "Wired Microphone")
    private let usb = TalkRoute.Port(kind: .usbAudio, uid: "AppleUSBAudioEngine:1")

    func testBluetoothWinsAsBefore() {
        XCTAssertEqual(TalkRoute.choose([builtIn, buds]), buds)
        XCTAssertEqual(TalkRoute.choose([builtIn, wired, buds]), buds)
        XCTAssertEqual(TalkRoute.choose([usb, wired, buds, builtIn]), buds)
    }

    func testWiredHeadsetWithoutEarbuds() {
        XCTAssertEqual(TalkRoute.choose([builtIn, wired]), wired)
        XCTAssertEqual(TalkRoute.choose([wired, builtIn]), wired)
    }

    func testWiredBeforeUSB() {
        XCTAssertEqual(TalkRoute.choose([builtIn, usb, wired]), wired)
        XCTAssertEqual(TalkRoute.choose([builtIn, usb]), usb)
    }

    func testNoHeadsetLeavesItToThePhone() {
        XCTAssertNil(TalkRoute.choose([builtIn]))
        XCTAssertNil(TalkRoute.choose([]))
        XCTAssertNil(TalkRoute.choose([TalkRoute.Port(kind: .other, uid: "LineIn"), builtIn]))
    }

    func testFirstOfTheBestKindAsIOSListsThem() {
        let second = TalkRoute.Port(kind: .bluetoothHFP, uid: "11:22:33:44:55:66-tsco")
        XCTAssertEqual(TalkRoute.choose([builtIn, buds, second]), buds)
        XCTAssertEqual(TalkRoute.choose([second, buds]), second)
    }

    func testOrderMatchesAndroid() {
        // AudioRouter.preference: BLE 0, SCO 1, wired 2, USB 3 (iOS has no
        // separate BLE input kind; HFP is its call-mode Bluetooth).
        XCTAssertEqual(TalkRoute.Kind.allCases.sorted(), [.bluetoothHFP, .headsetMic, .usbAudio, .other])
        XCTAssertTrue(TalkRoute.Kind.headsetMic.isHeadset)
        XCTAssertTrue(TalkRoute.Kind.usbAudio.isHeadset)
        XCTAssertFalse(TalkRoute.Kind.other.isHeadset)
    }

    func testPlanClearsASpeakerOverrideOnlyForAHeadset() {
        XCTAssertEqual(TalkRoute.plan(available: [builtIn, wired], outputOnSpeaker: true),
                       TalkRoute.Plan(input: wired, clearSpeakerOverride: true))
        XCTAssertEqual(TalkRoute.plan(available: [builtIn, wired], outputOnSpeaker: false),
                       TalkRoute.Plan(input: wired, clearSpeakerOverride: false))
        // No headset: the speaker is the talk's route, keep it.
        XCTAssertEqual(TalkRoute.plan(available: [builtIn], outputOnSpeaker: true),
                       TalkRoute.Plan(input: nil, clearSpeakerOverride: false))
    }

    func testPlugAndUnplugMidTalk() {
        // Phone only → a wired headset plugged in → unplugged again.
        XCTAssertNil(TalkRoute.plan(available: [builtIn], outputOnSpeaker: true).input)
        XCTAssertEqual(TalkRoute.plan(available: [builtIn, wired], outputOnSpeaker: true).input, wired)
        XCTAssertNil(TalkRoute.plan(available: [builtIn], outputOnSpeaker: false).input)
        // Earbuds and a wired set; the earbuds go: the wired one takes over.
        XCTAssertEqual(TalkRoute.plan(available: [builtIn, wired, buds], outputOnSpeaker: false).input, buds)
        XCTAssertEqual(TalkRoute.plan(available: [builtIn, wired], outputOnSpeaker: false).input, wired)
    }

    func testReplansOnlyWhenADeviceComesOrGoes() {
        XCTAssertTrue(TalkRoute.replans(reason: 1)) // newDeviceAvailable
        XCTAssertTrue(TalkRoute.replans(reason: 2)) // oldDeviceUnavailable
        // unknown 0, categoryChange 3, override 4, wakeFromSleep 6,
        // noSuitableRouteForCategory 7, routeConfigurationChange 8.
        for reason: UInt in [0, 3, 4, 6, 7, 8] { XCTAssertFalse(TalkRoute.replans(reason: reason), "\(reason)") }
    }

    func testLogWords() {
        XCTAssertEqual(TalkRoute.Kind.allCases.map(\.word), ["bluetooth", "wired", "usb", "phone"])
    }
}
