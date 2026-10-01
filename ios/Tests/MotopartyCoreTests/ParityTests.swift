import XCTest
@testable import MotopartyCore

/// The iPhone's screens doing what the Pixel's do (2026-10-01): smart-command
/// hints, the enqueue toast, the stale-results heading, the speech languages,
/// the ambient tint and the per-route sync offset.
final class ParityTests: XCTestCase {
    // MARK: Smart-command hints

    func testSmartChipsNeedTheInterpreter() {
        // Each is something only Gemini understands: the grammar does not.
        for chip in VoiceCommandChip.smart {
            XCTAssertNil(chip.argument)
            XCTAssertEqual(CommandParser.parse(chip.words), .unknown, chip.words)
        }
        let words = (VoiceCommandChip.all + VoiceCommandChip.smart).map(\.words)
        XCTAssertEqual(Set(words).count, words.count)
        XCTAssertTrue((6...8).contains(VoiceCommandChip.smart.count))
    }

    func testSmartChipsShowOnlyWhileTheHostInterprets() {
        XCTAssertEqual(VoiceCommandChip.shown(interprets: false), VoiceCommandChip.all)
        XCTAssertEqual(VoiceCommandChip.shown(interprets: true), VoiceCommandChip.all + VoiceCommandChip.smart)
    }

    func testSmartChipsAreAndroidsList() {
        // Android's SMART_COMMANDS (ui/Logic.kt), flattened: the same words in the same order.
        XCTAssertEqual(VoiceCommandChip.smart.map(\.words), [
            "repeat this song", "go back 30 seconds", "start over", "what's next",
            "remove the next song", "move the last song to next", "clear the queue", "undo",
        ])
    }

    // MARK: Enqueue toast

    func testEnqueueToastWordsAreAndroids() {
        XCTAssertEqual(Toast.enqueued(.next, titles: ["Yellow"], nothingLoaded: false), "Playing next: Yellow")
        XCTAssertEqual(Toast.enqueued(.end, titles: ["Yellow"], nothingLoaded: false), "Added to queue: Yellow")
        XCTAssertEqual(Toast.enqueued(.end, titles: ["A", "B", "C"], nothingLoaded: false), "Added 3 songs")
        XCTAssertEqual(Toast.enqueued(.next, titles: ["A", "B"], nothingLoaded: false), "Playing next: 2 songs")
    }

    func testNoToastWhenTheScreenShowsIt() {
        XCTAssertNil(Toast.enqueued(.now, titles: ["Yellow"], nothingLoaded: false))
        XCTAssertNil(Toast.enqueued(.end, titles: [], nothingLoaded: false))
        // Nothing loaded: the host plays it at once, Ride shows that.
        XCTAssertNil(Toast.enqueued(.next, titles: ["Yellow"], nothingLoaded: true))
    }

    func testToastGoesAfterFourSeconds() {
        let toast = Toast("Added to queue: Yellow", nowMs: 1_000)
        XCTAssertEqual(toast.untilMs, 5_000)
    }

    // MARK: Search

    func testStaleHeadingOnlyOnceTheBoxWasEdited() {
        XCTAssertNil(SearchWording.staleHeading(searched: "moby", box: "moby"))
        XCTAssertNil(SearchWording.staleHeading(searched: "moby", box: " moby "))
        XCTAssertNil(SearchWording.staleHeading(searched: "moby", box: ""))
        XCTAssertNil(SearchWording.staleHeading(searched: "", box: "moby"))
        XCTAssertEqual(SearchWording.staleHeading(searched: "moby", box: "moby porc"), "Results for “moby”")
    }

    // MARK: Settings

    func testSpeechLanguagesAreAndroids14() {
        XCTAssertEqual(SpeechLanguages.all.count, 14)
        XCTAssertEqual(Set(SpeechLanguages.all).count, 14)
        XCTAssertEqual(SpeechLanguages.all.first, "en-US")
        XCTAssertTrue(SpeechLanguages.all.contains("he-IL"))
        XCTAssertEqual(SpeechLanguages.offered(current: "en-GB"), SpeechLanguages.all)
        XCTAssertEqual(SpeechLanguages.offered(current: "ja-JP"), SpeechLanguages.all + ["ja-JP"])
    }

