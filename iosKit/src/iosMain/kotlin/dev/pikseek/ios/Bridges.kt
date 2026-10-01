package dev.pikseek.ios

import platform.Foundation.NSData
import platform.UIKit.UIView
import platform.UIKit.UIViewController

// 这个文件里的接口由 Swift 一侧实现（iosApp/Sources），程序启动时经 PikSeekIos.start 交进来。
// 只放系统能力本身：发一个 HTTPS 请求、读写钥匙串、驱动 libmpv、弹系统的面板。
// 规则与逻辑（认证白名单、播放状态机、缩略图排程）都在 Kotlin 这边，与桌面版是同一份代码。
// 类型都用最简单的：字符串、数字、NSData，Swift 那边不必认识 Kotlin 各模块里的类。

/** 一次 POST 的结果。[failure] 非空表示没拿到响应。 */
class HttpResult(
    val status: Int,
    val body: String,
    val failure: String?,
    /** 连接没建起来（请求没发出去）。 */
    val connectFailure: Boolean,
)

interface HttpBridge {
    /** 同步发一个 POST 并等结果（在后台线程上调）。不得跟随重定向，不得打印请求与响应。 */
    fun post(url: String, headers: Map<String, String>, body: NSData): HttpResult
}

/** 钥匙串读一项的结果。[status] 是系统的 OSStatus：0 成功，-25300 没有这一项。 */
class KeychainItem(val data: NSData?, val status: Int)

interface KeychainBridge {
    /** 写入（已有则覆盖）。返回 OSStatus，0 为成功。 */
    fun write(name: String, data: NSData): Int

    fun read(name: String): KeychainItem

    fun delete(name: String)

    fun names(): List<String>
}

/** libmpv 的事件，见 dev.pikseek.player.MpvListener。回调在 mpv 的事件线程上。 */
interface MpvEvents {
    fun onPropertyChanged(name: String, value: String?)

    fun onEvent(event: String, detail: String?)
}

/** 一个带画面的 libmpv 实例，见 dev.pikseek.player.MpvHandle。 */
interface MpvPlayerBridge {
    /** 画面所在的视图，放进界面里。 */
    fun view(): UIView

    fun command(arguments: List<String>): Boolean

    fun setProperty(name: String, value: String): Boolean

    fun getProperty(name: String): String?

    fun observe(name: String)

    fun setEvents(events: MpvEvents?)

    fun close()
}

/** `screenshot-raw` 取到的一帧：bgr0，每行 [stride] 字节。 */
class GrabbedFrame(
    val timeMs: Long,
    val width: Int,
    val height: Int,
    val stride: Int,
    val data: NSData,
    /** 旋转元数据（度）与该显示的尺寸，没有时为 0。 */
    val rotate: Int,
    val displayWidth: Int,
    val displayHeight: Int,
)

/** 一个不出画面、不出声音的 libmpv 实例，专门解码取帧。方法都会阻塞，在后台线程上调。 */
interface FrameGrabberBridge {
    fun durationMs(): Long

    /** [startSeconds] 小于 0 表示从头打开。停在第一帧上才返回 true。 */
    fun open(location: String, timeoutMs: Long, startSeconds: Double): Boolean

    fun grab(): GrabbedFrame?

    /** 跳到 [seconds] 之前最近的关键帧并取那一帧。 */
    fun seekAndGrab(seconds: Double, timeoutMs: Long): GrabbedFrame?

    fun close()
}

/** Swift 一侧提供的全部系统能力。 */
interface NativeServices {
    fun http(): HttpBridge

    fun keychain(): KeychainBridge

    /** 建一个带画面的播放器。建不出来返回 null。在界面线程上调。 */
    fun createPlayer(): MpvPlayerBridge?

    /** 建一个取帧解码器，帧缩到 [frameWidth] 宽。建不出来返回 null。 */
    fun createFrameGrabber(frameWidth: Int): FrameGrabberBridge?

    /** 弹系统的分享面板，把 [path] 这个文件交出去。 */
    fun share(path: String, from: UIViewController)

    /** 弹系统的文件选择器。选中的文件已拷进程序自己的临时目录，回调给的是那里的路径；取消时给空表。 */
    fun pickFiles(multiple: Boolean, from: UIViewController, onPicked: (List<String>) -> Unit)

    /** 程序版本号（Info.plist 里的）。 */
    fun appVersion(): String
}
