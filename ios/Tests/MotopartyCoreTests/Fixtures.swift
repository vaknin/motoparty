import Foundation
import XCTest

/// Loads the shared protocol vectors from <repo>/fixtures, located relative to
/// this source file so the tests work from any working directory.
enum Fixtures {
    static let root: URL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // MotopartyCoreTests
        .deletingLastPathComponent() // Tests
        .deletingLastPathComponent() // ios
        .deletingLastPathComponent() // repo
        .appendingPathComponent("fixtures", isDirectory: true)

    static func json(_ relativePath: String) throws -> [String: Any] {
        let url = root.appendingPathComponent(relativePath)
        let data = try Data(contentsOf: url)
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw NSError(domain: "Fixtures", code: 1, userInfo: [NSLocalizedDescriptionKey: "\(relativePath) is not an object"])
        }
        return obj
    }

    /// Canonical text of a JSON value (sorted keys) for order-insensitive
    /// comparison that still distinguishes 0 / 0.0 / false.
    static func canonical(_ value: Any) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
        return String(decoding: data, as: UTF8.self)
    }

    static func canonical(jsonData: Data) throws -> String {
        try canonical(JSONSerialization.jsonObject(with: jsonData))
    }
}

extension Data {
    init(hex: String) {
        var bytes: [UInt8] = []
        var chars = Array(hex.utf8)
        if chars.count % 2 == 1 { chars.insert(UInt8(ascii: "0"), at: 0) }
        var i = 0
        while i < chars.count {
            bytes.append(UInt8(String(decoding: chars[i..<i + 2], as: UTF8.self), radix: 16)!)
            i += 2
        }
        self.init(bytes)
    }

    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
