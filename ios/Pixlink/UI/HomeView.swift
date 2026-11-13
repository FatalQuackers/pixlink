import PhotosUI
import ReplayKit
import SwiftUI
import UniformTypeIdentifiers

struct HomeView: View {
    @EnvironmentObject private var link: LinkClient
    @State private var pickingPhotos = false
    @State private var pickingFiles = false
    @State private var confirmForget = false
    @State private var showingFeatures = false
    @State private var showingWhatsNew = false
    @State private var ringingPC = false
    @AppStorage("seenFeatures") private var seenFeatures = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    StatusCard()

                    ExpiryCard()

                    NotificationsCard()

                    CastCard()

                    VStack(spacing: 12) {
                        HStack(spacing: 12) {
                            ActionTile(title: "Send photos", icon: "photo.on.rectangle", disabled: !link.isConnected) { pickingPhotos = true }
                            ActionTile(title: "Send files", icon: "doc", disabled: !link.isConnected) { pickingFiles = true }
                        }
                        HStack(spacing: 12) {
                            ActionTile(title: "Send clipboard", icon: "doc.on.clipboard", disabled: !link.isConnected) { link.sendClipboard() }
                            ActionTile(title: "Received files", icon: "folder", disabled: false) { openReceivedFolder() }
                        }
                        HStack(spacing: 12) {
                            ActionTile(title: ringingPC ? "Stop ringing" : "Find my PC", icon: "bell.and.waves.left.and.right", disabled: !link.isConnected) {
                                ringingPC.toggle()
                                link.send(Envelope("pc.ring", ["ring": ringingPC]))
                                link.activity = ringingPC ? "Ringing \(link.pc?.name ?? "your PC")…" : nil
                            }
                            ActionTile(title: "Lock PC", icon: "lock", disabled: !link.isConnected) {
                                link.send(Envelope("pc.lock"))
                                link.activity = "Locked \(link.pc?.name ?? "your PC")"
                            }
                        }
                    }

                    if let activity = link.activity {
                        HStack(spacing: 10) {
                            if link.sending { ProgressView() } else { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
                            Text(activity).font(.callout)
                            Spacer()
                        }
                        .padding(14)
                        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color(.secondarySystemBackground)))
                        .transition(.opacity)
                    }

                    Button { showingFeatures = true } label: {
                        Label("What works on iPhone", systemImage: "info.circle")
                            .font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                    }
                    .buttonStyle(.bordered)
                }
                .padding(16)
                .animation(.default, value: link.activity)
            }
            .navigationTitle("Pixlink")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button(role: .destructive) { confirmForget = true } label: { Label("Forget this PC", systemImage: "xmark.circle") }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
            .confirmationDialog("Forget \(link.pc?.name ?? "this PC")?", isPresented: $confirmForget, titleVisibility: .visible) {
                Button("Forget PC", role: .destructive) { link.forget() }
            } message: {
                Text("You'll need to scan the QR code again to link them. On the PC, unpair the phone in Settings too.")
            }
            .sheet(isPresented: $pickingPhotos) {
                PhotoPicker { urls in Task { await link.sendFiles(urls) } }
                    .ignoresSafeArea()
            }
            .sheet(isPresented: $showingFeatures) { FeaturesSheet() }
            .sheet(isPresented: $showingWhatsNew) { WhatsNewView() }
            .onAppear {
                if WhatsNew.shouldShow(paired: true) && seenFeatures { showingWhatsNew = true }
                else if !seenFeatures { seenFeatures = true; WhatsNew.markSeen(); showingFeatures = true }
            }
            .fileImporter(isPresented: $pickingFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
                if case let .success(urls) = result { Task { await link.sendFiles(urls) } }
            }
        }
    }

    private func openReceivedFolder() {
        // Opens Pixlink's folder in the Files app.
        let path = FileBridge.root.path
        if let url = URL(string: "shareddocuments://\(path)") { UIApplication.shared.open(url) }
    }
}

private struct StatusCard: View {
    @EnvironmentObject private var link: LinkClient

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle().fill(color.opacity(0.15)).frame(width: 48, height: 48)
                Image(systemName: "desktopcomputer").font(.title3).foregroundStyle(color)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(link.pc?.name ?? "Your PC").font(.headline)
                HStack(spacing: 6) {
                    Circle().fill(color).frame(width: 8, height: 8)
                    Text(title).font(.subheadline).foregroundStyle(.secondary)
                }
                if case let .offline(reason) = link.state {
                    Text(reason).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer()
            if case .offline = link.state {
                Button("Retry") { link.connect() }.buttonStyle(.bordered)
            } else if link.state == .connecting {
                ProgressView()
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color(.secondarySystemBackground)))
    }

    private var title: String {
        switch link.state {
        case .connected: return "Connected"
        case .connecting: return "Connecting…"
        case .notPaired: return "Not linked"
        case .offline: return "Not connected"
        }
    }

    private var color: Color {
        switch link.state {
        case .connected: return .green
        case .connecting: return .orange
        default: return .red
        }
    }
}

