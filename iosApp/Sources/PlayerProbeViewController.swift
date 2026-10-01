import UIKit

/// 自检页：用 libmpv 把随包的一个样片画到屏幕上，几秒后把读到的状态写进 Documents/probe.json。
/// 打包流程在模拟器里启动它、截图、取回这个文件，据此判断 iOS 上的播放链路通不通。
final class PlayerProbeViewController: UIViewController {
    private let sample: String
    private let metalLayer = MpvMetalLayer()
    private var core: MpvCore?
    private var headless: MpvCore?
    private let label = UILabel()

    init(sample: String) {
        self.sample = sample
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        metalLayer.frame = view.bounds
        metalLayer.contentsScale = UIScreen.main.nativeScale
        metalLayer.framebufferOnly = true
        metalLayer.backgroundColor = UIColor.black.cgColor
        view.layer.addSublayer(metalLayer)

        label.textColor = .white
        label.numberOfLines = 0
        label.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        label.frame = CGRect(x: 12, y: 60, width: view.bounds.width - 24, height: 200)
        view.addSubview(label)

        let name = (sample as NSString).deletingPathExtension
        let ext = (sample as NSString).pathExtension
        guard let path = Bundle.main.path(forResource: name, ofType: ext) else {
            report(["error": "sample \(sample) is not in the bundle"])
            return
        }
        guard let core = MpvCore(layer: metalLayer, options: [("loop-file", "inf")]) else {
            report(["error": "mpv did not initialise"])
            return
        }
        self.core = core
        core.command(["loadfile", path, "replace"])

        // 另起一个不出画面的实例，和缩略图解码器同样的配置：确认两个实例能并存、软解能跑
        let headless = MpvCore(layer: nil, options: [("pause", "yes")])
        self.headless = headless
        headless?.command(["loadfile", path, "replace"])

        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { [weak self] in self?.sampleState(final: false) }
        DispatchQueue.main.asyncAfter(deadline: .now() + 6.0) { [weak self] in self?.sampleState(final: true) }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        metalLayer.frame = view.bounds
    }

    private var firstPosition: Double?

    private func sampleState(final: Bool) {
        guard let core else { return }
        let position = core.double("time-pos")
        if !final {
            firstPosition = position
            return
        }
        var result: [String: Any] = [
            "sample": sample,
            "timePosAt2_5s": firstPosition ?? -1,
            "timePosAt6s": position ?? -1,
            "duration": core.double("duration") ?? -1,
            "videoCodec": core.string("video-codec") ?? "",
            "audioCodec": core.string("audio-codec") ?? "",
            "width": core.string("video-params/w") ?? "",
            "height": core.string("video-params/h") ?? "",
            "currentVo": core.string("current-vo") ?? "",
            "gpuContext": core.string("current-gpu-context") ?? "",
            "hwdecCurrent": core.string("hwdec-current") ?? "",
            "voConfigured": core.flag("vo-configured") ?? false,
            "estimatedVfFps": core.double("estimated-vf-fps") ?? -1,
            "frameDropCount": core.string("frame-drop-count") ?? "",
            "mpvVersion": core.string("mpv-version") ?? "",
            "ffmpegVersion": core.string("ffmpeg-version") ?? "",
            "logs": core.logs(),
            "drawableSize": "\(Int(metalLayer.drawableSize.width))x\(Int(metalLayer.drawableSize.height))",
        ]
        if let headless {
            result["headlessWidth"] = headless.string("video-params/w") ?? ""
            result["headlessDuration"] = headless.double("duration") ?? -1
            result["headlessLogs"] = headless.logs()
        } else {
            result["headlessError"] = "second mpv instance did not initialise"
        }
        report(result)
    }

    private func report(_ result: [String: Any]) {
        let text = (try? JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys]))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "\(result)"
        label.text = "vo=\(result["currentVo"] ?? "") t=\(result["timePosAt6s"] ?? "") \(result["width"] ?? "")x\(result["height"] ?? "")"
            + (result["error"].map { "\n\($0)" } ?? "")
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? text.write(to: documents.appendingPathComponent("probe.json"), atomically: true, encoding: .utf8)
        print("PIKSEEK_PROBE_RESULT \(text)")
    }
}
