package dev.pikseek.thumbnail

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

internal actual fun fileLastModifiedMillis(path: String): Long =
    runCatching { Files.getLastModifiedTime(Path.of(path)).toMillis() }.getOrDefault(0L)

internal actual fun touchFile(path: String, millis: Long) {
    runCatching { Files.setLastModifiedTime(Path.of(path), FileTime.fromMillis(millis)) }
}

// 桌面一侧的代码手里拿的是 java.nio 的 Path

fun ThumbnailCache(root: Path, codec: SpriteCodec): ThumbnailCache = ThumbnailCache(root.toString(), codec)

fun ThumbnailCache.directoryOf(fingerprint: MediaFingerprint): Path = Path.of(directoryPath(fingerprint))

fun TsSliceSource(
    reader: ByteRangeReader,
    durationMs: Long,
    grabber: FrameGrabber,
    tempDirectory: Path,
    description: String,
): TsSliceSource = TsSliceSource(reader, durationMs, grabber, tempDirectory.toString(), description)