/// Screen casting. iOS only lets the person start a broadcast, through Apple's own button.
private struct CastCard: View {
    var body: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Cast screen").font(.headline)
                Text("Show your iPhone's screen on your PC. Tap the button, then Start Broadcast.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            BroadcastButton()
                .frame(width: 56, height: 56)
                .background(Circle().fill(Color.pixBlue))
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color(.secondarySystemBackground)))
    }
}

/// Apple's broadcast picker, preset to Pixlink's casting extension.
private struct BroadcastButton: UIViewRepresentable {
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let picker = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 56, height: 56))
        picker.preferredExtension = (Bundle.main.bundleIdentifier ?? "app.pixlink") + ".broadcast"
        picker.showsMicrophoneButton = false
        for case let button as UIButton in picker.subviews {
            button.imageView?.tintColor = .white
            button.tintColor = .white
        }
        return picker
    }

    func updateUIView(_ view: RPSystemBroadcastPickerView, context: Context) {}
}

private struct ActionTile: View {
    let title: String
    let icon: String
    let disabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 12) {
                Image(systemName: icon).font(.title2).foregroundStyle(Color.pixBlue)
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
            }
            .frame(maxWidth: .infinity, minHeight: 84, alignment: .leading)
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color(.secondarySystemBackground)))
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .opacity(disabled ? 0.45 : 1)
    }
}

/// Notifications over Bluetooth: the PC reads them with Apple's Notification Center Service.
private struct NotificationsCard: View {
    @EnvironmentObject private var bluetooth: BluetoothLink
    @State private var showingHelp = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 14) {
                ZStack {
                    Circle().fill(Color.pixBlue.opacity(0.15)).frame(width: 44, height: 44)
                    Image(systemName: "bell.badge").foregroundStyle(Color.pixBlue)
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text("Notifications on your PC").font(.headline)
                    Text(subtitle).font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
            }
            switch bluetooth.state {
            case .off:
                Button("Set up notifications") { bluetooth.start(); showingHelp = true }
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity, alignment: .leading)
            case .bluetoothOff:
                Text("Turn on Bluetooth in Control Center.").font(.footnote).foregroundStyle(.secondary)
            case .notAllowed:
                Button("Allow Bluetooth in Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
                .buttonStyle(.bordered)
            case .connected:
                Button("Notifications not showing?") { showingHelp = true }.font(.footnote)
            default:
                Button("How it works") { showingHelp = true }.font(.footnote)
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color(.secondarySystemBackground)))
        .sheet(isPresented: $showingHelp) { NotificationHelp() }
    }

    private var subtitle: String {
        switch bluetooth.state {
        case .off: return "See notifications and answer or decline calls on your PC. Uses Bluetooth, works even when Pixlink is closed."
        case .bluetoothOff: return "Bluetooth is off."
        case .notAllowed: return "Pixlink needs Bluetooth permission for this."
        case .searching: return "Looking for your PC… Keep Pixlink open on the PC, with Bluetooth on."
        case .connecting: return "Connecting over Bluetooth…"
        case .connected: return "On. Your PC shows your notifications, even when Pixlink is closed."
        case .wrongPC: return "Found a different PC."
        }
    }
}

private struct NotificationHelp: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Setting up") {
                    HelpRow(n: 1, text: "Keep Pixlink open on your PC, with Bluetooth on.")
                    HelpRow(n: 2, text: "When your iPhone asks to pair with your PC, tap Pair. Windows may also ask: accept there too.")
                    HelpRow(n: 3, text: "If your iPhone asks to allow your PC to receive notifications, tap Allow.")
                }
                Section("Notifications not showing?") {
                    Text("On your iPhone, open Settings → Bluetooth, tap (i) next to your PC and turn on Share System Notifications.")
                    Text("Notifications only appear on your PC if they're allowed to show on your iPhone's lock screen (Settings → Notifications).")
                    Text("Still nothing? In Settings → Bluetooth, tap (i) → Forget This Device, do the same for the iPhone in Windows Bluetooth settings, then tap Set up notifications again.")
                }
                Section("What you get") {
                    Text("Every notification on your PC, Clear to remove it from your iPhone, and Answer or Decline for calls. Call audio stays on the iPhone, and you can't reply from the PC: Apple doesn't allow either.")
                }
            }
            .navigationTitle("Notifications on your PC")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

