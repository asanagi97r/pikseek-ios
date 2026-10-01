package dev.pikseek.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 认证模块内部的完整会话，含刷新令牌。只在本模块里流转，落盘前整份经 DPAPI 加密；
 * 交给主程序的是去掉刷新令牌的 [AuthSession]。
 */
@Serializable
internal data class StoredSession(
    val account: String,
    @SerialName("user_id") val userId: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    /** UNIX 秒。已比服务端的到期时刻提前 [EXPIRY_SKEW_SECONDS]。 */
    @SerialName("expires_at") val expiresAt: Long,
) {
    fun isUsableAt(epochSeconds: Long): Boolean = accessToken.isNotEmpty() && expiresAt > epochSeconds + REFRESH_MARGIN_SECONDS

    fun toPublic(): AuthSession = AuthSession(account, userId, accessToken, expiresAt)

    /** 不打印令牌。 */
    override fun toString(): String = "StoredSession(userId=$userId, expiresAt=$expiresAt)"

    companion object {
        /** 把到期时刻提前这么多记下，不让请求带着快过期的令牌出去。与 PikPak 各客户端的做法一致。 */
        const val EXPIRY_SKEW_SECONDS = 5L * 60L

        /** 离（已提前的）到期不足这么久就先刷新：交到主程序手里的令牌至少还能用这么久。 */
        const val REFRESH_MARGIN_SECONDS = 30L
    }
}

/** 一次令牌响应里用得上的字段。 */
internal class TokenGrant(val accessToken: String, val refreshToken: String, val userId: String, val expiresInSeconds: Long)
