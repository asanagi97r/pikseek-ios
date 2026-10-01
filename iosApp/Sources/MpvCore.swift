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

/// libmpv 的一层薄封装：建实例、设选项、发命令、读写属性、收事件。没有播放逻辑。
/// 播放器与缩略图解码器各建一个，互不相干。
///
/// 事件有两种取法，二选一：
/// - `startEvents`：mpv 有事件时唤醒，在自己的串行队列上逐个回调（播放器用）；
/// - `waitEvent`：调用方自己同步等（缩略图解码器用）。
final class MpvCore {
    private var handle: OpaquePointer?
    private let lock = NSLock()
    private let queue = DispatchQueue(label: "dev.pikseek.mpv.events", qos: .userInitiated)
    /// 留在这里是为了让层活得比 mpv 久：mpv 手里只有它的地址
    private var layer: MpvMetalLayer?

    /// 属性变了：名字与新值（按字符串订阅；变得不可用时为 nil）
    var onProperty: ((String, String?) -> Void)?
    /// 其余事件：mpv 给的名字；end-file 带原因（eof、stop、quit、redirect、error:<说明>）
    var onEvent: ((String, String?) -> Void)?

    /// - Parameters:
    ///   - layer: 有画面的实例传入要画到的层；nil 表示不出画面
    ///   - options: 初始化之前要设的选项，后设的盖掉先设的
    init?(layer: MpvMetalLayer?, options: [(String, String)]) {
        guard let mpv = mpv_create() else { return nil }
        self.layer = layer
        if var target = layer {
            // wid 给的是层对象的地址，按 int64 传
            mpv_set_option(mpv, "wid", MPV_FORMAT_INT64, &target)
        }
        for (name, value) in options {
            // 没有的选项（不同构建会少几个）跳过
            mpv_set_option_string(mpv, name, value)
        }
        guard mpv_initialize(mpv) >= 0 else {
            mpv_terminate_destroy(mpv)
            return nil
        }
        handle = mpv
    }

    deinit { close() }

    private func current() -> OpaquePointer? {
        lock.lock(); defer { lock.unlock() }
        return handle
    }

    /// 销毁实例。销毁要等 mpv 的线程收尾，放到事件队列上做，不挡调用方。
    func close() {
        lock.lock()
        let mpv = handle
        handle = nil
        lock.unlock()
        guard let mpv else { return }
        mpv_set_wakeup_callback(mpv, nil, nil)
        onProperty = nil
        onEvent = nil
        let layer = self.layer
        queue.async {
            mpv_terminate_destroy(mpv)
            // 层留到 mpv 销毁之后再放手
            _ = layer
        }
    }

    @discardableResult
    func command(_ arguments: [String]) -> Bool {
        guard let mpv = current() else { return false }
        var pointers: [UnsafePointer<CChar>?] = arguments.map { UnsafePointer(strdup($0)) }
        pointers.append(nil)
        defer { for pointer in pointers { free(UnsafeMutablePointer(mutating: pointer)) } }
        return mpv_command(mpv, &pointers) >= 0
    }

    @discardableResult
    func setProperty(_ name: String, _ value: String) -> Bool {
        guard let mpv = current() else { return false }
        return mpv_set_property_string(mpv, name, value) >= 0
    }

    func getProperty(_ name: String) -> String? {
        guard let mpv = current(), let raw = mpv_get_property_string(mpv, name) else { return nil }
        defer { mpv_free(raw) }
        return String(cString: raw)
    }

    func observe(_ name: String) {
        guard let mpv = current() else { return }
        mpv_observe_property(mpv, 0, name, MPV_FORMAT_STRING)
    }

    /// 开始把事件送到 `onProperty` 与 `onEvent`。
    func startEvents() {
        guard let mpv = current() else { return }
        mpv_set_wakeup_callback(mpv, { context in
            guard let context else { return }
            Unmanaged<MpvCore>.fromOpaque(context).takeUnretainedValue().drain()
        }, Unmanaged.passUnretained(self).toOpaque())
        drain()
    }

    private func drain() {
        queue.async { [weak self] in
            while let self, let mpv = self.current() {
                guard let event = mpv_wait_event(mpv, 0) else { break }
                if event.pointee.event_id == MPV_EVENT_NONE { break }
                self.dispatch(event.pointee)
            }
        }
    }

