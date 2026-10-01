package dev.pikseek.security

import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 本进程主动连过的主机，只记主机名、用途与时刻，供用户自己核对「这个程序到底访问了哪里」。
 *
 * 记什么：每建一条新连接记一次（进程默认的 ProxySelector 每条新连接都会被问一次，SDK 的 API 与 CDN 客户端、
 * 图片加载都经过它），认证模块发请求时另外按用途记一次。复用着的连接不会再记，所以「最近」指最近一次新建连接。
 *
 * 不记什么：路径、查询串、请求头、请求体、响应。直链的签名与令牌都在这些地方，一个字节也不进来；
 * [recordUrl] 收的是整条 URL，但只取出主机名。
 *
 * 只存在内存里，不落盘、不上传；导出由用户在安全页点按钮触发。
 */
object NetworkAudit {
    class Entry(
        val host: String,
        val category: HostCategory,
        val purpose: String,
        val firstSeenMillis: Long,
        val lastSeenMillis: Long,
        /** 新建连接（或认证请求）的次数。 */
        val count: Long,
    )

    private class Record(val firstSeen: Long, @Volatile var lastSeen: Long, @Volatile var count: Long, @Volatile var purpose: String?)

    private val records = ConcurrentHashMap<String, Record>()
    private val revision = MutableStateFlow(0L)
    private val startedAt = System.currentTimeMillis()

    /** 每记一笔就变，界面据此重读 [snapshot]。 */
    val changes: StateFlow<Long> = revision.asStateFlow()

    /**
     * 记一次对 [host] 的访问。[purpose] 是调用方知道的具体用途，给了就留着，没给的按主机名归类说明。
     * 主机名为空（非法的 URI）时不记。
     */
    fun record(host: String?, purpose: String? = null, nowMillis: Long = System.currentTimeMillis()) {
        val name = host?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return
        val record = records.computeIfAbsent(name) { Record(nowMillis, nowMillis, 0, purpose) }
        synchronized(record) {
            record.lastSeen = nowMillis
            record.count += 1
            if (purpose != null) record.purpose = purpose
        }
        revision.value += 1
    }

    /** 只取 [url] 的主机名记下，其余部分丢弃。交给播放器直接读的直链这样记。 */
    fun recordUrl(url: String, purpose: String? = null) {
        record(SecretRedactor.hostOf(url), purpose)
    }

    fun snapshot(): List<Entry> = records.entries
        .map { (host, record) ->
            Entry(
                host = host,
                category = HostClassifier.classify(host),
                purpose = record.purpose ?: HostClassifier.describe(host),
                firstSeenMillis = record.firstSeen,
                lastSeenMillis = record.lastSeen,
                count = record.count,
            )
        }
        // 要人留意的排最前，其余按类别、主机名
        .sortedWith(compareBy<Entry>({ it.category != HostCategory.Other }, { it.category.ordinal }, { it.host }))

    /** 非 PikPak、非本机的主机。安全页把它们标出来。 */
    fun unknownHosts(): List<String> = snapshot().filter { it.category == HostCategory.Other }.map { it.host }

    /** 纯文本报告。只有主机名、用途与时刻。 */
    fun exportText(nowMillis: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx").withZone(zone)
        fun at(millis: Long) = time.format(Instant.ofEpochMilli(millis))
        val entries = snapshot()
        return buildString {
            appendLine("PikSeek Network Audit")
            appendLine("generated:        ${at(nowMillis)}")
            appendLine("process started:  ${at(startedAt)}")
            appendLine("hosts:            ${entries.size}")
            appendLine("non-PikPak hosts: ${entries.count { it.category == HostCategory.Other }}")
            appendLine()
            appendLine("记录的是本进程新建连接时的目标主机名。不含路径、查询串、请求头与正文。")
            appendLine("播放器（mpv）只读本机回环代理；真正出网的读取由这里记下的连接完成。")
            appendLine()
            if (entries.isEmpty()) appendLine("（本次运行还没有访问过任何主机）")
            entries.forEach { entry ->
                appendLine(entry.host)
                appendLine("    category:   ${entry.category.label}")
                appendLine("    purpose:    ${entry.purpose}")
                appendLine("    first seen: ${at(entry.firstSeenMillis)}")
                appendLine("    last seen:  ${at(entry.lastSeenMillis)}")
                appendLine("    count:      ${entry.count}")
            }
        }
    }

    /** 测试用。 */
    internal fun clear() {
        records.clear()
        revision.value += 1
    }

    /** 给 ProxySelector 用：从它拿到的 URI 里取主机名。 */
    fun record(uri: URI) {
        record(uri.host)
    }
}
