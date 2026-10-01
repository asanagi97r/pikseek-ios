package dev.pikseek.auth

import dev.pikseek.security.HostClassifier
import java.net.URI

/**
 * 认证模块能访问哪里：只有 PikPak 官方认证主机 `user.<官方根域名>`，只走 HTTPS，只有下面三个路径。
 *
 * 每个认证请求发出前都过 [check]，对不上的直接抛 [AuthPolicyViolationException]，一个字节也不发。
 * 不跟随重定向（客户端那边设成 NEVER）：服务端回 3xx 指向别处时，密码不会被带过去。
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
    fun uri(root: String, endpoint: Endpoint, query: String? = null): URI {
        val uri = URI("https", null, "user.${root.trim().lowercase()}", -1, endpoint.path, query, null)
        check(uri)
        return uri
    }

    /** 不合策略时抛 [AuthPolicyViolationException]。 */
    fun check(uri: URI) {
        val host = uri.host?.lowercase().orEmpty()
        val allowed = uri.scheme.equals("https", ignoreCase = true) &&
            host in allowedHosts &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawUserInfo == null &&
            Endpoint.entries.any { it.path == uri.path }
        if (!allowed) {
            blocked.incrementAndGet()
            throw AuthPolicyViolationException(host.ifEmpty { "（无主机名）" })
        }
    }

    private val blocked = java.util.concurrent.atomic.AtomicInteger()

    /** 本次运行里被拦下、没有发出的认证请求数。正常应当一直是 0。 */
    val blockedCount: Int get() = blocked.get()
}
