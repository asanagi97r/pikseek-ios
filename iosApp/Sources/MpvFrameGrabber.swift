import Foundation
import Libmpv
import PikSeekKit

/// 一个不出画面、不出声音的 mpv 实例，专门解码取帧，给时间轴预览用。与主播放器完全分开：
/// 它定位、它读文件都不动主播放器的任何东西。与桌面版的 MpvFrameGrabber（Kotlin，经 FFM）同样的选项与流程。
///
/// 不是线程安全的：一个实例同一时刻只由一个线程用。方法都会阻塞，Kotlin 一侧放在后台线程上调。
final class MpvFrameGrabber: NSObject, FrameGrabberBridge {
    private let core: MpvCore
    private var duration: Int64 = 0

    private init(core: MpvCore) {
        self.core = core
        super.init()
    }

    static func make(frameWidth: Int32) -> MpvFrameGrabber? {
        let options: [(String, String)] = [
            // 不出画面、不出声音，停在帧上
            ("vo", "null"),
            ("ao", "null"),
            ("aid", "no"),
            ("sid", "no"),
            ("pause", "yes"),
            ("keep-open", "always"),
            ("idle", "yes"),
            // 不读配置，不往别处写任何东西
            ("config", "no"),
            ("terminal", "no"),
            ("input-default-bindings", "no"),
            ("input-vo-keyboard", "no"),
            ("osd-level", "0"),
            ("ytdl", "no"),
            ("resume-playback", "no"),
            ("save-position-on-quit", "no"),
            // 不去同目录里找字幕、音轨与封面
            ("sub-auto", "no"),
            ("audio-file-auto", "no"),
            ("cover-art-auto", "no"),
            ("audio-display", "no"),
            // 软件解码、只求快：缩略图只有两百来像素宽，也免得与主播放器抢硬解
            ("hwdec", "no"),
            ("vd-lavc-threads", "2"),
            ("vd-lavc-skiploopfilter", "all"),
            ("vd-lavc-fast", "yes"),
            ("sws-fast", "yes"),
            // 只落在关键帧上
            ("hr-seek", "no"),
            // 往后只读够解一帧的量，不囤
            ("cache", "yes"),
            ("demuxer-max-bytes", "\(4 * 1024 * 1024)"),
            ("demuxer-max-back-bytes", "0"),
            ("demuxer-readahead-secs", "0"),
            ("cache-secs", "1"),
            ("network-timeout", "20"),
            ("vf", "scale=w=\(frameWidth):h=-2"),
        ]
        guard let core = MpvCore(layer: nil, options: options) else { return nil }
        return MpvFrameGrabber(core: core)
    }

    func durationMs() -> Int64 { duration }

    func open(location: String, timeoutMs: Int64, startSeconds: Double) -> Bool {
        duration = 0
        core.drainPending()
        let started: Bool
        if startSeconds >= 0 {
            started = core.command(["loadfile", location, "replace", "-1", "start=\(String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), startSeconds))"])
        } else {
            started = core.command(["loadfile", location])
        }
        guard started, awaitFrame(timeoutMs: timeoutMs, newFile: true) else { return false }
        if let text = core.getProperty("duration"), let seconds = Double(text) {
            duration = Int64(seconds * 1000)
        }
        return true
    }

    func grab() -> GrabbedFrame? {
        let timeMs = core.getProperty("time-pos").flatMap(Double.init).map { Int64($0 * 1000) } ?? 0
        guard let shot = core.screenshotRaw() else { return nil }
        let rotate = core.getProperty("video-params/rotate").flatMap { Int32($0) } ?? 0
        let displayWidth = core.getProperty("dwidth").flatMap { Int32($0) } ?? 0
        let displayHeight = core.getProperty("dheight").flatMap { Int32($0) } ?? 0
        return GrabbedFrame(
            timeMs: timeMs,
            width: shot.width,
            height: shot.height,
            stride: shot.stride,
            data: shot.data,
            rotate: rotate,
            displayWidth: displayWidth,
            displayHeight: displayHeight
        )
    }

    func seekAndGrab(seconds: Double, timeoutMs: Int64) -> GrabbedFrame? {
        core.drainPending()
        let target = String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), seconds)
        guard core.command(["seek", target, "absolute+keyframes"]) else { return nil }
        guard awaitFrame(timeoutMs: timeoutMs, newFile: false) else { return nil }
        return grab()
    }

    func close() {
        core.close()
    }

    /// 等到新的一帧出来（playback-restart）。文件结束、出错、超时都返回 false。
    ///
    /// `newFile` 为 true 是刚下了 loadfile：上一个文件被顶掉时也会发一个「文件结束」，那不是这一个的，
    /// 要等到这一个文件的「开始」之后才认。
    private func awaitFrame(timeoutMs: Int64, newFile: Bool) -> Bool {
        let deadline = Date().addingTimeInterval(Double(timeoutMs) / 1000)
        var started = !newFile
        while Date() < deadline {
            guard let event = core.waitEvent(timeout: 0.25) else { continue }
            switch event.id {
            case MPV_EVENT_START_FILE:
                started = true
            case MPV_EVENT_PLAYBACK_RESTART:
                if started { return true }
            case MPV_EVENT_END_FILE:
                // 只有一帧的小文件：解完这一帧就到了文件末尾。keep-open 让画面停在最后一帧，照样取得到；
                // 出错或被别的文件顶掉才算失败
                if started && !event.endedAtEof { return false }
            case MPV_EVENT_SHUTDOWN:
                return false
            default:
                break
            }
        }
        return false
    }
}
