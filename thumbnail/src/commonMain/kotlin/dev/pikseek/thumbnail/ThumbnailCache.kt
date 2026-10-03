package dev.pikseek.thumbnail

import dev.pikseek.platform.currentTimeMillis
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一个视频的预览索引（index.json）。每一帧记着它的真实时刻与在哪张雪碧图的哪个位置；
 * 悬停时按时刻二分找最近的一帧，从雪碧图里裁出来，不碰网络。
 */
@Serializable
data class SpriteIndex(
    val version: Int,
    val fileId: String,
    val durationMs: Long,
    /** [MediaFingerprint.cacheKey]，目录名也是它。 */
    val sourceFingerprint: String,
    /** 每帧的宽高，像素。 */
    val width: Int,
    val height: Int,
    val columns: Int,
    val rows: Int,
    /** 计划的总格数。帧数到了它就是做完了。 */
    val slotCount: Int,
    /** 画面取自哪里，例如「480P 转码流」。只作说明。 */
    val source: String = "",
    val frames: List<Frame> = emptyList(),
) {
    @Serializable
    data class Frame(
        val slot: Int,
        val timeMs: Long,
        /** 雪碧图的序号，文件名是 sheet-<三位序号>.<扩展名>。 */
        val sheet: Int,
        val x: Int,
        val y: Int,
    )

    val isComplete: Boolean get() = frames.size >= slotCount
}

/** 磁盘上一个视频的预览概况：按多少格做的、做了几格。 */
class StoredPreview internal constructor(index: SpriteIndex) {
    val slotCount: Int = index.slotCount
    val durationMs: Long = index.durationMs
    val frameCount: Int = index.frames.map { it.slot }.distinct().size
    val source: String = index.source

    /** 照着它做的那份计划：沿用它的格数与时长，补齐时与已有的帧对得上。 */
    val plan: ThumbnailPlan get() = ThumbnailPlan(durationMs, slotCount)

    val isComplete: Boolean get() = frameCount >= slotCount
}

/** 文件的修改时刻，毫秒；读不到为 0。缓存拿索引文件的修改时刻当「最近一次用到」。 */
internal expect fun fileLastModifiedMillis(path: String): Long

/** 把文件的修改时刻设成 [millis]。设不了就算了。 */
internal expect fun touchFile(path: String, millis: Long)

/**
 * 缩略图的磁盘缓存：每个视频一个目录（名字是指纹的摘要），里面是 index.json 与若干张雪碧图。
 *
 * 一张雪碧图 [COLUMNS] x [ROWS] 格。第 n 格放在第 n / 25 张图的第 n % 25 个位置，
 * 所以帧不按顺序生成也没关系，哪张图里添了新帧就重写哪张。
 *
 * 总量超过上限时按最近使用时间清理，最久没看的先删。
 *
 * @param root 缓存根目录的路径
 */
class ThumbnailCache(private val root: String, private val codec: SpriteCodec) {
    private val json = Json { ignoreUnknownKeys = true }
    private val rootPath = Path(root)

    /** [fingerprint] 的缓存目录。 */
    fun directoryPath(fingerprint: MediaFingerprint): String = directory(fingerprint).toString()

    private fun directory(fingerprint: MediaFingerprint): Path = Path(rootPath, fingerprint.cacheKey())

    /**
     * 磁盘上这个视频有没有预览、按多少格做的、做了几格。只读索引，不解图。没有或读不懂时为 null。
     */
    fun stored(fingerprint: MediaFingerprint): StoredPreview? = readIndex(directory(fingerprint))?.let(::StoredPreview)

    /**
     * 读出已有的缓存。没有、版本不对、或图坏了，返回 null（读不懂的目录顺带删掉）。
     * 格数或时长与 [plan] 不同也返回 null，但不删：那是另一档的预览，删不删由调用方定。
     * 顺带把它记为刚用过。
     */
    fun load(fingerprint: MediaFingerprint, plan: ThumbnailPlan): Map<Int, ThumbnailFrame>? {
        val directory = directory(fingerprint)
        val indexFile = Path(directory, INDEX)
        if (SystemFileSystem.metadataOrNull(indexFile)?.isRegularFile != true) return null
        val index = readIndex(directory)
        if (index == null) {
            delete(directory)
            return null
        }
        if (index.slotCount != plan.slotCount || index.durationMs != plan.durationMs) return null
        val frames = HashMap<Int, ThumbnailFrame>()
        for ((sheetNumber, entries) in index.frames.groupBy { it.sheet }) {
            val file = Path(directory, sheetName(sheetNumber))
            val sprite = runCatching { codec.decode(read(file)) }.getOrNull()
            if (sprite == null || sprite.width != index.width * COLUMNS || sprite.height != index.height * ROWS) {
                // 这一张读不出来：里面的帧当作没有，引擎会重新生成
                continue
            }
            for (entry in entries) {
                if (entry.slot !in 0 until plan.slotCount) continue
                frames[entry.slot] = ThumbnailFrame(entry.timeMs, index.width, index.height, crop(sprite, entry.x, entry.y, index.width, index.height))
            }
        }
        runCatching { touchFile(indexFile.toString(), currentTimeMillis()) }
        return frames
    }