    // MARK: Ambient tint (Android's AmbientTest)

    private let androidSurface: UInt32 = 0x0E1013
    private let secondaryText: UInt32 = 0xB8BEC7
    private let waiting: UInt32 = 0xFBBF24
    private let warningRed: UInt32 = 0xE5484D

    func testNoSwatchesNoTint() {
        XCTAssertNil(Ambient.tint([]))
        XCTAssertNil(Ambient.tint([nil, nil]))
    }

    func testGreyCoverHasNoTint() {
        XCTAssertNil(Ambient.tint([0x808080, 0x000000, 0xFFFFFF, 0x70757A]))
    }

    func testFirstColourWinsGreysAndGapsAreSkipped() {
        let blue: UInt32 = 0x1D4ED8, red: UInt32 = 0xBE123C
        XCTAssertEqual(Ambient.tint([blue]), Ambient.tint([nil, 0x777777, blue, red]))
        XCTAssertEqual(Ambient.tint([red]), Ambient.tint([red, blue]))
    }

    func testKeepsTheHue() {
        let tint = Ambient.tint([0x1D4ED8])!
        let r = tint >> 16 & 0xFF, g = tint >> 8 & 0xFF, b = tint & 0xFF
        XCTAssertTrue(b > g && g > r, String(tint, radix: 16))
    }

    func testNearBlackCoverStillGlows() {
        for surface in [androidSurface, Ambient.surface] {
            let tint = Ambient.tint([0x03081C], surface: surface)!
            XCTAssertGreaterThan(Ambient.luminance(Ambient.blend(tint, alpha: Ambient.alpha, over: surface)),
                                 Ambient.luminance(surface) * 1.5 + 0.0005)
        }
    }

    func testAnyCoverLeavesTextReadable() {
        for surface in [androidSurface, Ambient.surface] {
            var tinted = 0
            for r in stride(from: 0, through: 255, by: 51) {
                for g in stride(from: 0, through: 255, by: 51) {
                    for b in stride(from: 0, through: 255, by: 51) {
                        let cover = UInt32(r << 16 | g << 8 | b)
                        guard let tint = Ambient.tint([cover], surface: surface) else { continue }
                        tinted += 1
                        let back = Ambient.blend(tint, alpha: Ambient.alpha, over: surface)
                        let name = "\(String(cover, radix: 16)) -> \(String(tint, radix: 16))"
                        XCTAssertLessThanOrEqual(Ambient.luminance(back), Ambient.maxLuminance, name)
                        XCTAssertGreaterThanOrEqual(Ambient.contrast(secondaryText, back), 7.0, name)
                        XCTAssertGreaterThanOrEqual(Ambient.contrast(waiting, back), 7.0, name)
                        XCTAssertGreaterThanOrEqual(Ambient.contrast(warningRed, back), 3.5, name)
                    }
                }
            }
            XCTAssertGreaterThan(tinted, 150)
        }
    }

    func testBrightYellowIsDarkenedBlueIsNot() {
        let yellow = Ambient.tint([0xFFE600], surface: androidSurface)!
        let blue = Ambient.tint([0x1D4ED8], surface: androidSurface)!
        XCTAssertLessThan(Ambient.luminance(yellow), 0.35)
        XCTAssertGreaterThan(Ambient.luminance(Ambient.blend(yellow, alpha: Ambient.alpha, over: androidSurface)),
                             Ambient.maxLuminance * 0.7)
        XCTAssertLessThanOrEqual(Ambient.luminance(Ambient.blend(blue, alpha: Ambient.alpha, over: androidSurface)),
                                 Ambient.maxLuminance)
    }

    func testContrastIsWcag() {
        XCTAssertEqual(Ambient.contrast(0xFFFFFF, 0x000000), 21, accuracy: 0.001)
        XCTAssertEqual(Ambient.contrast(androidSurface, androidSurface), 1, accuracy: 0.001)
        XCTAssertEqual(Ambient.blend(0xFFFFFF, alpha: 0.5, over: 0x000000), 0x808080)
    }