private struct HelpRow: View {
    let n: Int
    let text: String
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(n)").font(.footnote.weight(.bold)).foregroundStyle(.white)
                .frame(width: 22, height: 22).background(Circle().fill(Color.pixBlue))
            Text(text)
        }
    }
}

/// Free Apple ID installs stop opening after 7 days: say so before it happens.
private struct ExpiryCard: View {
    var body: some View {
        if let days = InstallInfo.daysLeft, days <= 3 {
            VStack(alignment: .leading, spacing: 6) {
                Label(days == 0 ? "Pixlink expires today" : "Pixlink expires in \(days) day\(days == 1 ? "" : "s")",
                      systemImage: "clock.badge.exclamationmark")
                    .font(.headline)
                    .foregroundStyle(.orange)
                Text("Apps installed with a free Apple ID stop opening after 7 days. Refresh it in AltStore or SideStore (they can do it automatically), or reinstall it with Sideloadly. Your PC link is kept.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color.orange.opacity(0.12)))
        }
    }
}

/// What Pixlink can and can't do on iPhone, and how each limit is worked around.
struct FeaturesSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Feature(icon: "photo.on.rectangle", color: .green, title: "Photos and files", detail: "Send them both ways. Your PC can browse your photos.")
                    Feature(icon: "doc.on.clipboard", color: .green, title: "Clipboard", detail: "Send it with one tap. Text copied on your PC can be sent to your iPhone.")
                    Feature(icon: "rectangle.on.rectangle", color: .green, title: "Screen casting", detail: "Show your screen on your PC. View only: Apple doesn't let other devices control an iPhone.")
                    Feature(icon: "bell.badge", color: .green, title: "Notifications and call alerts", detail: "Over Bluetooth, like Phone Link. Clear them or answer and decline calls from your PC.")
                    Feature(icon: "battery.75", color: .green, title: "Battery and Find my phone", detail: "Battery level shows on your PC. Find my phone rings while Pixlink is open.")
                } header: { Text("Works on iPhone") }

                Section {
                    Feature(icon: "moon.zzz", color: .orange, title: "Running in the background", detail: "iOS pauses apps soon after you leave them. Notifications and battery keep working over Bluetooth. Photos, files and clipboard need Pixlink open.")
                    Feature(icon: "clock", color: .orange, title: "Free installs last 7 days", detail: "With a free Apple ID, refresh Pixlink weekly. AltStore or SideStore can do it automatically. Pixlink reminds you the day before.")
                } header: { Text("Works, with a catch") }

                Section {
                    Feature(icon: "text.bubble", color: .red, title: "Replying and text history", detail: "Apple doesn't give other devices your messages or let them reply.")
                    Feature(icon: "phone", color: .red, title: "Call audio on your PC", detail: "You can answer from your PC, then talk on your iPhone.")
                    Feature(icon: "cursorarrow.click", color: .red, title: "Controlling your iPhone", detail: "Not allowed on iPhone. Casting is view only.")
                    Feature(icon: "personalhotspot", color: .red, title: "Hotspot switch", detail: "Turn on Personal Hotspot on the iPhone. Your PC joins it like any Wi-Fi.")
                } header: { Text("Not possible on iPhone") } footer: {
                    Text("These are Apple rules, not Pixlink ones. A Pixel gets everything.")
                }
            }
            .navigationTitle("Pixlink on iPhone")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Got it") { dismiss() } } }
        }
    }
}

private struct Feature: View {
    let icon: String
    let color: Color
    let title: String
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon).foregroundStyle(color).frame(width: 26)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(detail).font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 2)
    }
}

/// Picks photos and videos and hands back copies as files (originals, with their real names).
private struct PhotoPicker: UIViewControllerRepresentable {
    let onPicked: ([URL]) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration(photoLibrary: .shared())
        config.selectionLimit = 0
        config.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: PHPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPicked: onPicked) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let onPicked: ([URL]) -> Void
        init(onPicked: @escaping ([URL]) -> Void) { self.onPicked = onPicked }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            guard !results.isEmpty else { return }
            let group = DispatchGroup()
            var urls: [URL] = []
            let lock = NSLock()
            let folder = FileManager.default.temporaryDirectory.appendingPathComponent("send", isDirectory: true)
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            for result in results {
                let provider = result.itemProvider
                guard let type = provider.registeredTypeIdentifiers.first else { continue }
                group.enter()
                provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
                    defer { group.leave() }
                    guard let url else { return }
                    // The file is deleted when this returns, so keep a copy.
                    let copy = FileBridge.uniqueURL(folder.appendingPathComponent(url.lastPathComponent))
                    if (try? FileManager.default.copyItem(at: url, to: copy)) != nil {
                        lock.lock(); urls.append(copy); lock.unlock()
                    }
                }
            }
            group.notify(queue: .main) { self.onPicked(urls) }
        }
    }
}
