import Foundation
import QuartzCore
import Libmpv

/// mpv 画到这个层上（vo=gpu-next，经 MoltenVK 走 Metal）。
final class MpvMetalLayer: CAMetalLayer {
    // MoltenVK 为了收尾一次呈现会把 drawableSize 设成 1x1，画面跟着闪，有时就停在 1x1 不回来。
    // 忽略这种设置（mpv-player/mpv#13651）
    override var drawableSize: CGSize {
        get { super.drawableSize }
        set {
            if Int(newValue.width) > 1 && Int(newValue.height) > 1 {
                super.drawableSize = newValue
            }
        }
    }
}

/// libmpv 的一层薄封装：建实例、设选项、发命令、读属性、收日志。
/// 播放器和缩略图解码器各建一个，互不相干。
final class MpvCore {
    private(set) var handle: OpaquePointer?
    private let queue = DispatchQueue(label: "dev.pikseek.mpv.events", qos: .userInitiated)
    private let logLock = NSLock()
    private var logLines: [String] = []
    /// 留在这里是为了让层活得比 mpv 久：mpv 手里只有它的地址
    private var layer: MpvMetalLayer?

    var onEvent: ((mpv_event_id) -> Void)?

    /// - Parameter layer: 有画面的实例传入要画到的层；nil 表示不出画面（缩略图解码器）
    init?(layer: MpvMetalLayer?, options: [(String, String)] = []) {
        guard let mpv = mpv_create() else { return nil }
        handle = mpv
        self.layer = layer
        mpv_request_log_messages(mpv, "warn")
        if var target = layer {
            // wid 给的是层对象的地址，按 int64 传
            mpv_set_option(mpv, "wid", MPV_FORMAT_INT64, &target)
            set(option: "vo", "gpu-next")
            set(option: "gpu-api", "vulkan")
            set(option: "gpu-context", "moltenvk")
            set(option: "hwdec", "videotoolbox")
        } else {
            set(option: "vo", "null")
            set(option: "ao", "null")
            set(option: "hwdec", "no")
        }
        for (name, value) in options { set(option: name, value) }
        guard mpv_initialize(mpv) >= 0 else {
            mpv_terminate_destroy(mpv)
            handle = nil
            return nil
        }
        mpv_set_wakeup_callback(mpv, { context in
            guard let context else { return }
            Unmanaged<MpvCore>.fromOpaque(context).takeUnretainedValue().drainEvents()
        }, Unmanaged.passUnretained(self).toOpaque())
    }

    deinit { close() }

    func close() {
        guard let mpv = handle else { return }
        handle = nil
        mpv_set_wakeup_callback(mpv, nil, nil)
        // 等事件队列里正在跑的那一轮结束，再销毁
        queue.sync {}
        mpv_terminate_destroy(mpv)
    }

    @discardableResult
    func set(option name: String, _ value: String) -> Int32 {
        guard let mpv = handle else { return -1 }
        return mpv_set_option_string(mpv, name, value)
    }

    @discardableResult
    func set(property name: String, _ value: String) -> Int32 {
        guard let mpv = handle else { return -1 }
        return mpv_set_property_string(mpv, name, value)
    }

    func string(_ name: String) -> String? {
        guard let mpv = handle, let raw = mpv_get_property_string(mpv, name) else { return nil }
        defer { mpv_free(raw) }
        return String(cString: raw)
    }

    func double(_ name: String) -> Double? {
        guard let mpv = handle else { return nil }
        var value = Double()
        return mpv_get_property(mpv, name, MPV_FORMAT_DOUBLE, &value) >= 0 ? value : nil
    }

    func flag(_ name: String) -> Bool? {
        guard let mpv = handle else { return nil }
        var value: Int32 = 0
        return mpv_get_property(mpv, name, MPV_FORMAT_FLAG, &value) >= 0 ? value != 0 : nil
    }

    @discardableResult
    func command(_ arguments: [String]) -> Int32 {
        guard let mpv = handle else { return -1 }
        var pointers: [UnsafePointer<CChar>?] = arguments.map { UnsafePointer(strdup($0)) }
        pointers.append(nil)
        defer { for pointer in pointers { free(UnsafeMutablePointer(mutating: pointer)) } }
        return mpv_command(mpv, &pointers)
    }

    /// 到目前为止 mpv 报的告警与错误
    func logs() -> [String] {
        logLock.lock(); defer { logLock.unlock() }
        return logLines
    }

    private func drainEvents() {
        queue.async { [weak self] in
            while let self, let mpv = self.handle {
                guard let event = mpv_wait_event(mpv, 0) else { break }
                let id = event.pointee.event_id
                if id == MPV_EVENT_NONE { break }
                if id == MPV_EVENT_LOG_MESSAGE,
                   let message = event.pointee.data?.assumingMemoryBound(to: mpv_event_log_message.self).pointee {
                    let line = "[\(String(cString: message.prefix))] \(String(cString: message.level)): "
                        + String(cString: message.text).trimmingCharacters(in: .whitespacesAndNewlines)
                    self.logLock.lock()
                    if self.logLines.count < 200 { self.logLines.append(line) }
                    self.logLock.unlock()
                }
                self.onEvent?(id)
            }
        }
    }
}
