#if os(iOS)
import os

/// `idevicesyslog | grep motoparty` shows these on Linux.
enum Log {
    static let link = Logger(subsystem: "motoparty", category: "link")
    static let audio = Logger(subsystem: "motoparty", category: "audio")
    static let music = Logger(subsystem: "motoparty", category: "music")
    static let voice = Logger(subsystem: "motoparty", category: "voice")
    static let app = Logger(subsystem: "motoparty", category: "app")
}
#endif
