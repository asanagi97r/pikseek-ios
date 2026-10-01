package dev.pikseek.security

/**
 * 把一段文本里的机密抹掉：令牌、密码、Cookie、Authorization 头、带签名的查询串、邮箱。
 *
 * 是兜底，不是许可：写日志、出报告的代码本来就不该把机密拼进字符串，这里只保证万一拼进去了也出不了本机。
 * 导出的安全报告与网络审计在写出前都过一遍。
 */
object SecretRedactor {
    private const val MASK = "[已隐去]"

    // 三段以点相连的 base64url，头一段以 eyJ 开头：PikPak 的访问令牌是 JWT
    private val JWT = Regex("""eyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}""")
    private val BEARER = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")

    // "key": "value" 与 key=value 两种写法；键名不分大小写
    private const val SECRET_KEYS =
        "access_token|refresh_token|id_token|captcha_token|captcha_sign|password|passwd|pwd|client_secret|" +
            "authorization|cookie|set-cookie|x-captcha-token|token|signature|sign|secret"
    private val JSON_PAIR = Regex("""(?i)("(?:$SECRET_KEYS)"\s*:\s*)"(?:[^"\\]|\\.)*"""")
    // 值至少六个字符才算：报告里「token: 无」这类说明不该被当成机密抹掉
    private val PLAIN_PAIR = Regex("""(?i)\b($SECRET_KEYS)(\s*[=:]\s*)(?!\[已隐去])[^\s&;,"'}\]]{6,}""")
    private val URL_QUERY = Regex("""(https?://[^\s"'<>?#]+)\?[^\s"'<>]*""")
    private val EMAIL = Regex("""\b([A-Za-z0-9])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\.[A-Za-z]{2,})\b""")

    // 没有键名可认的长串：四十个字符以上的 base64 或十六进制，令牌与签名的样子
    private val OPAQUE = Regex("""\b[A-Za-z0-9_+/=-]{40,}\b""")

    fun redact(text: String): String {
        var result = text
        result = JWT.replace(result, MASK)
        result = BEARER.replace(result, "Bearer $MASK")
        result = JSON_PAIR.replace(result) { "${it.groupValues[1]}\"$MASK\"" }
        result = PLAIN_PAIR.replace(result) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        result = URL_QUERY.replace(result) { "${it.groupValues[1]}?$MASK" }
        result = EMAIL.replace(result) { "${it.groupValues[1]}***@${it.groupValues[2]}" }
        result = OPAQUE.replace(result, MASK)
        return result
    }

    /** 文本里是否有看起来像机密的东西。报告写出前用它自检。 */
    fun containsSecret(text: String): Boolean = redact(text) != text

    /** URL 的主机名，小写。解析不了时为 null。路径与查询串不看也不留。 */
    fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "").takeIf { it.isNotEmpty() } ?: return null
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        return host.lowercase().takeIf { it.isNotEmpty() }
    }
}
