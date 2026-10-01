package dev.pikseek.auth

import dev.pikseek.security.NetworkAudit
import dev.pikseek.platform.currentTimeMillis
import dev.pikseek.security.Digests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 与 PikPak 认证服务对话的全部代码：取验证令牌、密码登录、刷新会话。自己实现，不经 PikPak SDK，也不用 Piko 的代码。
 *
 * 协议本身（端点、字段、客户端标识）是 PikPak 官方 Android 客户端的公开行为，出处见 `AUTH_ENDPOINTS.md`。
 *
 * 安全上的几条：
 * - 每个请求发出前过 [AuthNetworkPolicy.check]；不跟随重定向。
 * - 不打印请求体、响应体与任何头；对外只报用途、主机与结果（[onRequest]、[NetworkAudit]）。
 * - 密码以 CharArray 进来，拼进请求体后立刻清零；请求体的字节发完也清零。String 清不掉，
 *   所以密码只在拼 JSON 的那一刻以 String 存在，不被任何字段引用。
 * - 传输层用系统自带的 HTTP 客户端（桌面是 JDK 的，iOS 是 URLSession）：认证这条路上没有第三方网络库。
 *   TLS 是与 PikPak 端到端的。
 */
class PikPakAuthClient(
    /** 刷新会话走哪个官方根域名。密码登录固定走 [AuthNetworkPolicy.PRIMARY_ROOT]。 */
    private val refreshRoot: () -> String = { AuthNetworkPolicy.PRIMARY_ROOT },
    private val transport: Transport = defaultAuthTransport(),
    private val onRequest: (AuthRequestRecord) -> Unit = {},
) {
    /**
     * 发一个 JSON POST，返回状态码与响应正文。实现必须先过 [AuthNetworkPolicy.check]、不跟随重定向；
     * 连接没建起来时抛 [AuthConnectException]（这种失败可以放心重发）。
     * 抽出来一是各平台的 HTTP 客户端不同，二是测试时换成本机的假服务端。
     */
    fun interface Transport {
        @Throws(IOException::class)
        fun post(uri: AuthUrl, headers: Map<String, String>, body: ByteArray): Reply
    }

    class Reply(val status: Int, val body: String)

    internal suspend fun signIn(account: String, password: CharArray): TokenGrant {
        try {
            val deviceId = deviceIdOf(account)
            val captcha = initCaptcha(account, deviceId)
            // 密码只在这里变成 String，随请求体一起用完即弃
            val body = buildJsonObject {
                put("client_id", CLIENT_ID)
                put("client_secret", CLIENT_SECRET)
                put("grant_type", "password")
                put("username", account)
                put("password", password.concatToString())
                put("captcha_token", captcha)
            }
            val reply = send(AuthNetworkPolicy.PRIMARY_ROOT, AuthNetworkPolicy.Endpoint.SignIn, deviceId, body)
            return reply.toGrant("signin")
        } finally {
            password.fill('\u0000')
        }
    }

    /** 用 [refreshToken] 换新会话。服务端说令牌无效时抛 [AuthRejectedException]。 */
    internal suspend fun refresh(account: String, refreshToken: String): TokenGrant {
        val body = buildJsonObject {
            put("client_id", CLIENT_ID)
            put("client_secret", CLIENT_SECRET)
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
        }
        val root = refreshRoot().takeIf { it in dev.pikseek.security.HostClassifier.OFFICIAL_ROOTS } ?: AuthNetworkPolicy.PRIMARY_ROOT
        val reply = send(root, AuthNetworkPolicy.Endpoint.Token, deviceIdOf(account), body)
        return reply.toGrant("refresh_token")
    }

    private suspend fun initCaptcha(account: String, deviceId: String): String {
        val body = buildJsonObject {
            put("client_id", CLIENT_ID)
            put("device_id", deviceId)
            // 这是服务端要签的字符串，不是地址：别的根域名下也照写官方主机
            put("action", "POST:https://user.mypikpak.com/v1/auth/signin")
            putJsonObject("meta") { put("username", account) }
        }
        val reply = send(AuthNetworkPolicy.PRIMARY_ROOT, AuthNetworkPolicy.Endpoint.CaptchaInit, deviceId, body)
        val token = reply.ok("captcha_init")["captcha_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (token.isEmpty()) {
            throw AuthRejectedException(
                code = CAPTCHA_REQUIRED,
                error = "captcha_required",
                description = null,
                httpStatus = null,
                message = "PikPak 要求人机验证。本客户端不绕过验证，请稍后再试，或改用刷新令牌登录。",
            )
        }
        return token
    }

    /**
     * 发一个认证请求。429 与 503 是服务端拒收，重发不会重复生效，退避后再试；连接都没建起来的也再试。
     * 其余失败（读超时、连接中断）不重发：请求可能已经到了。
     */
    private suspend fun send(root: String, endpoint: AuthNetworkPolicy.Endpoint, deviceId: String, body: JsonObject): JsonObject {
        val uri = AuthNetworkPolicy.uri(root, endpoint)
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "X-Device-Id" to deviceId,
            "Content-Type" to "application/json",
        )
        val bytes = json.encodeToString(JsonObject.serializer(), body).encodeToByteArray()
        try {
            var attempt = 0
            while (true) {
                // 审计只记主机与用途
                NetworkAudit.record(uri.host, "Auth：${endpoint.purpose}")
                val reply = try {
                    withContext(Dispatchers.IO) { transport.post(uri, headers, bytes) }
                } catch (e: IOException) {
                    val beforeSending = isConnectFailure(e)
                    if (beforeSending && attempt < RETRY_DELAYS_MILLIS.size) {
                        delay(RETRY_DELAYS_MILLIS[attempt++])
                        continue
                    }
                    report(endpoint, uri, "网络错误：${e::class.simpleName}")
                    throw e
                }
                if (reply.status == 429 || reply.status == 503) {
                    if (attempt < RETRY_DELAYS_MILLIS.size) {
                        delay(RETRY_DELAYS_MILLIS[attempt++])
                        continue
                    }
                    report(endpoint, uri, "HTTP ${reply.status}")
                    throw AuthUnavailableException("PikPak 认证服务暂时不可用（HTTP ${reply.status}）")
                }
                if (reply.status >= 500) {
                    report(endpoint, uri, "HTTP ${reply.status}")
                    throw AuthUnavailableException("PikPak 认证服务暂时不可用（HTTP ${reply.status}）")
                }
                val parsed = runCatching { json.parseToJsonElement(reply.body) as? JsonObject }.getOrNull()
                val rejection = parsed?.rejection(reply.status)
                    ?: if (reply.status !in 200..299) {
                        AuthRejectedException(-1, "http_${reply.status}", null, reply.status, "登录失败（HTTP ${reply.status}）")
                    } else if (parsed == null) {
                        AuthRejectedException(-1, "bad_response", null, reply.status, "PikPak 认证服务返回了无法识别的内容")
                    } else {
                        null
                    }
                if (rejection != null) {
                    report(endpoint, uri, "被拒绝：${rejection.error}")
                    throw rejection
                }
                report(endpoint, uri, "成功")
                return parsed!!
            }
        } finally {
            // 请求体里有密码或刷新令牌
            bytes.fill(0)
        }
    }

    private fun report(endpoint: AuthNetworkPolicy.Endpoint, uri: AuthUrl, outcome: String) {
        onRequest(AuthRequestRecord(endpoint.purpose, uri.host, currentTimeMillis(), outcome))
    }

    private fun JsonObject.ok(operation: String): JsonObject = this.also {
        if (it.isEmpty()) throw AuthRejectedException(-1, "bad_response", null, null, "$operation：响应为空")
    }

    private fun JsonObject.toGrant(operation: String): TokenGrant {
        val accessToken = this["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (accessToken.isEmpty()) {
            throw AuthRejectedException(-1, "bad_response", null, null, "$operation：响应里没有访问令牌")
        }
        return TokenGrant(
            accessToken = accessToken,
            refreshToken = this["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            userId = this["sub"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            expiresInSeconds = this["expires_in"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    /** PikPak 的错误信封：`error_code` 非 0。2xx 与 4xx 上都可能带。 */
    private fun JsonObject.rejection(httpStatus: Int): AuthRejectedException? {
        val code = (this["error_code"] as? JsonPrimitive)?.intOrNull ?: return null
        if (code == 0) return null
        val error = (this["error"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val description = (this["error_description"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return AuthRejectedException(code, error, description, httpStatus, describe(code, error, description))
    }

    private fun describe(code: Int, error: String, description: String?): String = when {
        error.startsWith("captcha") ->
            "PikPak 要求人机验证（$error）。本客户端不绕过验证，请稍后再试，或改用刷新令牌登录。"
        code == REFRESH_TOKEN_INVALID -> "登录已失效，请重新登录"
        else -> "PikPak 拒绝了请求：${description ?: error.ifEmpty { "未知原因" }}（错误码 $code）"
    }

    companion object {
        // PikPak 官方 Android 客户端的公开标识，认证服务据此认客户端。出处见 AUTH_ENDPOINTS.md
        internal const val USER_AGENT = "ANDROID-com.pikcloud.pikpak/1.21.0"
        internal const val CLIENT_ID = "YNxT9w7GMdWvEOKa"
        internal const val CLIENT_SECRET = "dbw2OtmVEeuUvIptb1Coyg"

        const val CAPTCHA_REQUIRED = 9
        const val REFRESH_TOKEN_INVALID = 4126

        private val RETRY_DELAYS_MILLIS = longArrayOf(500L, 1_500L)
        private val json = Json { ignoreUnknownKeys = true }

        /** 设备标识：账号名的 MD5，十六进制。同一账号每次相同，服务端据此把验证令牌与设备对上。 */
        fun deviceIdOf(account: String): String =
            Digests.hex(Digests.md5(account.encodeToByteArray()))
    }
}

/** 认证服务暂时不可用（429、5xx）。与断网同样处理：会话留着，稍后再试。 */
class AuthUnavailableException(message: String) : IOException(message)

/** 连接没建起来（拒绝连接、连接超时、找不到主机）：请求一个字节也没发出去，重发不会重复生效。 */
class AuthConnectException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** 这个平台上认证请求走的传输层。 */
expect fun defaultAuthTransport(): PikPakAuthClient.Transport

/** [error] 是不是「连接没建起来」。各平台的 HTTP 客户端报这种失败的异常不同。 */
internal expect fun isConnectFailure(error: Throwable): Boolean
