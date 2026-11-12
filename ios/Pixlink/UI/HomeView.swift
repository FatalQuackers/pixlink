import PhotosUI
import ReplayKit
import SwiftUI
import UniformTypeIdentifiers

struct HomeView: View {
    @EnvironmentObject private var link: LinkClient
    @State private var pickingPhotos = false
    @State private var pickingFiles = false
    @State private var confirmForget = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    StatusCard()

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

                    LimitsCard()
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

private struct LimitsCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("What works on iPhone", systemImage: "info.circle").font(.subheadline.weight(.semibold))
            Text("Photos, files, clipboard, screen casting and Find my phone. Pixlink stays connected while it's open. iOS pauses it soon after you leave the app. Apple doesn't let apps share notifications, texts or calls, or let your PC control the screen.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
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