    private func dispatch(_ event: mpv_event) {
        switch event.event_id {
        case MPV_EVENT_PROPERTY_CHANGE:
            guard let data = event.data else { return }
            let property = data.assumingMemoryBound(to: mpv_event_property.self).pointee
            let name = String(cString: property.name)
            var value: String?
            if property.format == MPV_FORMAT_STRING, let raw = property.data {
                if let text = raw.assumingMemoryBound(to: UnsafePointer<CChar>?.self).pointee {
                    value = String(cString: text)
                }
            }
            onProperty?(name, value)
        case MPV_EVENT_END_FILE:
            var detail = "error"
            if let data = event.data {
                let end = data.assumingMemoryBound(to: mpv_event_end_file.self).pointee
                switch end.reason {
                case MPV_END_FILE_REASON_EOF: detail = "eof"
                case MPV_END_FILE_REASON_STOP: detail = "stop"
                case MPV_END_FILE_REASON_QUIT: detail = "quit"
                case MPV_END_FILE_REASON_REDIRECT: detail = "redirect"
                default: detail = "error:" + String(cString: mpv_error_string(end.error))
                }
            }
            onEvent?("end-file", detail)
        case MPV_EVENT_START_FILE, MPV_EVENT_FILE_LOADED, MPV_EVENT_PLAYBACK_RESTART, MPV_EVENT_SEEK, MPV_EVENT_SHUTDOWN:
            onEvent?(String(cString: mpv_event_name(event.event_id)), nil)
        default:
            break
        }
    }

    /// 同步等下一个事件，最多等 `timeout` 秒。没有事件（超时）或已关闭时返回 nil。
    /// 返回事件的编号；end-file 另给出是不是正常放完。
    func waitEvent(timeout: Double) -> (id: mpv_event_id, endedAtEof: Bool)? {
        guard let mpv = current(), let event = mpv_wait_event(mpv, timeout) else { return nil }
        let id = event.pointee.event_id
        if id == MPV_EVENT_NONE { return nil }
        var eof = false
        if id == MPV_EVENT_END_FILE, let data = event.pointee.data {
            eof = data.assumingMemoryBound(to: mpv_event_end_file.self).pointee.reason == MPV_END_FILE_REASON_EOF
        }
        return (id, eof)
    }

    /// 把队列里积着的旧事件读掉。
    func drainPending() {
        guard let mpv = current() else { return }
        while let event = mpv_wait_event(mpv, 0), event.pointee.event_id != MPV_EVENT_NONE {}
    }

    /// `screenshot-raw video`：解码器眼下交出来的那一帧，bgr0。没有画面时为 nil。
    func screenshotRaw() -> (width: Int32, height: Int32, stride: Int32, data: Data)? {
        guard let mpv = current() else { return nil }
        let arguments: [String] = ["screenshot-raw", "video"]
        var pointers: [UnsafePointer<CChar>?] = arguments.map { UnsafePointer(strdup($0)) }
        pointers.append(nil)
        defer { for pointer in pointers { free(UnsafeMutablePointer(mutating: pointer)) } }
        var result = mpv_node()
        guard mpv_command_ret(mpv, &pointers, &result) >= 0 else { return nil }
        defer { mpv_free_node_contents(&result) }
        guard result.format == MPV_FORMAT_NODE_MAP, let list = result.u.list?.pointee else { return nil }
        var width: Int64 = 0
        var height: Int64 = 0
        var stride: Int64 = 0
        var bytes: Data?
        for index in 0..<Int(list.num) {
            guard let keyPointer = list.keys[index] else { continue }
            let key = String(cString: keyPointer)
            let value = list.values[index]
            if value.format == MPV_FORMAT_INT64 {
                switch key {
                case "w": width = value.u.int64
                case "h": height = value.u.int64
                case "stride": stride = value.u.int64
                default: break
                }
            } else if value.format == MPV_FORMAT_BYTE_ARRAY, key == "data", let array = value.u.ba?.pointee, let base = array.data {
                bytes = Data(bytes: base, count: array.size)
            }
        }
        guard let data = bytes, width > 0, height > 0, stride >= width * 4, Int64(data.count) >= stride * height else { return nil }
        return (Int32(width), Int32(height), Int32(stride), data)
    }
}