    /**
     * 把 [frames] 里属于 [sheets] 的那几张雪碧图重写，再写索引。先写图后写索引：
     * 索引里提到的帧，图里一定已经有了。每个文件先写到临时名再改名换上。
     */
    fun save(fingerprint: MediaFingerprint, plan: ThumbnailPlan, source: String, frames: Map<Int, ThumbnailFrame>, sheets: Set<Int>) {
        val first = frames.values.firstOrNull() ?: return
        val width = first.width
        val height = first.height
        val directory = directory(fingerprint)
        SystemFileSystem.createDirectories(directory)
        for (sheetNumber in sheets) {
            val pixels = IntArray(width * COLUMNS * height * ROWS)
            var any = false
            for (cell in 0 until COLUMNS * ROWS) {
                val frame = frames[sheetNumber * COLUMNS * ROWS + cell] ?: continue
                if (frame.width != width || frame.height != height) continue
                any = true
                val originX = cell % COLUMNS * width
                val originY = cell / COLUMNS * height
                for (row in 0 until height) {
                    frame.pixels.copyInto(pixels, (originY + row) * width * COLUMNS + originX, row * width, row * width + width)
                }
            }
            if (any) replace(Path(directory, sheetName(sheetNumber)), codec.encode(width * COLUMNS, height * ROWS, pixels))
        }
        val index = SpriteIndex(
            version = MediaFingerprint.FORMAT_VERSION,
            fileId = fingerprint.fileId,
            durationMs = plan.durationMs,
            sourceFingerprint = fingerprint.cacheKey(),
            width = width,
            height = height,
            columns = COLUMNS,
            rows = ROWS,
            slotCount = plan.slotCount,
            source = source,
            frames = frames.entries
                .filter { it.value.width == width && it.value.height == height }
                .sortedBy { it.value.timeMs }
                .map { (slot, frame) ->
                    val cell = slot % (COLUMNS * ROWS)
                    SpriteIndex.Frame(slot, frame.timeMs, slot / (COLUMNS * ROWS), cell % COLUMNS * width, cell / COLUMNS * height)
                },
        )
        replace(Path(directory, INDEX), json.encodeToString(SpriteIndex.serializer(), index).encodeToByteArray())
    }

    /** [fingerprint] 的缓存在磁盘上占多少字节。 */
    fun sizeOf(fingerprint: MediaFingerprint): Long = sizeOf(directory(fingerprint))

    /** 删掉 [fingerprint] 的缓存。 */
    fun delete(fingerprint: MediaFingerprint) = delete(directory(fingerprint))

    /**
     * 这个视频的整份预览（索引与它提到的雪碧图）打成一个包，见 [PreviewPack]。没有缓存时为 null。
     */
    fun exportPack(fingerprint: MediaFingerprint): ByteArray? {
        val directory = directory(fingerprint)
        val index = readIndex(directory) ?: return null
        val files = ArrayList<Pair<String, ByteArray>>()
        files += INDEX to read(Path(directory, INDEX))
        for (sheet in index.frames.map { it.sheet }.distinct().sorted()) {
            val file = Path(directory, sheetName(sheet))
            if (SystemFileSystem.metadataOrNull(file)?.isRegularFile != true) return null
            files += file.name to read(file)
        }
        return PreviewPack.encode(files)
    }

    /**
     * 把 [exportPack] 打出的包解进 [fingerprint] 的缓存目录，换掉原有的。包坏了、版本不对时不动原有的，返回 null。
     */
    fun importPack(fingerprint: MediaFingerprint, bytes: ByteArray): StoredPreview? {
        val files = PreviewPack.decode(bytes) ?: return null
        val indexBytes = files.firstOrNull { it.first == INDEX }?.second ?: return null
        val index = parseIndex(indexBytes) ?: return null
        val sheetNames = index.frames.map { sheetName(it.sheet) }.toSet()
        // 包里只该有索引与它提到的雪碧图；别的名字一概不写，也就写不到缓存目录外面去
        val sheets = files.filter { it.first in sheetNames }
        if (sheets.size != sheetNames.size) return null
        val directory = directory(fingerprint)
        delete(directory)
        SystemFileSystem.createDirectories(directory)
        sheets.forEach { (name, data) -> replace(Path(directory, name), data) }
        replace(Path(directory, INDEX), indexBytes)
        return StoredPreview(index)
    }

