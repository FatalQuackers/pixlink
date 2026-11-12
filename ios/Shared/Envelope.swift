import Foundation

/// One protocol message: {"t": type, "id": request id, "d": payload}. See docs/PROTOCOL.md.
struct Envelope {
    var t: String
    var id: String?
    var d: [String: Any]

    init(_ t: String, id: String? = nil, _ d: [String: Any] = [:]) {
        self.t = t
        self.id = id
        self.d = d
    }

    static func parse(_ line: Data) -> Envelope? {
        guard let obj = (try? JSONSerialization.jsonObject(with: line)) as? [String: Any],
              let t = obj["t"] as? String else { return nil }
        return Envelope(t, id: obj["id"] as? String, obj["d"] as? [String: Any] ?? [:])
    }

    /// The message as one line of JSON, ending in a newline.
    func line() -> Data {
        var obj: [String: Any] = ["t": t, "d": d]
        if let id { obj["id"] = id }
        var data = (try? JSONSerialization.data(withJSONObject: obj)) ?? Data()
        data.append(0x0A)
        return data
    }

    /// A reply carrying this request's id.
    func reply(_ type: String, _ data: [String: Any] = [:]) -> Envelope { Envelope(type, id: id, data) }

    func str(_ key: String) -> String? { d[key] as? String }
    func int(_ key: String) -> Int? { (d[key] as? NSNumber)?.intValue }
    func bool(_ key: String) -> Bool? { (d[key] as? NSNumber)?.boolValue }
}
