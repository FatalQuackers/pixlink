import SwiftUI

struct PairView: View {
    @EnvironmentObject private var link: LinkClient
    @ObservedObject private var pending = PendingPairing.shared
    @State private var scanning = false
    @State private var pasting = false
    @State private var pastedText = ""
    @State private var working = false
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                Image("Logo")
                    .resizable()
                    .frame(width: 72, height: 72)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .padding(.top, 40)

                VStack(alignment: .leading, spacing: 10) {
                    Text("Link your iPhone to your PC")
                        .font(.largeTitle.bold())
                    Text("Send photos, files and your clipboard, and cast your screen to your PC. Everything stays on your Wi-Fi.")
                        .font(.body)
                        .foregroundStyle(.secondary)
                }

                VStack(alignment: .leading, spacing: 16) {
                    Step(number: 1, text: "Open Pixlink on your Windows PC")
                    Step(number: 2, text: "Connect your iPhone and PC to the same Wi-Fi")
                    Step(number: 3, text: "Scan the QR code on your PC")
                }

                if let error {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.callout)
                        .foregroundStyle(.red)
                        .fixedSize(horizontal: false, vertical: true)
                }

                VStack(spacing: 12) {
                    Button {
                        error = nil
                        scanning = true
                    } label: {
                        HStack {
                            if working { ProgressView().tint(.white) }
                            Text(working ? "Linking…" : "Scan QR code")
                        }
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(working)

                    Button("Paste the pairing link instead") { pasting = true }
                        .font(.subheadline)
                        .disabled(working)
                }

                Text("The 5-digit code works on Pixel phones only. On iPhone, use the QR code.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .padding(24)
        }
        .sheet(isPresented: $scanning) {
            ScannerSheet { code in
                scanning = false
                if let info = PairingInfo(code) { start(info) } else { error = "That isn't a Pixlink code. Scan the QR code on Pixlink's pairing screen on your PC." }
            }
        }
        .alert("Paste pairing link", isPresented: $pasting) {
            TextField("pixlink://pair?…", text: $pastedText)
            Button("Link") {
                if let info = PairingInfo(pastedText) { start(info) } else { error = "That link isn't a Pixlink pairing link." }
            }
            Button("Cancel", role: .cancel) {}
        }
        .onChange(of: pending.info) { info in
            if let info { pending.info = nil; start(info) }
        }
        .onAppear {
            if let info = pending.info { pending.info = nil; start(info) }
        }
    }

    private func start(_ info: PairingInfo) {
        working = true
        error = nil
        Task {
            do { try await link.pair(info) }
            catch { self.error = error.localizedDescription }
            working = false
        }
    }
}

private struct Step: View {
    let number: Int
    let text: String

    var body: some View {
        HStack(spacing: 14) {
            Text("\(number)")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white)
                .frame(width: 30, height: 30)
                .background(Circle().fill(Color.pixBlue))
            Text(text)
        }
    }
}

private struct ScannerSheet: View {
    let onCode: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            QRScannerView(onCode: onCode)
                .ignoresSafeArea()
                .overlay(alignment: .bottom) {
                    Text("Point your camera at the QR code on your PC")
                        .font(.callout.weight(.medium))
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(.ultraThinMaterial, in: Capsule())
                        .padding(.bottom, 40)
                }
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                }
        }
    }
}
