import UIKit
import AVFoundation
import PikSeekKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private let services = SystemServices()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // 看视频时静音拨片不该把声音关掉
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)

        // 界面与全部逻辑在 Kotlin 一侧（PikSeekKit）；这里只把系统能力交进去
        PikSeekIos.shared.start(native: services)

        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = selfTestController() ?? PikSeekIos.shared.mainViewController()
        window.makeKeyAndVisible()
        self.window = window
        return true
    }

    /// 打包流程在模拟器里带着环境变量 PIKSEEK_SELFTEST 启动：不进正常界面，跑一遍自检，结果写到 Documents/selftest.txt。
    private func selfTestController() -> UIViewController? {
        guard ProcessInfo.processInfo.environment["PIKSEEK_SELFTEST"] != nil,
              let mp4 = Bundle.main.path(forResource: "control-mpeg4-aac", ofType: "mp4"),
              let wmv = Bundle.main.path(forResource: "wmv2-wmav2", ofType: "wmv")
        else { return nil }
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return PikSeekIos.shared.selfTestViewController(
            sample: mp4,
            otherSample: wmv,
            resultPath: documents.appendingPathComponent("selftest.txt").path
        )
    }
}
