package dev.pikseek.thumbnail

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 「讨厌」名单：按 gcid 记，存到网盘上预览缓存的文件夹里，全账号只有这一个文件（[FILE_NAME]）。
 * 「收藏」不在这里，它就是网盘自带的星标。
 *
 * 按 gcid 而不是文件 ID：同一个视频再存一份（换个文件夹、换个名字），照样认得出是讨厌过的。
 *
 * 几台设备各改各的，传上去之前先与网盘上的那份合并（[merge]）：每个视频以最后一次改动为准。
 * 取消讨厌也记一笔（[Entry.disliked] 为 false），不然合并时另一台设备上的旧名单会把它加回来。
 */
@Serializable
data class RatingBook(
    val version: Int = VERSION,
    /** gcid（大写）→ 最后一次改动。 */
    val entries: Map<String, Entry> = emptyMap(),
) {
    @Serializable
    data class Entry(val disliked: Boolean, val atMs: Long)

    fun isDisliked(gcid: String): Boolean = gcid.isNotBlank() && entries[gcid.uppercase()]?.disliked == true

    val dislikedCount: Int get() = entries.values.count { it.disliked }

    /** 改一个视频，[atMs] 是改的时刻。 */
    fun with(gcid: String, disliked: Boolean, atMs: Long): RatingBook {
        if (gcid.isBlank()) return this
        return copy(entries = entries + (gcid.uppercase() to Entry(disliked, atMs)))
    }

    /** 两份合并，每个视频取改得晚的那一笔。 */
    fun merge(other: RatingBook): RatingBook {
        val merged = HashMap(entries)
        for ((gcid, entry) in other.entries) {
            val mine = merged[gcid]
            if (mine == null || entry.atMs > mine.atMs) merged[gcid] = entry
        }
        return RatingBook(entries = merged)
    }

    /** 取消讨厌留下的记录过了 [keepMs] 就丢掉：别的设备早该同步过了，名单不必一直长下去。 */
    fun pruned(nowMs: Long, keepMs: Long = CLEARED_KEEP_MS): RatingBook =
        copy(entries = entries.filterValues { it.disliked || nowMs - it.atMs < keepMs })

    fun encode(): ByteArray = json.encodeToString(serializer(), this).encodeToByteArray()

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "ratings.psratings"

        private const val CLEARED_KEEP_MS = 180L * 24 * 3600 * 1000

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** 是不是名单文件。网盘给同名文件加的「(1)」也算。 */
        fun isFileName(name: String): Boolean {
            val extension = FILE_NAME.substringAfterLast('.')
            if (!name.endsWith(".$extension")) return false
            return withoutCopyNumber(name.removeSuffix(".$extension")) == FILE_NAME.substringBeforeLast('.')
        }

        /** 读不懂或版本更新时为 null。 */
        fun decode(bytes: ByteArray): RatingBook? {
            val book = runCatching { json.decodeFromString(serializer(), bytes.decodeToString()) }.getOrNull() ?: return null
            if (book.version > VERSION) return null
            return RatingBook(entries = book.entries.mapKeys { it.key.uppercase() })
        }
    }
}