    func testSwatchesPutTheCoversColourBeforeItsGreys() {
        // Mostly grey, with a fifth of saturated blue: the blue comes first.
        let pixels = Array(repeating: UInt32(0x606060), count: 80) + Array(repeating: UInt32(0x2050E0), count: 20)
        let swatches = Ambient.swatches(pixels)
        XCTAssertEqual(swatches.first, 0x2050E0)
        XCTAssertEqual(swatches.count, 2)
        XCTAssertNotNil(Ambient.tint(swatches))
        // An all-grey cover: no tint.
        XCTAssertNil(Ambient.tint(Ambient.swatches(Array(repeating: 0x777777, count: 50))))
        XCTAssertEqual(Ambient.swatches([]), [])
    }

    // MARK: Music sync offset per output (Android's LatencyTrimsTest)

    private let airpods = OutputRoute(key: "AA:BB:CC:DD:EE:01", name: "AirPods Pro", wireless: true)
    private let other = OutputRoute(key: "AA:BB:CC:DD:EE:02", name: "Cardo", wireless: true)

    func testTheOldSingleValueBecomesTheWirelessDefault() {
        let trims = LatencyTrims.load(nil, legacy: 260)
        XCTAssertEqual(trims.of(airpods), 260)
        XCTAssertEqual(trims.of(other), 260)
        XCTAssertEqual(trims.of(.speaker), 0)
        XCTAssertEqual(LatencyTrims.load(nil, legacy: nil), LatencyTrims())
    }

    func testStoredTrimsWinOverTheLegacyOne() {
        let stored = LatencyTrims(local: 20, wirelessDefault: 100, byDevice: [airpods.key: 240]).encoded()
        let trims = LatencyTrims.load(stored, legacy: 260)
        XCTAssertEqual(trims.of(airpods), 240)
        XCTAssertEqual(trims.of(other), 100)
        XCTAssertEqual(trims.of(.speaker), 20)
        // Unreadable: as if there were none.
        XCTAssertEqual(LatencyTrims.load(Data("junk".utf8), legacy: 50).of(airpods), 50)
    }

    func testEditingOneRouteLeavesTheOthersAlone() {
        let trims = LatencyTrims(wirelessDefault: 260).with(airpods, 250).with(.speaker, -30)
        XCTAssertEqual(trims.of(airpods), 250)
        XCTAssertEqual(trims.of(other), 260)
        XCTAssertEqual(trims.of(.speaker), -30)
        XCTAssertEqual(trims.with(other, 9_000).of(other), LatencyTrims.maxMs)
        XCTAssertEqual(trims.with(other, -9_000).of(other), LatencyTrims.minMs)
    }

    func testBluetoothProfilesOfOneDeviceAreOneRoute() {
        let a2dp = OutputRoute.of(portType: "BluetoothA2DPOutput", uid: "aa:bb:cc:dd:ee:01-tacl", name: "AirPods Pro")
        let hfp = OutputRoute.of(portType: "BluetoothHFP", uid: "AA:BB:CC:DD:EE:01-tsco", name: "AirPods Pro")
        XCTAssertEqual(a2dp, airpods)
        XCTAssertEqual(hfp, airpods)
    }

    func testThePhonesOwnOutputsShareOneTrim() {
        let wired = OutputRoute.of(portType: "Headphones", uid: "Wired Headphones", name: "Headphones")
        let usb = OutputRoute.of(portType: "USBAudio", uid: "usb-1", name: "USB-C to 3.5mm")
        for route in [wired, usb, .speaker, OutputRoute.of(portType: "Receiver", uid: "Built-In Receiver", name: "Receiver"),
                      OutputRoute.of(portType: nil, uid: "", name: "")] {
            XCTAssertEqual(route.key, OutputRoute.localKey)
            XCTAssertFalse(route.wireless)
        }
        XCTAssertEqual(OutputRoute.of(portType: "Speaker", uid: "Speaker", name: "Speaker"), .speaker)
    }

    func testAWirelessDeviceWithoutAnAddressIsKeyedByItsUidOrName() {
        XCTAssertEqual(OutputRoute.of(portType: "AirPlay", uid: "4C:57:CA:00:11:22-airplay", name: "TV").key, "4C:57:CA:00:11:22")
        XCTAssertEqual(OutputRoute.of(portType: "CarAudio", uid: "car-7", name: "Car").key, "car-7")
        XCTAssertEqual(OutputRoute.of(portType: "BluetoothLE", uid: "", name: "Cardo").key, "name:Cardo")
    }
}
