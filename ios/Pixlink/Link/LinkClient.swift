import AudioToolbox
import Foundation
import Network
import UIKit

struct PixlinkError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// The connection to the paired PC (port 47100, TLS, newline-delimited JSON), the same protocol
/// the Android app speaks. iOS pauses apps soon after they leave the screen, so the link is live
/// while Pixlink is open and reconnects as soon as it's opened again.
@MainActor
final class LinkClient: ObservableObject {
    static let shared = LinkClient()

    enum State: Equatable {
        case notPaired
        case connecting
        case connected
        case offline(String)
    }

    @Published private(set) var state: State
    @Published private(set) var pc: PairedPC?
    /// A short line about the last thing sent or received ("Sent 3 photos").
    @Published var activity: String?
    @Published var sending = false

    private var conn: NWConnection?
    private var buffer = Data()
    private let queue = DispatchQueue(label: "pixlink.link")
    private var pingTimer: Timer?
    private var retryTask: Task<Void, Never>?
    private var retryDelay: Double = 1
    private var pendingPair: PairingInfo?
    private var pairContinuation: CheckedContinuation<Void, Error>?

    private let photos = PhotoBridge()
    private let files = FileBridge()
    private static let chunkSize = 256 * 1024

    private init() {
        pc = SharedStore.pc
        state = SharedStore.pc == nil ? .notPaired : .offline("Not connected yet")
        UIDevice.current.isBatteryMonitoringEnabled = true
        NotificationCenter.default.addObserver(forName: UIDevice.batteryLevelDidChangeNotification, object: nil, queue: .main) { _ in
            Task { @MainActor in LinkClient.shared.sendStatus() }
        }
    }

    var isConnected: Bool { state == .connected }

    // MARK: - Pairing

