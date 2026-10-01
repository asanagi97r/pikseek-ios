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
        window.rootViewController = PikSeekIos.shared.mainViewController()
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}
