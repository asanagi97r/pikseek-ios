import UIKit
import UniformTypeIdentifiers
import PikSeekKit

/// Kotlin 一侧要用的全部系统能力，程序启动时交给 PikSeekIos.start。
final class SystemServices: NSObject, NativeServices {
    private let authHttp = AuthHttp()
    private let authKeychain = AuthKeychain()
    private var picker: FilePicker?

    func http() -> HttpBridge { authHttp }

    func keychain() -> KeychainBridge { authKeychain }

    func createPlayer() -> MpvPlayerBridge? { MpvPlayer.make() }

    func createFrameGrabber(frameWidth: Int32) -> FrameGrabberBridge? { MpvFrameGrabber.make(frameWidth: frameWidth) }

    func share(path: String, from: UIViewController) {
        let sheet = UIActivityViewController(activityItems: [URL(fileURLWithPath: path)], applicationActivities: nil)
        // iPad 上分享面板是气泡，要给它一个锚点
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = from.view
            popover.sourceRect = CGRect(x: from.view.bounds.midX, y: from.view.bounds.midY, width: 0, height: 0)
            popover.permittedArrowDirections = []
        }
        from.present(sheet, animated: true)
    }

    func pickFiles(multiple: Bool, from: UIViewController, onPicked: @escaping ([String]) -> Void) {
        let picker = FilePicker { [weak self] paths in
            self?.picker = nil
            onPicked(paths)
        }
        self.picker = picker
        picker.present(multiple: multiple, from: from)
    }

    func appVersion() -> String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "开发版"
    }
}

/// 系统的文件选择器。选中的文件由系统先拷一份给我们（asCopy），再挪进程序自己的临时目录，
/// 之后 Kotlin 一侧读的就是沙盒里的普通文件；上传完由它删掉。
private final class FilePicker: NSObject, UIDocumentPickerDelegate {
    private let done: ([String]) -> Void

    init(done: @escaping ([String]) -> Void) {
        self.done = done
    }

    func present(multiple: Bool, from: UIViewController) {
        let controller = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        controller.allowsMultipleSelection = multiple
        controller.delegate = self
        from.present(controller, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        let manager = FileManager.default
        let folder = manager.temporaryDirectory.appendingPathComponent("PikSeek/uploads/\(UUID().uuidString)", isDirectory: true)
        try? manager.createDirectory(at: folder, withIntermediateDirectories: true)
        var paths: [String] = []
        for url in urls {
            let target = folder.appendingPathComponent(url.lastPathComponent)
            do {
                try manager.moveItem(at: url, to: target)
                paths.append(target.path)
            } catch {
                // 挪不动就原地用：系统给的这份副本本来就在我们的沙盒里
                paths.append(url.path)
            }
        }
        done(paths)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        done([])
    }
}
