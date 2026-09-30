#if os(iOS)
import MotopartyCore
import SwiftUI
import UIKit

/// Every cover the app shows (rows, the Ride card, the lock screen) comes
/// through here: one memory cache of decoded images, one disk cache, one
/// request per image however many views ask. The covers travel over the
/// Pixel's mobile data, so each is asked for in the size it is shown at
/// (`ArtURL`). Main-queue API.
final class ArtLoader {
    static let shared = ArtLoader()

    private let images = NSCache<NSString, UIImage>()
    private let session: URLSession
    private var waiting: [String: [(UIImage?) -> Void]] = [:]

    private init() {
        images.countLimit = 400
        images.totalCostLimit = 64 << 20
        let config = URLSessionConfiguration.default
        config.urlCache = URLCache(memoryCapacity: 8 << 20, diskCapacity: 128 << 20)
        // A cover never changes under its URL: the disk copy is as good.
        config.requestCachePolicy = .returnCacheDataElseLoad
        config.timeoutIntervalForRequest = 15
        config.waitsForConnectivity = false
        session = URLSession(configuration: config)
    }

    private func key(_ url: String, _ pixels: Int) -> String { ArtURL.sized(url, pixels: pixels) }

    /// The decoded image, if it is already in memory.
    func cached(_ url: String, pixels: Int) -> UIImage? {
        images.object(forKey: key(url, pixels) as NSString)
    }

    /// `completion` runs on the main queue; nil when the image cannot be had.
    func load(_ url: String, pixels: Int, completion: @escaping (UIImage?) -> Void) {
        let key = key(url, pixels)
        if let image = images.object(forKey: key as NSString) { return completion(image) }
        guard let request = URL(string: key) else { return completion(nil) }
        if waiting[key] != nil {
            waiting[key]?.append(completion)
            return
        }
        waiting[key] = [completion]
        session.dataTask(with: request) { [weak self] data, response, _ in
            var image: UIImage?
            let status = (response as? HTTPURLResponse)?.statusCode ?? 200
            if let data, (200..<300).contains(status), let decoded = UIImage(data: data) {
                // Decoded here, not on the main queue at first draw.
                image = decoded.preparingForDisplay() ?? decoded
            }
            DispatchQueue.main.async {
                guard let self else { return }
                if let image {
                    let cost = Int(image.size.width * image.scale * image.size.height * image.scale * 4)
                    self.images.setObject(image, forKey: key as NSString, cost: cost)
                }
                let callbacks = self.waiting.removeValue(forKey: key) ?? []
                for callback in callbacks { callback(image) }
            }
        }.resume()
    }

    @MainActor
    func image(_ url: String, pixels: Int) async -> UIImage? {
        await withCheckedContinuation { continuation in
            load(url, pixels: pixels) { continuation.resume(returning: $0) }
        }
    }
}

/// Cover art from a URL the phone fetches itself (over the host's hotspot),
/// or a music-note tile while it loads or when there is none. A cover that is
/// already in memory shows at once; one that has to load fades in.
struct Artwork: View {
    let url: String?
    let size: CGFloat
    var cornerRadius: CGFloat?

    @Environment(\.displayScale) private var displayScale
    @State private var image: UIImage?
    /// The URL `image` belongs to.
    @State private var imageURL: String?

    var body: some View {
        ZStack {
            Rectangle().fill(Color.primary.opacity(0.08))
            Image(systemName: "music.note")
                .font(.system(size: size * 0.4, weight: .semibold))
                .foregroundStyle(.secondary)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: size, height: size)
                    .id(imageURL)
                    .transition(.opacity)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius ?? max(6, size * 0.1), style: .continuous))
        .accessibilityHidden(true)
        .task(id: url) { await load() }
    }

    @MainActor
    private func load() async {
        guard let url, !url.isEmpty else {
            image = nil
            imageURL = nil
            return
        }
        let pixels = ArtURL.pixels(points: Double(size), scale: Double(displayScale))
        if let hit = ArtLoader.shared.cached(url, pixels: pixels) {
            image = hit
            imageURL = url
            return
        }
        // The cover of the track before stays until this one is here (the
        // Ride card cross-fades); a row that never had one shows the tile.
        let loaded = await ArtLoader.shared.image(url, pixels: pixels)
        guard !Task.isCancelled else { return }
        withAnimation(.easeOut(duration: 0.25)) {
            image = loaded
            imageURL = loaded == nil ? nil : url
        }
    }
}

/// The cover, blurred and dimmed, as a card's background.
struct ArtBackdrop: View {
    let url: String?

    @State private var image: UIImage?

    var body: some View {
        // An overlay, so the filling image never widens the card.
        Brand.card
            .overlay {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .blur(radius: 36)
                        .opacity(0.4)
                        .transition(.opacity)
                }
            }
            .clipped()
            .accessibilityHidden(true)
        .task(id: url) {
            guard let url, !url.isEmpty else {
                image = nil
                return
            }
            // The smallest size: it is blurred anyway, and the queue rows use it too.
            let loaded = await ArtLoader.shared.image(url, pixels: ArtURL.buckets[0])
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.4)) { image = loaded }
        }
    }
}
#endif
