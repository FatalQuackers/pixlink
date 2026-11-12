import CoreMedia
import VideoToolbox

/// Hardware H.264 encoder producing Annex-B frames (start codes, SPS/PPS before every key frame).
final class H264Encoder {
    typealias Output = (Data, Int64, Bool) -> Void

    private var session: VTCompressionSession?
    private var transfer: VTPixelTransferSession?
    private let output: Output
    private var lastPts = CMTime.invalid
    private var forceKey = true

    init?(width: Int, height: Int, bitrate: Int, output: @escaping Output) {
        self.output = output
        let attrs: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
            kCVPixelBufferWidthKey: width,
            kCVPixelBufferHeightKey: height,
            kCVPixelBufferIOSurfacePropertiesKey: [String: Any](),
        ]
        var s: VTCompressionSession?
        let status = VTCompressionSessionCreate(allocator: nil, width: Int32(width), height: Int32(height),
                                                codecType: kCMVideoCodecType_H264, encoderSpecification: nil,
                                                imageBufferAttributes: attrs as CFDictionary, compressedDataAllocator: nil,
                                                outputCallback: nil, refcon: nil, compressionSessionOut: &s)
        guard status == noErr, let s else { return nil }
        session = s
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_H264_Main_AutoLevel)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_AverageBitRate, value: bitrate as CFNumber)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: 30 as CFNumber)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: 60 as CFNumber)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, value: 2 as CFNumber)
        VTCompressionSessionPrepareToEncodeFrames(s)

        var t: VTPixelTransferSession?
        VTPixelTransferSessionCreate(allocator: nil, pixelTransferSessionOut: &t)
        transfer = t
    }

    func requestKeyFrame() { forceKey = true }

    func encode(_ source: CVPixelBuffer, pts: CMTime) {
        // At most ~30 frames a second.
        if lastPts.isValid, CMTimeGetSeconds(CMTimeSubtract(pts, lastPts)) < 1.0 / 31 { return }
        lastPts = pts
        guard let session, let transfer, let pool = VTCompressionSessionGetPixelBufferPool(session) else { return }
        var scaled: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, pool, &scaled)
        guard let scaled, VTPixelTransferSessionTransferImage(transfer, from: source, to: scaled) == noErr else { return }

        var props: CFDictionary?
        if forceKey {
            forceKey = false
            props = [kVTEncodeFrameOptionKey_ForceKeyFrame: kCFBooleanTrue] as CFDictionary
        }
        VTCompressionSessionEncodeFrame(session, imageBuffer: scaled, presentationTimeStamp: pts, duration: .invalid,
                                        frameProperties: props, infoFlagsOut: nil) { [weak self] status, _, sample in
            guard status == noErr, let sample, let self else { return }
            self.emit(sample)
        }
    }

    private func emit(_ sample: CMSampleBuffer) {
        let startCode: [UInt8] = [0, 0, 0, 1]
        var notSync = false
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[CFString: Any]],
           let first = attachments.first {
            notSync = first[kCMSampleAttachmentKey_NotSync] as? Bool ?? false
        }
        let isKey = !notSync
        var out = Data()

        if isKey, let format = CMSampleBufferGetFormatDescription(sample) {
            var count = 0
            CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, parameterSetIndex: 0, parameterSetPointerOut: nil,
                                                               parameterSetSizeOut: nil, parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil)
            for i in 0..<count {
                var pointer: UnsafePointer<UInt8>?
                var size = 0
                CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, parameterSetIndex: i, parameterSetPointerOut: &pointer,
                                                                   parameterSetSizeOut: &size, parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil)
                if let pointer {
                    out.append(contentsOf: startCode)
                    out.append(pointer, count: size)
                }
            }
        }

        guard let block = CMSampleBufferGetDataBuffer(sample) else { return }
        let length = CMBlockBufferGetDataLength(block)
        var avcc = Data(count: length)
        let copied = avcc.withUnsafeMutableBytes { raw -> OSStatus in
            guard let base = raw.baseAddress else { return -1 }
            return CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length, destination: base)
        }
        guard copied == noErr else { return }

        // AVCC (4-byte lengths) → Annex-B (start codes).
        var offset = 0
        while offset + 4 <= length {
            let nalLength = Int(avcc[offset]) << 24 | Int(avcc[offset + 1]) << 16 | Int(avcc[offset + 2]) << 8 | Int(avcc[offset + 3])
            offset += 4
            guard nalLength > 0, offset + nalLength <= length else { break }
            out.append(contentsOf: startCode)
            out.append(avcc.subdata(in: offset..<(offset + nalLength)))
            offset += nalLength
        }

        let pts = CMSampleBufferGetPresentationTimeStamp(sample)
        output(out, Int64(CMTimeGetSeconds(pts) * 1_000_000), isKey)
    }

    func invalidate() {
        if let session {
            VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
            VTCompressionSessionInvalidate(session)
        }
        session = nil
        if let transfer { VTPixelTransferSessionInvalidate(transfer) }
        transfer = nil
    }
}
