import Photos
import UIKit

/// Photos tab on the PC: lists the photo library, sends thumbnails and originals.
/// Photo ids are positions in the latest list (newest first).
actor PhotoBridge {
    private var assets: [PHAsset] = []

    private func authorized() async -> Bool {
        let status = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        return status == .authorized || status == .limited
    }

    func list(_ m: Envelope) async -> Envelope {
        guard await authorized() else { return m.reply("photos.list", ["photos": [Any]()]) }
        let offset = m.int("offset") ?? 0
        let limit = m.int("limit") ?? 200
        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        let result = PHAsset.fetchAssets(with: .image, options: options)
        var all: [PHAsset] = []
        all.reserveCapacity(result.count)
        result.enumerateObjects { asset, _, _ in all.append(asset) }
        assets = all

        var photos: [[String: Any]] = []
        for (i, asset) in all.dropFirst(offset).prefix(limit).enumerated() {
            let date = asset.creationDate ?? Date(timeIntervalSince1970: 0)
            photos.append([
                "id": offset + i,
                "date": Int(date.timeIntervalSince1970 * 1000),
                "width": asset.pixelWidth,
                "height": asset.pixelHeight,
                "name": Self.fileName(for: asset, date: date),
                "size": 0,
            ])
        }
        return m.reply("photos.list", ["photos": photos])
    }

    func thumb(_ m: Envelope) async -> Envelope {
        let id = m.int("id") ?? -1
        let size = m.int("size") ?? 300
        var data: [String: Any] = ["id": id]
        if assets.indices.contains(id), let image = await Self.image(assets[id], size: size),
           let jpeg = image.jpegData(compressionQuality: 0.8) {
            data["jpegBase64"] = jpeg.base64EncodedString()
        }
        return m.reply("photos.thumb", data)
    }

    /// The original photo (HEIC or JPEG, as taken).
    func original(id: Int) async -> (name: String, data: Data)? {
        guard assets.indices.contains(id) else { return nil }
        let asset = assets[id]
        let name = PHAssetResource.assetResources(for: asset).first?.originalFilename ?? "photo_\(id).jpg"
        let data: Data? = await withCheckedContinuation { cont in
            let options = PHImageRequestOptions()
            options.isNetworkAccessAllowed = true
            options.deliveryMode = .highQualityFormat
            options.version = .current
            PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) { data, _, _, _ in
                cont.resume(returning: data)
            }
        }
        return data.map { (name, $0) }
    }

    private static func fileName(for asset: PHAsset, date: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd_HHmmss"
        return "IMG_\(f.string(from: date)).jpg"
    }

    private static func image(_ asset: PHAsset, size: Int) async -> UIImage? {
        await withCheckedContinuation { cont in
            let options = PHImageRequestOptions()
            options.isNetworkAccessAllowed = true
            options.deliveryMode = .highQualityFormat
            options.resizeMode = .fast
            PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: size, height: size),
                                                  contentMode: .aspectFit, options: options) { image, _ in
                cont.resume(returning: image)
            }
        }
    }
}
