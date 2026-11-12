import Foundation

/// What the PC's QR code holds:
/// pixlink://pair?v=1&host=192.168.1.20&port=47100&token=…&fp=SHA256:AB:…&name=DESKTOP-01
struct PairingInfo: Equatable {
    var host: String
    var port: Int
    var token: String
    var fingerprint: String
    var name: String

    init?(_ text: String) {
        guard let c = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              c.scheme?.lowercased() == "pixlink", c.host?.lowercased() == "pair" else { return nil }
        func q(_ key: String) -> String? { c.queryItems?.first { $0.name == key }?.value }
        guard let host = q("host"), let token = q("token"), let fp = q("fp") else { return nil }
        self.host = host
        self.port = Int(q("port") ?? "") ?? 47100
        self.token = token
        self.fingerprint = fp.replacingOccurrences(of: "SHA256:", with: "").uppercased()
        self.name = q("name") ?? "your PC"
    }
}
