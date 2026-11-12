import CoreMedia
import ReplayKit

/// Screen casting: iOS hands this extension the screen while a broadcast runs. Frames are encoded
/// to H.264 and sent to the PC's mirror port (47101), the same stream the Android app sends.
final class SampleHandler: RPBroadcastSampleHandler {
    private var client: MirrorClient?
    private var encoder: H264Encoder?
    private let lock = NSLock()

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        guard let pc = SharedStore.pc, let identity = SharedStore.loadIdentity() else {
            stop("Open Pixlink and link your PC first.")
            return
        }
        let client = MirrorClient(pc: pc, identity: identity)
        client.onClosed = { [weak self] reason in self?.stop(reason) }
        client.onCongested = { [weak self] in self?.encoder?.requestKeyFrame() }
        self.client = client
        client.start()
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with type: RPSampleBufferType) {
        guard type == .video, let client, client.isReady,
              let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        lock.lock()
        defer { lock.unlock() }
        if encoder == nil {
            let (w, h) = Self.outputSize(width: CVPixelBufferGetWidth(pixels), height: CVPixelBufferGetHeight(pixels))
            encoder = H264Encoder(width: w, height: h, bitrate: 6_000_000) { [weak client] data, ptsUs, isKey in
                client?.sendFrame(data, ptsUs: ptsUs, isKey: isKey)
            }
            guard encoder != nil else {
                stop("Screen casting couldn't start on this iPhone.")
                return
            }
            client.announce(width: w, height: h)
        }
        encoder?.encode(pixels, pts: CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
    }

    override func broadcastFinished() {
        encoder?.invalidate()
        client?.close()
    }

    private func stop(_ reason: String) {
        encoder?.invalidate()
        client?.close()
        finishBroadcastWithError(NSError(domain: "app.pixlink", code: 1, userInfo: [NSLocalizedDescriptionKey: reason]))
    }

    /// Long side at most 1280 pixels (enough for a sharp picture, light on Wi-Fi), even numbers for H.264.
    static func outputSize(width: Int, height: Int) -> (Int, Int) {
        let scale = min(1, 1280 / Double(max(width, height)))
        func even(_ v: Double) -> Int { max(2, Int(v.rounded()) & ~1) }
        return (even(Double(width) * scale), even(Double(height) * scale))
    }
}
