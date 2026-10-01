package dev.pikseek.security

/** 一个主机属于哪一类。界面与导出的报告按它分组，[Other] 是要人留意的。 */
enum class HostCategory(val label: String) {
    PikPakAuth("PikPak 认证"),
    PikPakApi("PikPak 文件 API"),
    PikPakCdn("PikPak 取流 / 下载"),
    PikPakOther("PikPak 其他"),
    Upload("上传（PikPak 下发的对象存储）"),
    Loopback("本机回环"),
    Other("非 PikPak 域名"),
}

/**
 * 按主机名归类，纯函数。
 *
 * PikPak 的 API 有四个根域名，指向同一组服务器（Piko 的 SDK 有实测记录）：登录与刷新走 `user.<根>`，
 * 文件接口走 `api-drive.<根>`，直链在各边缘节点的子域名下。这四个之外的一律算「非 PikPak 域名」，
 * 哪怕名字里带着 pikpak：`mypikpak.com.evil.example` 的根不是 mypikpak.com。
 */
object HostClassifier {
    /** PikPak 官方网页端自己列出的四个 API 根域名。 */
    val OFFICIAL_ROOTS: List<String> = listOf("mypikpak.com", "mypikpak.net", "pikpak.me", "pikpakdrive.com")

    private val LOOPBACK_V4 = Regex("""127\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}""")

    fun classify(host: String): HostCategory {
        val name = normalize(host)
        if (isLoopback(name)) return HostCategory.Loopback
        val root = officialRootOf(name)
        if (root != null) {
            return when (name.removeSuffix(root).removeSuffix(".")) {
                "user" -> HostCategory.PikPakAuth
                "api-drive" -> HostCategory.PikPakApi
                "" -> HostCategory.PikPakOther
                else -> if (looksLikeEdge(name)) HostCategory.PikPakCdn else HostCategory.PikPakOther
            }
        }
        // 上传凭据里的端点由 PikPak 的接口下发，是阿里云的对象存储
        if (name.endsWith(".aliyuncs.com")) return HostCategory.Upload
        return HostCategory.Other
    }

    /** 用途说明。调用方知道得更具体时（例如认证模块）自己给，这里是按主机名能说出的最多。 */
    fun describe(host: String): String = when (classify(host)) {
        HostCategory.PikPakAuth -> "Auth：登录、刷新会话、验证令牌"
        HostCategory.PikPakApi -> "File API：文件列表、详情、离线任务"
        HostCategory.PikPakCdn -> "CDN：视频取流、下载、缩略图"
        HostCategory.PikPakOther -> "PikPak 官方域名下的其他服务"
        HostCategory.Upload -> "上传文件内容"
        HostCategory.Loopback -> "播放器读本机代理，不出本机"
        HostCategory.Other -> "未归类，请核对"
    }

    /** [host] 所在的官方根域名，不在四个之内时为 null。只认整段后缀，不认子串。 */
    fun officialRootOf(host: String): String? {
        val name = normalize(host)
        return OFFICIAL_ROOTS.firstOrNull { name == it || name.endsWith(".$it") }
    }

    fun isLoopback(host: String): Boolean {
        val name = normalize(host)
        return name == "localhost" || name == "::1" || name == "[::1]" || LOOPBACK_V4.matches(name)
    }

    // 直链与图片的主机都是带编号的边缘节点（dl-a10b-0621、vod-…、sg-thumbnail-drive…）
    private fun looksLikeEdge(host: String): Boolean {
        val label = host.substringBefore('.')
        return label.startsWith("dl-") || label.startsWith("vod") || label.contains("thumbnail") ||
            label.contains("download") || label.contains("media") || label.any { it.isDigit() }
    }

    private fun normalize(host: String): String = host.trim().trimEnd('.').lowercase()
}
