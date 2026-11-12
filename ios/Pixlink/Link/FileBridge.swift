import Foundation

/// Files tab on the PC: shows Pixlink's own folder on the iPhone (also visible in the Files app under
/// On My iPhone → Pixlink). Files sent from the PC are saved there.
final class FileBridge {
    static var root: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].resolvingSymlinksInPath()
    }

    private var incoming: [String: (handle: FileHandle, url: URL)] = [:]

    func roots(_ m: Envelope) -> Envelope {
        m.reply("files.roots", ["roots": [["name": "Pixlink on iPhone", "path": "/"]], "powerMode": false])
    }

    /// Maps a PC-side path ("/", "/Folder/file.jpg") to a URL inside Pixlink's folder.
    func resolve(_ path: String) -> URL? {
        let rel = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let url = (rel.isEmpty ? Self.root : Self.root.appendingPathComponent(rel)).standardizedFileURL.resolvingSymlinksInPath()
        return url.path.hasPrefix(Self.root.path) ? url : nil
    }

    func list(_ m: Envelope) -> Envelope {
        let path = m.str("path") ?? "/"
        let keys: [URLResourceKey] = [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey]
        guard let dir = resolve(path),
              let items = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles])
        else { return m.reply("files.list", ["path": path, "entries": [Any]()]) }

        let base = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let entries: [[String: Any]] = items.compactMap { url in
            let v = try? url.resourceValues(forKeys: Set(keys))
            let isDir = v?.isDirectory ?? false
            return [
                "name": url.lastPathComponent,
                "dir": isDir,
                "size": v?.fileSize ?? 0,
                "modified": Int((v?.contentModificationDate ?? Date()).timeIntervalSince1970 * 1000),
                "path": "/" + (base.isEmpty ? url.lastPathComponent : base + "/" + url.lastPathComponent),
            ]
        }.sorted {
            let a = $0["dir"] as? Bool ?? false, b = $1["dir"] as? Bool ?? false
            if a != b { return a }
            return ($0["name"] as? String ?? "").localizedCaseInsensitiveCompare($1["name"] as? String ?? "") == .orderedAscending
        }
        return m.reply("files.list", ["path": path, "entries": entries])
    }

    /// A file the PC is about to send (file.put).
    func begin(_ m: Envelope) {
        guard let id = m.str("transferId") ?? m.id else { return }
        let name = (m.str("name") ?? "file").replacingOccurrences(of: "/", with: "_")
        let url = Self.uniqueURL(Self.root.appendingPathComponent(name))
        FileManager.default.createFile(atPath: url.path, contents: nil)
        guard let handle = try? FileHandle(forWritingTo: url) else { return }
        incoming[id] = (handle, url)
    }

    /// Writes one chunk; returns the file name when the file is complete.
    func chunk(_ m: Envelope) -> String? {
        guard let id = m.str("transferId"), let t = incoming[id] else { return nil }
        if let data = Data(base64Encoded: m.str("dataBase64") ?? "") { t.handle.write(data) }
        guard m.bool("last") == true else { return nil }
        try? t.handle.close()
        incoming[id] = nil
        return t.url.lastPathComponent
    }

    static func uniqueURL(_ url: URL) -> URL {
        guard FileManager.default.fileExists(atPath: url.path) else { return url }
        let dir = url.deletingLastPathComponent()
        let stem = url.deletingPathExtension().lastPathComponent
        let ext = url.pathExtension
        for i in 1... {
            let candidate = dir.appendingPathComponent(ext.isEmpty ? "\(stem) (\(i))" : "\(stem) (\(i)).\(ext)")
            if !FileManager.default.fileExists(atPath: candidate.path) { return candidate }
        }
        return url
    }
}
