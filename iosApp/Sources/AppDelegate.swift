import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        // 自检：环境变量 PIKSEEK_PROBE 给出要播的样片名，播几秒后把结果写进 Documents/probe.json
        let sample = ProcessInfo.processInfo.environment["PIKSEEK_PROBE"] ?? "control-mpeg4-aac.mp4"
        window.rootViewController = PlayerProbeViewController(sample: sample)
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}
