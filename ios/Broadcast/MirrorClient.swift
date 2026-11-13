import Foundation
import Network

/// Sends the encoded screen to the PC's mirror port (47101) over the same pinned TLS as the app.
/// Framing: "PXLI" + width + height once (iPhone casting), then [uint32 length][uint64 pts µs][Annex-B bytes].
final class MirrorClient {
    static let port: UInt16 = 47101

    var onClosed: ((String) -> Void)?
    /// Frames were dropped because Wi-Fi couldn't keep up: the next frame should be a key frame.
    var onCongested: (() -> Void)?

    private let connection: NWConnection
    private let queue = DispatchQueue(label: "pixlink.mirror")
    private let stateLock = NSLock()
    private var ready = false
    private var closed = false
    private var inFlight = 0
    private var dropping = false

    var isReady: Bool { stateLock.lock(); defer { stateLock.unlock() }; return ready && !closed }

    init(pc: PairedPC, identity: SecIdentity) {
        connection = NWConnection(host: NWEndpoint.Host(pc.host), port: NWEndpoint.Port(rawValue: Self.port)!,
                                  using: PixTLS.parameters(pin: pc.fingerprint, identity: identity))
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.stateLock.lock(); self.ready = true; self.stateLock.unlock()
                self.watchForClose()
            case .failed, .waiting:
                self.finish("Couldn't reach your PC. Open Tethr Link on your PC, check both are on the same Wi-Fi, then try again.")
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    /// The PC never sends anything on this connection; it ends when the PC stops showing the screen.
    private func watchForClose() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1024) { [weak self] _, _, done, error in
            guard let self else { return }
            if done || error != nil { self.finish("Screen casting was stopped on your PC.") }
            else { self.watchForClose() }
        }
    }

    func announce(width: Int, height: Int) {
        var header = Data("PXLI".utf8)
        header.append(Self.be32(UInt32(width)))
        header.append(Self.be32(UInt32(height)))
        connection.send(content: header, completion: .contentProcessed { _ in })
    }

    func sendFrame(_ data: Data, ptsUs: Int64, isKey: Bool) {
        stateLock.lock()
        // Wi-Fi falling behind: skip frames until the next key frame instead of building up delay.
        if inFlight > 6 && !isKey { dropping = true }
        if dropping && isKey { dropping = false }
        let skip = dropping
        if !skip { inFlight += 1 }
        stateLock.unlock()
        if skip { onCongested?(); return }

        var packet = Data(capacity: data.count + 12)
        packet.append(Self.be32(UInt32(data.count)))
        packet.append(Self.be64(UInt64(bitPattern: ptsUs)))
        packet.append(data)
        connection.send(content: packet, completion: .contentProcessed { [weak self] _ in
            guard let self else { return }
            self.stateLock.lock(); self.inFlight -= 1; self.stateLock.unlock()
        })
    }

    func close() {
        stateLock.lock(); closed = true; stateLock.unlock()
        connection.cancel()
    }

    private func finish(_ reason: String) {
        stateLock.lock()
        let already = closed
        closed = true
        stateLock.unlock()
        connection.cancel()
        if !already { onClosed?(reason) }
    }

    private static func be32(_ v: UInt32) -> Data { withUnsafeBytes(of: v.bigEndian) { Data($0) } }
    private static func be64(_ v: UInt64) -> Data { withUnsafeBytes(of: v.bigEndian) { Data($0) } }
}
