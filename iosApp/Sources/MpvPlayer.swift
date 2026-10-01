import UIKit
import PikSeekKit

/// 放画面的视图：里面只有一个 Metal 层，跟着视图的大小走。不接触摸，控件都在上面那层 Compose 里。
final class MpvVideoView: UIView {
    let metalLayer = MpvMetalLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .black
        metalLayer.contentsScale = UIScreen.main.nativeScale
        metalLayer.framebufferOnly = true
        metalLayer.backgroundColor = UIColor.black.cgColor
        metalLayer.frame = bounds
        layer.addSublayer(metalLayer)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        // 不要隐式动画：转屏时层要立刻到位
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        metalLayer.frame = bounds
        CATransaction.commit()
    }
}

/// 一个带画面的 libmpv 实例，交给 Kotlin 一侧的播放后端去驱动。这里没有播放逻辑：
/// 只建实例、定画面输出、把命令与事件原样转过去。
final class MpvPlayer: NSObject, MpvPlayerBridge {
    private let videoView: MpvVideoView
    private let core: MpvCore
    private var observers: [NSObjectProtocol] = []

    private init(core: MpvCore, view: MpvVideoView) {
        self.core = core
        self.videoView = view
        super.init()
    }

    /// 建不出 mpv 实例时返回 nil。在主线程上调。
    static func make() -> MpvPlayer? {
        let view = MpvVideoView(frame: CGRect(x: 0, y: 0, width: 320, height: 180))
        let options: [(String, String)] = [
            // 画面：libplacebo 经 MoltenVK 画到 Metal 层上；能硬解的交给 VideoToolbox
            ("vo", "gpu-next"),
            ("gpu-api", "vulkan"),
            ("gpu-context", "moltenvk"),
            ("hwdec", "videotoolbox"),
            // 不读配置、不接键盘、不往别处写任何东西
            ("config", "no"),
            ("terminal", "no"),
            ("input-default-bindings", "no"),
            ("input-vo-keyboard", "no"),
            ("osc", "no"),
            ("osd-level", "0"),
            ("ytdl", "no"),
            ("resume-playback", "no"),
            ("save-position-on-quit", "no"),
            // 地址是本机代理给的，不去同目录里找字幕、音轨与封面
            ("sub-auto", "no"),
            ("audio-file-auto", "no"),
            ("cover-art-auto", "no"),
            ("audio-display", "no"),
            // 手机内存有限，往后囤 64 MiB、往前留 16 MiB
            ("cache", "yes"),
            ("demuxer-max-bytes", "\(64 * 1024 * 1024)"),
            ("demuxer-max-back-bytes", "\(16 * 1024 * 1024)"),
            ("network-timeout", "30"),
        ]
        guard let core = MpvCore(layer: view.metalLayer, options: options) else { return nil }
        let player = MpvPlayer(core: core, view: view)
        player.watchAppState()
        return player
    }

    func view() -> UIView { videoView }

    func command(arguments: [String]) -> Bool { core.command(arguments) }

    func setProperty(name: String, value: String) -> Bool { core.setProperty(name, value) }

    func getProperty(name: String) -> String? { core.getProperty(name) }

    func observe(name: String) { core.observe(name) }

    func setEvents(events: MpvEvents?) {
        if let events {
            core.onProperty = { name, value in events.onPropertyChanged(name: name, value: value) }
            core.onEvent = { event, detail in events.onEvent(event: event, detail: detail) }
            core.startEvents()
        } else {
            core.onProperty = nil
            core.onEvent = nil
        }
    }

    func close() {
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        core.close()
    }

    /// 退到后台时停掉画面输出：后台不许用 Metal，留着回来会黑屏。回到前台再接上，播不播由播放后端决定。
    private func watchAppState() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.core.setProperty("pause", "yes")
            self?.core.setProperty("vid", "no")
        })
        observers.append(center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.core.setProperty("vid", "auto")
        })
    }
}
