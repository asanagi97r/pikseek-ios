package dev.pikseek.thumbnail

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * 一个视频的整份预览打成的单个文件：索引加雪碧图，原样放在一起。存到网盘上，换台设备、换个地方登录都能直接用。
 *
 * 格式：`PSPK`、版本号（4 字节），文件个数（4 字节），然后每个文件是名字长度（2 字节）、UTF-8 名字、
 * 内容长度（4 字节）、内容。整数都是大端。不压缩：雪碧图本身是 WebP，再压也小不了。
 */
object PreviewPack {
    private const val MAGIC = 0x5053504B // "PSPK"
    private const val VERSION = 1
    private const val MAX_FILES = 512
    private const val MAX_NAME = 128
    private const val MAX_FILE_BYTES = 32 * 1024 * 1024

    fun encode(files: List<Pair<String, ByteArray>>): ByteArray {
        val buffer = Buffer()
        buffer.writeInt(MAGIC)
        buffer.writeInt(VERSION)
        buffer.writeInt(files.size)
        for ((name, data) in files) {
            val nameBytes = name.encodeToByteArray()
            require(nameBytes.size in 1..MAX_NAME) { "文件名太长：$name" }
            buffer.writeShort(nameBytes.size.toShort())
            buffer.write(nameBytes)
            buffer.writeInt(data.size)
            buffer.write(data)
        }
        return buffer.readByteArray()
    }

    /** 解不开（不是这种包、版本不认识、长度对不上）时为 null。 */
    fun decode(bytes: ByteArray): List<Pair<String, ByteArray>>? = runCatching {
        val buffer = Buffer().apply { write(bytes) }
        if (buffer.readInt() != MAGIC || buffer.readInt() != VERSION) return null
        val count = buffer.readInt()
        if (count !in 1..MAX_FILES) return null
        val files = ArrayList<Pair<String, ByteArray>>(count)
        repeat(count) {
            val nameLength = buffer.readShort().toInt()
            if (nameLength !in 1..MAX_NAME) return null
            val name = buffer.readByteArray(nameLength).decodeToString()
            val size = buffer.readInt()
            if (size !in 0..MAX_FILE_BYTES) return null
            files += name to buffer.readByteArray(size)
        }
        if (!buffer.exhausted()) return null
        files
    }.getOrNull()
}

/**
 * 网盘上预览包的文件名：`<gcid>_<档次>_<已做格数>of<总格数>.pspreview`，例如 `ABC…123_M_120of120.pspreview`。
 *
 * 认视频只靠 gcid（PikPak 的内容哈希）：视频改名、挪到别的文件夹都还是它。档次与进度写在名字里，
 * 列一次文件夹就知道每个视频的预览做到了哪，不必把包一个个读出来。
 */
data class PreviewPackName(
    val gcid: String,
    val density: PreviewDensity,
    val done: Int,
    val total: Int,
) {
    val fileName: String get() = "${gcid.uppercase()}_${density.letter}_${done}of$total$EXTENSION"

    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    val isComplete: Boolean get() = total in 1..done

    companion object {
        const val EXTENSION = ".pspreview"

        /** 不是这种名字时为 null。不用正则：各平台的正则引擎认的写法不一样，这里拆字符串就够了。 */
        fun parse(fileName: String): PreviewPackName? {
            if (!fileName.endsWith(EXTENSION)) return null
            // 网盘给重名的上传另起的名字带「(1)」这样的序号：照样认
            val parts = withoutCopyNumber(fileName.removeSuffix(EXTENSION)).split('_')
            if (parts.size != 3) return null
            val (gcid, letter, progress) = parts
            if (gcid.length != 40 || !gcid.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) return null
            val density = PreviewDensity.entries.firstOrNull { it.letter == letter } ?: return null
            val counts = progress.split("of")
            if (counts.size != 2) return null
            val done = counts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val total = counts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return PreviewPackName(gcid.uppercase(), density, done.coerceAtMost(total), total)
        }
    }
}

/** 去掉末尾「(数字)」样的序号：网盘给重名的上传另起名字时加的。 */
internal fun withoutCopyNumber(stem: String): String {
    if (!stem.endsWith(')')) return stem
    val open = stem.lastIndexOf('(')
    if (open < 0 || open == stem.length - 2) return stem
    val digits = stem.substring(open + 1, stem.length - 1)
    return if (digits.all { it in '0'..'9' }) stem.substring(0, open).trimEnd() else stem
}

/** 档次在文件名里的写法。 */
val PreviewDensity.letter: String
    get() = when (this) {
        PreviewDensity.Low -> "L"
        PreviewDensity.Medium -> "M"
        PreviewDensity.High -> "H"
    }
