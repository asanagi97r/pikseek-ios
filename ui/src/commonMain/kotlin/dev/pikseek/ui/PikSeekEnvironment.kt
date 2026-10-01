package dev.pikseek.ui

import androidx.compose.runtime.staticCompositionLocalOf
import dev.pikseek.platform.AppSettings

/**
 * PikSeek 自己加的那部分界面要用的进程级对象，由入口拼好，经 [LocalPikSeek] 交给界面。
 * 与 Piko 的 PikoServices 分开：那一份里的东西都与网盘有关，这一份是预览、拖动与性能浮层的设置。
 */
class PikSeekEnvironment(
    val settings: AppSettings,
    val previewCache: PreviewCacheControl,
)

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
