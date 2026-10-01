package dev.pikseek.desktop

import dev.piko.shared.media.PlayableMediaInfo
import dev.piko.ui.PikoServices
import dev.pikseek.platform.AppPaths
import dev.pikseek.platform.AppSettings
import dev.pikseek.thumbnail.MpvFrameGrabber
import dev.pikseek.thumbnail.ThumbnailEngine
import dev.pikseek.ui.PikSeekEnvironment
import dev.pikseek.ui.PreviewRuntime
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.flow.StateFlow

/**
 * 桌面版的预览运行时：逻辑在共用的 [PreviewRuntime] 里，这里只给出桌面的目录与解码器
 * （经 FFM 直调程序自带的 libmpv，与主播放器用的是同一份）。
 */
class PikSeekRuntime(val settings: AppSettings, services: PikoServices) {
    /** 程序自带的 libmpv 所在的目录。没有时（开发时资源没备好）做不了预览。 */
    private val mpvDirectory: Path? = System.getProperty("compose.application.resources.dir")
        ?.let { File(it, "mpv") }
        ?.takeIf { File(it, "libmpv-2.dll").isFile }
        ?.toPath()

    private val runtime = PreviewRuntime(
        settings = settings,
        services = services,
        cacheDirectory = AppPaths.thumbnails.toString(),
        tempDirectory = AppPaths.temp.resolve("thumbs").toString(),
        newGrabber = { mpvDirectory?.let(::MpvFrameGrabber) },
        fileLength = { File(it).length() },
    )

    val environment: PikSeekEnvironment get() = runtime.environment

    val canGenerate: Boolean get() = mpvDirectory != null

    /** 见 [PreviewRuntime.openSession]。 */
    fun openSession(
        fileId: String,
        info: PlayableMediaInfo?,
        localPath: String?,
        durationMs: Long,
        position: () -> Long,
        busy: StateFlow<Boolean>,
        parallelism: StateFlow<Int>,
    ): ThumbnailEngine.Session? {
        if (mpvDirectory == null) return null
        return runtime.openSession(fileId, info, localPath, durationMs, position, busy, parallelism)
    }
}