    /** 全部缓存占多少字节。 */
    fun totalBytes(): Long = entries().sumOf { sizeOf(it) }

    /**
     * 总量超过 [limitBytes] 时，从最久没用的删起，直到不超。[keep] 是正在看的那个视频，不删。
     * [limitBytes] 为 0 表示不限。返回删掉的字节数。
     */
    fun trim(limitBytes: Long, keep: MediaFingerprint? = null): Long {
        if (limitBytes <= 0) return 0
        val kept = keep?.let(::directory)
        val all = entries().map { Triple(it, sizeOf(it), lastUsed(it)) }
        var total = all.sumOf { it.second }
        var freed = 0L
        for ((directory, size, _) in all.sortedBy { it.third }) {
            if (total <= limitBytes) break
            if (directory == kept) continue
            delete(directory)
            total -= size
            freed += size
        }
        return freed
    }

    /** 清空全部缓存。返回删掉的字节数。 */
    fun clear(): Long {
        val total = totalBytes()
        entries().forEach(::delete)
        return total
    }

    private fun entries(): List<Path> {
        if (SystemFileSystem.metadataOrNull(rootPath)?.isDirectory != true) return emptyList()
        return SystemFileSystem.list(rootPath).filter { SystemFileSystem.metadataOrNull(it)?.isDirectory == true }
    }

    private fun sizeOf(directory: Path): Long {
        if (SystemFileSystem.metadataOrNull(directory)?.isDirectory != true) return 0
        return runCatching {
            SystemFileSystem.list(directory).sumOf { file ->
                SystemFileSystem.metadataOrNull(file)?.takeIf { it.isRegularFile }?.size ?: 0L
            }
        }.getOrDefault(0L)
    }

    private fun lastUsed(directory: Path): Long = runCatching { fileLastModifiedMillis(Path(directory, INDEX).toString()) }.getOrDefault(0L)

    private fun delete(directory: Path) {
        // 只删缓存根目录底下的东西
        if (directory.parent != rootPath) return
        runCatching {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it, mustExist = false) }
            SystemFileSystem.delete(directory, mustExist = false)
        }
    }

    private fun read(file: Path): ByteArray = SystemFileSystem.source(file).buffered().use { it.readByteArray() }

    private fun readIndex(directory: Path): SpriteIndex? {
        val file = Path(directory, INDEX)
        if (SystemFileSystem.metadataOrNull(file)?.isRegularFile != true) return null
        return runCatching { read(file) }.getOrNull()?.let(::parseIndex)
    }

    /** 解析索引并检查它是这一版程序读得懂的。 */
    private fun parseIndex(bytes: ByteArray): SpriteIndex? {
        val index = runCatching { json.decodeFromString(SpriteIndex.serializer(), bytes.decodeToString()) }.getOrNull() ?: return null
        val usable = index.version == MediaFingerprint.FORMAT_VERSION &&
            index.columns == COLUMNS && index.rows == ROWS &&
            index.width > 0 && index.height > 0 && index.slotCount > 0 && index.durationMs > 0 &&
            index.frames.all { it.slot in 0 until index.slotCount && it.sheet >= 0 }
        return index.takeIf { usable }
    }

    private fun replace(target: Path, bytes: ByteArray) {
        val staging = Path(target.parent ?: rootPath, target.name + ".tmp")
        try {
            SystemFileSystem.sink(staging).buffered().use { it.write(bytes) }
            SystemFileSystem.atomicMove(staging, target)
        } finally {
            SystemFileSystem.delete(staging, mustExist = false)
        }
    }

    private fun sheetName(number: Int): String = "sheet-${number.toString().padStart(3, '0')}.${codec.extension}"

    private fun crop(sprite: DecodedSprite, x: Int, y: Int, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        for (row in 0 until height) {
            val from = (y + row) * sprite.width + x
            sprite.pixels.copyInto(out, row * width, from, from + width)
        }
        return out
    }

    companion object {
        const val COLUMNS = 5
        const val ROWS = 5
        const val FRAMES_PER_SHEET = COLUMNS * ROWS
        private const val INDEX = "index.json"
    }
}
