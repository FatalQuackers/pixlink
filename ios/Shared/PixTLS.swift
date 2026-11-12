import CryptoKit
import Foundation
import Network
import Security

/// TLS like the Android app: only the paired PC's certificate is accepted (pinned by SHA-256),
/// and this iPhone proves who it is with the certificate the PC gave it.
enum PixTLS {
    static func fingerprint(_ der: Data) -> String {
        SHA256.hash(data: der).map { String(format: "%02X", $0) }.joined(separator: ":")
    }

    static func parameters(pin: String, identity: SecIdentity?) -> NWParameters {
        let tls = NWProtocolTLS.Options()
        let options = tls.securityProtocolOptions
        sec_protocol_options_set_min_tls_protocol_version(options, .TLSv12)
        if let identity, let secIdentity = sec_identity_create(identity) {
            sec_protocol_options_set_local_identity(options, secIdentity)
        }
        let expected = pin.replacingOccurrences(of: "SHA256:", with: "").uppercased()
        sec_protocol_options_set_verify_block(options, { _, trust, complete in
            let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
            guard let chain = SecTrustCopyCertificateChain(secTrust) as? [SecCertificate],
                  let leaf = chain.first else { complete(false); return }
            complete(fingerprint(SecCertificateCopyData(leaf) as Data) == expected)
        }, DispatchQueue.global(qos: .userInitiated))

        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 20
        tcp.connectionTimeout = 8
        return NWParameters(tls: tls, tcp: tcp)
    }
}
