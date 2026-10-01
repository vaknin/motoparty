#if os(iOS)
import SwiftUI

/// The colours both phones share (Android's `Palette`, `ui/Theme.kt`).
enum Brand {
    /// `#FF7A2F`: the app tint and the TALK button.
    static let orange = Color(red: 1, green: 0x7A / 255.0, blue: 0x2F / 255.0)
    /// Text and glyphs on the orange and the amber.
    static let onOrange = Color(red: 0x1F / 255.0, green: 0x10 / 255.0, blue: 0x04 / 255.0)
    /// `#E5484D`: a live talk.
    static let live = Color(red: 0xE5 / 255.0, green: 0x48 / 255.0, blue: 0x4D / 255.0)
    /// `#FBBF24`: waiting (the link being looked for, a talk connecting).
    static let waiting = Color(red: 0xFB / 255.0, green: 0xBF / 255.0, blue: 0x24 / 255.0)
    /// `#4ADE80`: linked.
    static let good = Color(red: 0x4A / 255.0, green: 0xDE / 255.0, blue: 0x80 / 255.0)
    /// Cards on the black background.
    static let card = Color(red: 0x18 / 255.0, green: 0x1B / 255.0, blue: 0x20 / 255.0)
}

/// A row that is a button: dims and lights up while pressed, which `.plain`
/// does not show (a glove gets no other sign the touch landed).
struct RowButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.55 : 1)
            .background(Color.primary.opacity(configuration.isPressed ? 0.08 : 0),
                        in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// A bare glyph that is a button (transport, ✕): shrinks a little and dims
/// while pressed.
struct GlyphButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(isEnabled ? (configuration.isPressed ? 0.5 : 1) : 0.35)
            .scaleEffect(configuration.isPressed ? 0.9 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// A line at the bottom of a tab, Android's snackbar: "Removed: <title>"
/// with Undo on the Queue tab, "Added to queue: <title>" (no action) after
/// a touch enqueue anywhere.
struct Banner: View {
    let text: String
    var actionTitle: String?
    var action: () -> Void = {}

    var body: some View {
        HStack(spacing: 8) {
            Text(text)
                .font(.subheadline)
                .lineLimit(2)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            if let actionTitle {
                Button(action: action) {
                    Text(actionTitle)
                        .font(.body.weight(.semibold))
                        .frame(minWidth: 64, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(GlyphButtonStyle())
                .foregroundStyle(Brand.orange)
            }
        }
        .padding(.leading, 16)
        .padding(.trailing, actionTitle == nil ? 16 : 6)
        .padding(.vertical, 4)
        .background(Brand.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.white.opacity(0.08)))
        .shadow(color: .black.opacity(0.4), radius: 8, y: 2)
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
        .accessibilityElement(children: .contain)
    }
}

/// Lays its subviews out in rows, wrapping to the next row when one is full:
/// the command chips. Never wider than the width it is given.
struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(subviews, width: proposal.width ?? .infinity).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let arranged = arrange(subviews, width: bounds.width)
        for (subview, frame) in zip(subviews, arranged.frames) {
            subview.place(at: CGPoint(x: bounds.minX + frame.minX, y: bounds.minY + frame.minY),
                          proposal: ProposedViewSize(frame.size))
        }
    }

    private func arrange(_ subviews: Subviews, width: CGFloat) -> (size: CGSize, frames: [CGRect]) {
        var frames: [CGRect] = []
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var widest: CGFloat = 0
        for subview in subviews {
            // A chip wider than the row is squeezed into it (its text truncates).
            var size = subview.sizeThatFits(.unspecified)
            if size.width > width { size = subview.sizeThatFits(ProposedViewSize(width: width, height: nil)) }
            if x > 0, x + size.width > width {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            frames.append(CGRect(origin: CGPoint(x: x, y: y), size: size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            widest = max(widest, x - spacing)
        }
        return (CGSize(width: widest, height: y + rowHeight), frames)
    }
}
#endif
