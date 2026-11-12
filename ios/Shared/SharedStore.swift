import Foundation
import Security

/// The paired PC, as remembered on this iPhone.
struct PairedPC: Codable, Equatable {
    var host: String
    var port: Int
    /// SHA-256 of the PC's certificate, AA:BB:… form.
    var fingerprint: String
    var name: String
    var pcId: String
}

/// Storage shared by the app and the screen casting extension (an App Group), so the extension
/// knows which PC to cast to and has the certificate to prove it's this iPhone.
enum SharedStore {
    static let appGroup = "group.app.pixlink"

    static var defaults: UserDefaults { UserDefaults(suiteName: appGroup) ?? .standard }

    static var pc: PairedPC? {
        get {
            guard let data = defaults.data(forKey: "pc") else { return nil }
            return try? JSONDecoder().decode(PairedPC.self, from: data)
        }
        set {
            if let newValue, let data = try? JSONEncoder().encode(newValue) {
                defaults.set(data, forKey: "pc")
            } else {
                defaults.removeObject(forKey: "pc")
            }
        }
    }

    /// The client certificate the PC issued when pairing (PKCS#12) and its password.
    static func saveIdentity(p12: Data, password: String) {
        defaults.set(p12, forKey: "identityP12")
        defaults.set(password, forKey: "identityPassword")
    }

    static func forget() {
        pc = nil
        defaults.removeObject(forKey: "identityP12")
        defaults.removeObject(forKey: "identityPassword")
    }

    static func loadIdentity() -> SecIdentity? {
        guard let p12 = defaults.data(forKey: "identityP12"),
              let password = defaults.string(forKey: "identityPassword") else { return nil }
        var items: CFArray?
        let status = SecPKCS12Import(p12 as CFData, [kSecImportExportPassphrase as String: password] as CFDictionary, &items)
        guard status == errSecSuccess,
              let list = items as? [[String: Any]],
              let first = list.first,
              let identity = first[kSecImportItemIdentity as String] else { return nil }
        return (identity as! SecIdentity)
    }
}
