import SwiftUI

/// "What's new" once after every update. The notes ship in the app (whatsnew.txt, from notes/<version>.md).
enum WhatsNew {
    struct Item: Identifiable { let id = UUID(); let heading: Bool; let text: String }

    static var thisBuild: String {
        let info = Bundle.main.infoDictionary
        return "\(info?["CFBundleShortVersionString"] as? String ?? "0").\(info?["CFBundleVersion"] as? String ?? "0")"
    }

    static var items: [Item] {
        guard let url = Bundle.main.url(forResource: "whatsnew", withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        let lines = text.components(separatedBy: .newlines)
        let end = lines.firstIndex { $0.trimmingCharacters(in: .whitespaces).hasPrefix("## Install") } ?? lines.count
        return lines[..<end].compactMap { raw in
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty || line.hasPrefix("<!--") || line.hasPrefix("# ") { return nil }
            let clean = { (s: String) in
                s.replacingOccurrences(of: "**", with: "").replacingOccurrences(of: "`", with: "")
                    .replacingOccurrences(of: #"\[(.+?)\]\(.+?\)"#, with: "$1", options: .regularExpression)
            }
            if line.hasPrefix("#") { return Item(heading: true, text: clean(line.trimmingCharacters(in: CharacterSet(charactersIn: "# ")))) }
            if line.hasPrefix("- ") || line.hasPrefix("* ") { return Item(heading: false, text: clean(String(line.dropFirst(2)))) }
            return Item(heading: false, text: clean(line))
        }
    }

    /// True once per update; a fresh install just records the build.
    static func shouldShow(paired: Bool) -> Bool {
        let defaults = UserDefaults.standard
        if defaults.string(forKey: "whatsNewSeen") == thisBuild { return false }
        if !paired { markSeen(); return false }
        return !items.isEmpty
    }

    static func markSeen() { UserDefaults.standard.set(thisBuild, forKey: "whatsNewSeen") }
}

struct WhatsNewView: View {
    @Environment(\.dismiss) private var dismiss
    private let items = WhatsNew.items
    @State private var logoIn = false
    @State private var shown = 0
    @State private var pulse = false
    @State private var burst = false

    var body: some View {
        ZStack {
            ConfettiBurst(active: burst)
                .allowsHitTesting(false)

            VStack(spacing: 18) {
                ZStack {
                    Circle()
                        .fill(Color.pixBlue.opacity(0.25))
                        .frame(width: 140, height: 140)
                        .scaleEffect(pulse ? 1.25 : 0.7)
                        .opacity(pulse ? 0 : 0.8)
                        .animation(.easeOut(duration: 1.6).repeatForever(autoreverses: false), value: pulse)
                    Image("Logo")
                        .resizable()
                        .frame(width: 88, height: 88)
                        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                        .scaleEffect(logoIn ? 1 : 0.3)
                        .rotationEffect(.degrees(logoIn ? 0 : -30))
                }
                .padding(.top, 40)

                VStack(spacing: 4) {
                    Text("What's new").font(.largeTitle.bold())
                    Text("Tethr Link \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")")
                        .foregroundStyle(.secondary)
                }

                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                            Group {
                                if item.heading {
                                    Text(item.text).font(.subheadline.weight(.semibold)).foregroundStyle(Color.pixBlue).padding(.top, 6)
                                } else {
                                    HStack(alignment: .top, spacing: 12) {
                                        Circle().fill(Color.pixBlue).frame(width: 8, height: 8).padding(.top, 7)
                                        Text(item.text).fixedSize(horizontal: false, vertical: true)
                                    }
                                }
                            }
                            .opacity(shown > index ? 1 : 0)
                            .offset(y: shown > index ? 0 : 14)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 24)
                }

                Button {
                    WhatsNew.markSeen()
                    dismiss()
                } label: {
                    Text("Continue").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .padding(.horizontal, 24)
                .padding(.bottom, 16)
                .opacity(shown > items.count ? 1 : 0)
            }
        }
        .interactiveDismissDisabled()
        .onAppear {
            withAnimation(.spring(response: 0.6, dampingFraction: 0.5)) { logoIn = true }
            pulse = true
            burst = true
            for i in 1...(items.count + 1) {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3 + Double(i) * 0.08) {
                    withAnimation(.easeOut(duration: 0.35)) { shown = i }
                }
            }
        }
    }
}

/// A one-off burst of confetti from the logo.
private struct ConfettiBurst: View {
    let active: Bool
    private let pieces: [(angle: Double, distance: CGFloat, color: Color, spin: Double)] = (0..<40).map { i in
        (Double.random(in: 0..<(2 * .pi)), CGFloat.random(in: 140...320),
         [Color.pixBlue, .green, .yellow, .pink, .purple][i % 5], Double.random(in: -540...540))
    }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                ForEach(0..<pieces.count, id: \.self) { i in
                    let p = pieces[i]
                    RoundedRectangle(cornerRadius: 2)
                        .fill(p.color)
                        .frame(width: 7, height: 12)
                        .rotationEffect(.degrees(active ? p.spin : 0))
                        .offset(x: active ? cos(p.angle) * p.distance : 0,
                                y: active ? sin(p.angle) * p.distance + 240 : 0)
                        .opacity(active ? 0 : 1)
                        .animation(.easeOut(duration: 1.8).delay(0.1), value: active)
                }
            }
            .position(x: geo.size.width / 2, y: 110)
        }
    }
}
