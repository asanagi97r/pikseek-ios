package dev.pikseek.auth

import dev.pikseek.security.HostClassifier
import kotlinx.atomicfu.atomic

/**
 * 认证请求的地址，拆成各部分。认证模块自己拼、自己查，不依赖哪个平台的 URL 类。
 *
 * @param port 没写端口时为 -1
 * @param userInfo `user:password@` 那一段，没有时为 null
 */
class AuthUrl(
    val scheme: String,
    val host: String,
    val port: Int = -1,
    val path: String,
    val query: String? = null,
    val userInfo: String? = null,
) {
    override fun toString(): String = buildString {
        append(scheme).append("://")
        if (userInfo != null) append(userInfo).append('@')
        append(host)
        if (port != -1) append(':').append(port)
        append(path)
        if (query != null) append('?').append(query)
    }

    companion object {
        /** 拆一条地址。拆不出主机名时主机名为空串，交给 [AuthNetworkPolicy.check] 去拒绝。 */
        fun parse(text: String): AuthUrl {
            val scheme = text.substringBefore("://", "")
            var rest = if (scheme.isEmpty()) text else text.substringAfter("://")
            rest = rest.substringBefore('#')
            val authority = rest.takeWhile { it != '/' && it != '?' }
            val afterAuthority = rest.substring(authority.length)
            val path = afterAuthority.substringBefore('?')
            val query = if ('?' in afterAuthority) afterAuthority.substringAfter('?') else null
            val userInfo = if ('@' in authority) authority.substringBeforeLast('@') else null
            val hostPort = authority.substringAfterLast('@')
            // 方括号里的是 IPv6 字面量，冒号不是端口分隔符
            val portText = if (hostPort.endsWith("]")) "" else hostPort.substringAfterLast(':', "")
            val host = if (portText.isEmpty() && !hostPort.endsWith(":")) hostPort else hostPort.substringBeforeLast(':')
            val port = when {
                portText.isEmpty() -> if (hostPort.endsWith(":")) INVALID_PORT else -1
                else -> portText.toIntOrNull() ?: INVALID_PORT
            }
            return AuthUrl(scheme, host, port, path, query, userInfo)
        }

        // 端口写了却读不成数字：给一个一定通不过检查的值
        private const val INVALID_PORT = -2
    }
}

/**
 * 认证模块能访问哪里：只有 PikPak 官方认证主机 `user.<官方根域名>`，只走 HTTPS，只有下面三个路径。
 *
 * 每个认证请求发出前都过 [check]，对不上的直接抛 [AuthPolicyViolationException]，一个字节也不发。
 * 不跟随重定向（客户端那边设成不跟随）：服务端回 3xx 指向别处时，密码不会被带过去。
 *
 * 依据见 `AUTH_ENDPOINTS.md`。
 */
object AuthNetworkPolicy {
    /** 认证用到的三个端点。[sensitive] 写明请求体里带了什么机密，安全页原样显示。 */
    enum class Endpoint(val path: String, val purpose: String, val sensitive: String) {
        CaptchaInit("/v1/shield/captcha/init", "登录前取验证令牌", "账号名、设备标识"),
        SignIn("/v1/auth/signin", "密码登录", "账号名、密码"),
        Token("/v1/auth/token", "刷新会话", "刷新令牌"),
    }

    /** 密码登录固定用的根域名：别的根域名下的密码登录没有实测过，刷新令牌测过。 */
    const val PRIMARY_ROOT = "mypikpak.com"

    val allowedHosts: List<String> = HostClassifier.OFFICIAL_ROOTS.map { "user.$it" }

    /** [root] 下 [endpoint] 的地址。[root] 不是官方根域名时抛异常。 */
    fun uri(root: String, endpoint: Endpoint, query: String? = null): AuthUrl {
        val url = AuthUrl("https", "user.${root.trim().lowercase()}", -1, endpoint.path, query)
        check(url)
        return url
    }

    /** 不合策略时抛 [AuthPolicyViolationException]。 */
    fun check(url: AuthUrl) {
        val host = url.host.lowercase()
        val allowed = url.scheme.equals("https", ignoreCase = true) &&
            host in allowedHosts &&
            (url.port == -1 || url.port == 443) &&
            url.userInfo == null &&
            Endpoint.entries.any { it.path == url.path }
        if (!allowed) {
            blocked.incrementAndGet()
            throw AuthPolicyViolationException(host.ifEmpty { "（无主机名）" })
        }
    }

    private val blocked = atomic(0)

    /** 本次运行里被拦下、没有发出的认证请求数。正常应当一直是 0。 */
    val blockedCount: Int get() = blocked.value
}
