#if os(iOS)
import Darwin
import Foundation
import Network

enum NetworkInterfaces {
    /// IPv4 address of the Wi-Fi interface (en0), or of any other up,
    /// non-loopback, non-cellular interface.
    static func wifiIPv4() -> String? {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return nil }
        defer { freeifaddrs(head) }

        var fallback: String?
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let ifa = cursor {
            defer { cursor = ifa.pointee.ifa_next }
            let flags = Int32(bitPattern: ifa.pointee.ifa_flags)
            guard let addr = ifa.pointee.ifa_addr,
                  addr.pointee.sa_family == UInt8(AF_INET),
                  flags & IFF_UP != 0,
                  flags & IFF_LOOPBACK == 0 else { continue }
            let name = String(cString: ifa.pointee.ifa_name)
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let rc = getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count),
                                 nil, 0, NI_NUMERICHOST)
            guard rc == 0 else { continue }
            let ip = host.withUnsafeBufferPointer { String(cString: $0.baseAddress!) }
            if name == "en0" { return ip }
            if fallback == nil, !name.hasPrefix("pdp_ip") { fallback = ip }
        }
        return fallback
    }

    /// Dotted IPv4 / host name of an endpoint's host, without interface suffix.
    static func hostString(_ endpoint: NWEndpoint?) -> String? {
        guard case .hostPort(let host, _)? = endpoint else { return nil }
        return hostString(host)
    }

    static func hostString(_ host: NWEndpoint.Host) -> String? {
        switch host {
        case .ipv4(let a):
            let b = [UInt8](a.rawValue)
            guard b.count == 4 else { return nil }
            return "\(b[0]).\(b[1]).\(b[2]).\(b[3])"
        case .name(let name, _):
            return name
        case .ipv6(let a):
            // Only if the host has no IPv4 (we force IPv4 on connections).
            let s = "\(a)"
            return s.split(separator: "%").first.map(String.init)
        @unknown default:
            return nil
        }
    }

    /// Wi-Fi only: never route LAN traffic to the host over cellular.
    static func lanParameters(_ base: NWParameters) -> NWParameters {
        base.prohibitedInterfaceTypes = [.cellular]
        if let ip = base.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options {
            ip.version = .v4
        }
        return base
    }
}
#endif
