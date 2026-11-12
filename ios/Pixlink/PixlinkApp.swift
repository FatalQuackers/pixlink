import SwiftUI

@main
struct PixlinkApp: App {
    @StateObject private var link = LinkClient.shared
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(link)
                .tint(.pixBlue)
                // Scanning the PC's QR code with the iPhone camera opens Pixlink straight into pairing.
                .onOpenURL { url in PendingPairing.shared.info = PairingInfo(url.absoluteString) }
        }
        .onChange(of: phase) { newPhase in
            if newPhase == .active { link.resume() }
        }
    }
}

/// A pairing link opened from outside the app (camera scan), picked up by the pairing screen.
@MainActor
final class PendingPairing: ObservableObject {
    static let shared = PendingPairing()
    @Published var info: PairingInfo?
}

extension Color {
    static let pixBlue = Color(red: 61 / 255, green: 123 / 255, blue: 1)
}

struct ContentView: View {
    @EnvironmentObject private var link: LinkClient

    var body: some View {
        Group {
            if link.pc == nil {
                PairView()
            } else {
                HomeView()
            }
        }
        .animation(.easeInOut(duration: 0.3), value: link.pc == nil)
    }
}
