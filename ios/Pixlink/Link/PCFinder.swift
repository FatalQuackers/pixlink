import Foundation
import Network

/// Finds the paired PC's current address with Bonjour ("<pcId>._pixlink._tcp").
/// iPhones can't hear the PC's UDP beacon, so this is how Pixlink follows the PC to a new IP address.
enum PCFinder {
    static func find(pcId: String, timeout: TimeInterval = 4) async -> String? {
        await withCheckedContinuation { (cont: CheckedContinuation<String?, Never>) in
            let queue = DispatchQueue(label: "pixlink.finder")
            let browser = NWBrowser(for: .bonjour(type: "_pixlink._tcp", domain: nil), using: .tcp)
            var finished = false
            var resolving: NWConnection?

            func finish(_ host: String?) {
                guard !finished else { return }
                finished = true
                browser.cancel()
                resolving?.cancel()
                cont.resume(returning: host)
            }

            browser.browseResultsChangedHandler = { results, _ in
                guard resolving == nil else { return }
                for result in results {
                    guard case let .service(name, _, _, _) = result.endpoint, name.lowercased() == pcId.lowercased() else { continue }
                    // Connect to the service briefly just to learn its IPv4 address.
                    let params = NWParameters.tcp
                    if let ip = params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options { ip.version = .v4 }
                    let c = NWConnection(to: result.endpoint, using: params)
                    resolving = c
                    c.stateUpdateHandler = { state in
                        switch state {
                        case .ready:
                            if case let .hostPort(host, _) = c.currentPath?.remoteEndpoint {
                                var text = "\(host)"
                                if let pct = text.firstIndex(of: "%") { text = String(text[..<pct]) }
                                finish(text)
                            } else { finish(nil) }
                        case .failed: finish(nil)
                        default: break
                        }
                    }
                    c.start(queue: queue)
                    return
                }
            }
            browser.stateUpdateHandler = { state in if case .failed = state { finish(nil) } }
            browser.start(queue: queue)
            queue.asyncAfter(deadline: .now() + timeout) { finish(nil) }
        }
    }
}
