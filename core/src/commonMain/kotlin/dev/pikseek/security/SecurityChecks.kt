package dev.pikseek.security

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * 在数据目录里找明文凭据：真去读文件，而不是写死一个 0。
 *
 * 认的是凭据落盘时的样子：JSON 或 key=value 里的 access_token、refresh_token、password，以及 JWT。
 * 加密存储的密文是二进制，对不上这些样子；日志若真漏进了令牌，这里会把那个文件数出来。
 */
object PlaintextCredentialScan {
    class Result(val scannedFiles: Int, val suspicious: List<String>) {
        val count: Int get() = suspicious.size
    }

    private val PATTERNS = listOf(
        Regex("""(?i)"(access_token|refresh_token|password|passwd)"\s*:\s*"[^"]{6,}""""),
        Regex("""(?i)\b(access_token|refresh_token|password|passwd)\s*=\s*[^\s&]{6,}"""),
        Regex("""eyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}"""),
    )

    // 图片、视频切片与块缓存不会是文本凭据，文件又大又多，跳过
    private val SKIPPED_EXTENSIONS = setOf("webp", "jpg", "jpeg", "png", "ts", "mp4", "mkv", "blk", "bin", "dll", "jar", "aot")
    private const val MAX_BYTES = 4L * 1024 * 1024

    /** [root] 是数据目录的路径。结果里的可疑文件写成相对 [root] 的路径，用正斜杠分隔。 */
    fun scan(root: String): Result {
        val start = Path(root)
        if (SystemFileSystem.metadataOrNull(start)?.isDirectory != true) return Result(0, emptyList())
        var scanned = 0
        val suspicious = ArrayList<String>()

        fun visit(directory: Path, relative: String) {
            val children = runCatching { SystemFileSystem.list(directory) }.getOrDefault(emptyList())
            for (child in children) {
                val name = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                val metadata = SystemFileSystem.metadataOrNull(child) ?: continue
                if (metadata.isDirectory) {
                    visit(child, name)
                    continue
                }
                if (!metadata.isRegularFile) continue
                if (child.name.substringAfterLast('.', "").lowercase() in SKIPPED_EXTENSIONS) continue
                if (metadata.size == 0L || metadata.size > MAX_BYTES) continue
                val bytes = runCatching { SystemFileSystem.source(child).buffered().use { it.readByteArray() } }.getOrNull() ?: continue
                scanned++
                if (looksLikeCredential(bytes)) suspicious += name
            }
        }
        visit(start, "")
        return Result(scanned, suspicious)
    }

    /** 这段内容里有没有凭据的样子。 */
    fun looksLikeCredential(bytes: ByteArray): Boolean {
        // 按单字节读：不管原本是什么编码，ASCII 的键名与令牌都原样可见，二进制文件也不会抛
        val text = CharArray(bytes.size) { (bytes[it].toInt() and 0xff).toChar() }.concatToString()
        return PATTERNS.any { it.containsMatchIn(text) }
    }
}

/**
 * 程序里有没有常见的遥测、统计、崩溃上报库。PikSeek 一个都不依赖；各平台按自己的办法实际查一遍
 * （桌面查类路径，iOS 查链接进来的类），往后有人加依赖把它们带进来，安全页当场就会显示出来。
 *
 * 返回找到的库名，去重。空表示一个都没有。
 */
expect fun telemetryLibrariesPresent(): List<String>