    func pair(_ info: PairingInfo) async throws {
        retryTask?.cancel()
        close()
        state = .connecting
        pendingPair = info
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            pairContinuation = cont
            // No certificate yet: the PC accepts that only while its pairing screen is open,
            // and gives this iPhone its own certificate in pair.accept.
            open(host: info.host, port: info.port, pin: info.fingerprint, identity: nil) { [weak self] in
                self?.send(Envelope("pair.request", [
                    "token": info.token,
                    "phoneName": UIDevice.current.name,
                    "model": Device.model,
                    "iosVersion": UIDevice.current.systemVersion,
                    "platform": "ios",
                    "needsIdentity": true,
                ]))
            }
        }
    }

    func forget() {
        retryTask?.cancel()
        close()
        SharedStore.forget()
        pc = nil
        state = .notPaired
    }

    // MARK: - Connection

    func connect() {
        guard let pc = SharedStore.pc else { state = .notPaired; return }
        if state == .connected || state == .connecting { return }
        guard let identity = SharedStore.loadIdentity() else {
            state = .offline("This iPhone's key is missing. Forget the PC and pair again.")
            return
        }
        retryTask?.cancel()
        state = .connecting
        open(host: pc.host, port: pc.port, pin: pc.fingerprint, identity: identity) { [weak self] in self?.onReady() }
    }

    /// Called when the app comes back on screen.
    func resume() {
        retryDelay = 1
        if case .offline = state { connect() }
        else if state == .connected { sendStatus() }
    }

    private func open(host: String, port: Int, pin: String, identity: SecIdentity?, ready: @escaping @MainActor () -> Void) {
        conn?.cancel()
        buffer = Data()
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(clamping: port)) else { return }
        let c = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: PixTLS.parameters(pin: pin, identity: identity))
        conn = c
        c.stateUpdateHandler = { [weak self] st in
            Task { @MainActor in
                guard let self, self.conn === c else { return }
                switch st {
                case .ready:
                    ready()
                    self.receive(c)
                case .failed(let error):
                    self.dropped(c, reason: Self.describe(error))
                case .waiting(let error):
                    self.dropped(c, reason: Self.describe(error))
                default:
                    break
                }
            }
        }
        c.start(queue: queue)
    }

    private static func describe(_ error: NWError) -> String {
        switch error {
        case .tls: return "Your PC didn't accept this iPhone. Forget the PC and pair again."
        case .posix(let code) where code == .ECONNREFUSED: return "Pixlink isn't running on your PC."
        default: return "Can't reach your PC. Check both are on the same Wi-Fi and Pixlink is open on the PC."
        }
    }

    private func receive(_ c: NWConnection) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { [weak self] data, _, done, error in
            Task { @MainActor in
                guard let self, self.conn === c else { return }
                if let data, !data.isEmpty {
                    self.buffer.append(data)
                    self.drainLines()
                }
                if done || error != nil {
                    self.dropped(c, reason: "Your PC closed the connection.")
                    return
                }
                self.receive(c)
            }
        }
    }

    private func drainLines() {
        while let nl = buffer.firstIndex(of: 0x0A) {
            let line = buffer.subdata(in: buffer.startIndex..<nl)
            buffer.removeSubrange(buffer.startIndex...nl)
            if let m = Envelope.parse(line) { handle(m) }
        }
    }

    private func onReady() {
        state = .connected
        retryDelay = 1
        send(Envelope("hello", [
            "phoneName": UIDevice.current.name,
            "model": Device.model,
            "appVersion": Device.appVersion,
            "powerMode": false,
            "canControl": false,
            "platform": "ios",
            "via": "wifi",
        ]))
        sendStatus()
        pingTimer?.invalidate()
        pingTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { _ in
            Task { @MainActor in LinkClient.shared.send(Envelope("ping")) }
        }
    }

    private func dropped(_ c: NWConnection, reason: String) {
        c.cancel()
        if conn === c { conn = nil }
        pingTimer?.invalidate()
        if let cont = pairContinuation {
            pairContinuation = nil
            pendingPair = nil
            state = SharedStore.pc == nil ? .notPaired : .offline(reason)
            cont.resume(throwing: PixlinkError(reason))
            return
        }
        guard SharedStore.pc != nil else { state = .notPaired; return }
        state = .offline(reason)
        scheduleRetry()
    }

    private func scheduleRetry() {
        retryTask?.cancel()
        retryTask = Task { [weak self] in
            guard let self else { return }
            // The PC may have a new address (new Wi-Fi, or it joined a hotspot): look for it.
            if let pc = SharedStore.pc, let host = await PCFinder.find(pcId: pc.pcId), host != pc.host {
                var moved = pc
                moved.host = host
                SharedStore.pc = moved
                self.pc = moved
            }
            try? await Task.sleep(nanoseconds: UInt64(self.retryDelay * 1_000_000_000))
            guard !Task.isCancelled, UIApplication.shared.applicationState != .background else { return }
            self.retryDelay = min(self.retryDelay * 2, 30)
            self.connect()
        }
    }

    private func close() {
        pingTimer?.invalidate()
        conn?.cancel()
        conn = nil
    }

    func send(_ m: Envelope) {
        conn?.send(content: m.line(), completion: .contentProcessed { _ in })
    }

    /// Sends and waits until the data has been handed to the network (so big files don't pile up in memory).
    private func sendAndWait(_ m: Envelope) async {
        guard let c = conn else { return }
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            c.send(content: m.line(), completion: .contentProcessed { _ in cont.resume() })
        }
    }

    func sendStatus() {
        guard state == .connected else { return }
        let device = UIDevice.current
        let level = device.batteryLevel
        send(Envelope("status", [
            "battery": level < 0 ? -1 : Int((level * 100).rounded()),
            "charging": device.batteryState == .charging || device.batteryState == .full,
            "dnd": false,
        ]))
    }

    // MARK: - Messages from the PC

    private func handle(_ m: Envelope) {
        switch m.t {
        case "pair.accept":
            guard let info = pendingPair,
                  let p12 = Data(base64Encoded: m.str("identityP12") ?? ""),
                  let password = m.str("identityPassword") else {
                failPairing("Your PC needs the latest Pixlink to link an iPhone. Update it and try again.")
                return
            }
            SharedStore.saveIdentity(p12: p12, password: password)
            let paired = PairedPC(host: info.host, port: info.port, fingerprint: info.fingerprint,
                                  name: m.str("pcName") ?? info.name, pcId: m.str("pcId") ?? info.fingerprint)
            SharedStore.pc = paired
            pc = paired
            let cont = pairContinuation
            pairContinuation = nil
            pendingPair = nil
            close()
            state = .offline("Connecting…")
            cont?.resume()
            connect() // now with the new certificate
        case "pair.reject":
            failPairing(m.str("reason") ?? "Your PC said no. Try again.")
        case "ping":
            send(m.reply("pong"))
        case "photos.list":
            Task { self.send(await self.photos.list(m)) }
        case "photos.thumb":
            Task { self.send(await self.photos.thumb(m)) }
        case "file.get":
            Task { await self.sendRequestedFile(m) }
        case "files.roots":
            send(files.roots(m))
        case "files.list":
            send(files.list(m))
        case "file.put":
            files.begin(m)
        case "file.chunk":
            if let name = files.chunk(m) { activity = "Received \(name). It's in Files → On My iPhone → Pixlink." }
        case "clipboard.set":
            if let text = m.str("text") {
                UIPasteboard.general.string = text
                activity = "Copied from your PC"
            }
        case "phone.ring":
            if m.bool("ring") ?? true { Ringer.ring() } else { Ringer.stop() }
        case "mirror.start":
            // iOS only lets the person start screen broadcasting, so the PC can't start it.
            send(m.reply("mirror.stop"))
        default:
            break
        }
    }

    private func failPairing(_ reason: String) {
        let cont = pairContinuation
        pairContinuation = nil
        pendingPair = nil
        close()
        state = SharedStore.pc == nil ? .notPaired : .offline(reason)
        cont?.resume(throwing: PixlinkError(reason))
    }

    // MARK: - Sending to the PC

    func sendClipboard() {
        guard let text = UIPasteboard.general.string, !text.isEmpty else {
            activity = "Your clipboard is empty (or holds a picture, which isn't supported yet)."
            return
        }
        send(Envelope("clipboard.text", ["text": text, "auto": false]))
        activity = "Clipboard sent to \(pc?.name ?? "your PC")"
    }

    /// Sends files to the PC (saved in Downloads\Pixlink there).
    func sendFiles(_ urls: [URL]) async {
        guard isConnected, !urls.isEmpty else { return }
        sending = true
        defer { sending = false }
        var sent = 0
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let handle = try? FileHandle(forReadingFrom: url) else { continue }
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? -1
            activity = "Sending \(url.lastPathComponent)…"
            await stream(handle: handle, name: url.lastPathComponent, size: size, transferId: Self.newId())
            try? handle.close()
            sent += 1
        }
        activity = sent == 1 ? "Sent \(urls[0].lastPathComponent)" : "Sent \(sent) files"
    }

    private func sendRequestedFile(_ m: Envelope) async {
        let transferId = m.id ?? Self.newId()
        if let photoId = m.int("photoId") {
            guard let photo = await photos.original(id: photoId) else { return }
            await stream(data: photo.data, name: photo.name, transferId: transferId)
        } else if let path = m.str("path"), let url = files.resolve(path), let handle = try? FileHandle(forReadingFrom: url) {
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? -1
            await stream(handle: handle, name: url.lastPathComponent, size: size, transferId: transferId)
            try? handle.close()
        }
    }

    private func stream(handle: FileHandle, name: String, size: Int, transferId: String) async {
        var seq = 0
        var current = (try? handle.read(upToCount: Self.chunkSize)) ?? Data()
        while true {
            let next = current.count < Self.chunkSize ? Data() : ((try? handle.read(upToCount: Self.chunkSize)) ?? Data())
            let last = next.isEmpty
            await sendAndWait(chunk(transferId: transferId, seq: seq, last: last, data: current, name: name, size: size))
            if last || !isConnected { break }
            current = next
            seq += 1
        }
    }

    private func stream(data: Data, name: String, transferId: String) async {
        var seq = 0
        var offset = 0
        repeat {
            let end = min(offset + Self.chunkSize, data.count)
            let last = end >= data.count
            await sendAndWait(chunk(transferId: transferId, seq: seq, last: last, data: data.subdata(in: offset..<end), name: name, size: data.count))
            offset = end
            seq += 1
            if last { break }
        } while isConnected
    }

    private func chunk(transferId: String, seq: Int, last: Bool, data: Data, name: String, size: Int) -> Envelope {
        var d: [String: Any] = ["transferId": transferId, "seq": seq, "last": last, "dataBase64": data.base64EncodedString()]
        if seq == 0 { d["name"] = name; d["size"] = size }
        return Envelope("file.chunk", d)
    }

    private static func newId() -> String { String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(12)).lowercased() }
}

/// Find my phone: plays an alert sound repeatedly for up to 30 seconds (while Pixlink is open).
@MainActor
enum Ringer {
    private static var timer: Timer?

    static func ring() {
        stop()
        var count = 0
        timer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { t in
            AudioServicesPlayAlertSound(SystemSoundID(1005))
            count += 1
            if count >= 20 { t.invalidate() }
        }
        timer?.fire()
    }

    static func stop() {
        timer?.invalidate()
        timer = nil
    }
}

enum Device {
    static var model: String {
        var info = utsname()
        uname(&info)
        let id = withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
        return id.isEmpty ? UIDevice.current.model : id
    }

    static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
    }
}
