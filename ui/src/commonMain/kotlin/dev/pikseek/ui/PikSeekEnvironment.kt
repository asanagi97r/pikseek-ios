package dev.pikseek.ui

import androidx.compose.runtime.staticCompositionLocalOf
import dev.pikseek.platform.AppSettings
import dev.pikseek.ui.preview.CloudPack
import dev.pikseek.ui.preview.PreviewCacheRequest
import dev.pikseek.ui.preview.PreviewJobsState
import kotlinx.coroutines.flow.StateFlow

/**
 * PikSeek 自己加的那部分界面要用的进程级对象，由入口拼好，经 [LocalPikSeek] 交给界面。
 * 与 Piko 的 PikoServices 分开：那一份里的东西都与网盘有关，这一份是预览、拖动与性能浮层的设置。
 */
class PikSeekEnvironment(
    val settings: AppSettings,
    val previewCache: PreviewCacheControl,
    val previewPacks: PreviewPackControl,
)

/**
 * 存在网盘上的预览缓存，界面能做的事：看每个视频做到了哪、开始做一批、停下。
 * 网盘文件列表的角标与「预览缓存」按钮经 [LocalPreviewPacks] 拿到它。
 */
interface PreviewPackControl {
    /** 网盘上已有的预览包，gcid → 包。 */
    val packs: StateFlow<Map<String, CloudPack>>

    /** 正在做的视频，gcid → 做到几成。 */
    val live: StateFlow<Map<String, Float>>

    val jobs: StateFlow<PreviewJobsState>

    /** 重列网盘上的预览缓存（列过不久就不重列）。 */
    fun refresh()

    fun start(request: PreviewCacheRequest)

    fun cancel()
}

/**
 * 没提供时为 null：网盘页以外、或别的窗口里也会画文件列表，那里不画角标也不给按钮，不该因此崩掉。
 */
val LocalPreviewPacks = staticCompositionLocalOf<PreviewPackControl?> { null }

/** 设置页对预览缓存能做的事：看占了多少、清空、按上限清理。 */
interface PreviewCacheControl {
    suspend fun totalBytes(): Long

    /** 返回清掉的字节数。 */
    suspend fun clear(): Long

    /** 上限改了之后按新上限清理一次。 */
    suspend fun trim(limitBytes: Long)
}

val LocalPikSeek = staticCompositionLocalOf<PikSeekEnvironment> {
    error("PikSeekEnvironment 未提供，入口要用 CompositionLocalProvider 包一层")
}
