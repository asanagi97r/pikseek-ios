package dev.piko.shared.data

import dev.pikseek.auth.AuthService
import dev.pikseek.auth.AuthSession
import dev.pikseek.auth.ReloginRequiredException
import io.github.nihildigit.pikpak.Session
import io.github.nihildigit.pikpak.SessionStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException

/**
 * 把 PikPak SDK 接到 [AuthService] 上：SDK 以为自己在读一个会话存储，实际每次都是向认证模块要一个可用的访问令牌。
 * 每个 SDK client 配一份。
 *
 * SDK 自己会刷新令牌、会用密码重新登录，这两样都不让它做：
 * - 交给它的会话不带刷新令牌，它无从刷新；
 * - 这里从不返回 null、也从不把它已经被拒的令牌再给一次，它走不到密码登录那一步
 *   （那一步会先向认证服务要验证令牌；密码它也拿不到，见 [PikoClientManager]）。
 *
 * 于是登录与刷新会话的请求只从认证模块发出，刷新令牌不进 SDK 的内存。
 *
 * SDK 什么时候来读：
 * 1. 新建的 client 头一回要会话；
 * 2. 它内存里的会话过期了。认证模块判过期比 SDK 早半分钟，这时给出的必是刷新过的新令牌；
 * 3. 令牌没到期却被服务端以 401 拒绝。这时认证模块手里还是同一个令牌，所以「同一个 client 把同一个令牌又要了一次」
 *    只可能是这种情况，强制刷新。
 */
internal class BrokerSessionStore(private val auth: AuthService) : SessionStore {
    private val lock = Mutex()
    private var lastGiven: String? = null

    override suspend fun load(account: String): Session = lock.withLock {
        var session = auth.session(account) ?: throw ReloginRequiredException(account)
        if (session.accessToken == lastGiven) {
            session = auth.refresh(account)
            // 刷新后还是那个被拒的令牌：再交给 SDK 它就会转去密码登录。当作暂时性故障，让上层稍后重试
            if (session.accessToken == lastGiven) throw IOException("认证服务没有换发新的访问令牌")
        }
        lastGiven = session.accessToken
        session.toSdk()
    }

    // SDK 只在自己登录或刷新之后才写，而这两样它都做不了；真走到这里说明上面的前提被破坏了，不能悄悄把令牌存到别处
    override suspend fun save(account: String, session: Session) = Unit

    // 退出登录由 PikoClientManager 经 AuthService 做，不经 SDK
    override suspend fun clear(account: String) = Unit

    private fun AuthSession.toSdk() = Session(
        accessToken = accessToken,
        refreshToken = "",
        sub = userId,
        expiresAt = expiresAtEpochSeconds,
    )
}
