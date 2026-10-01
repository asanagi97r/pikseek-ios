package dev.pikseek.auth

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * 主程序与认证之间唯一的接口。
 *
 * 边界：
 *
 * ```
 * 主程序（界面、文件、播放）
 *     │  只调这里的方法，拿到的只有 AuthSession（短期访问令牌）
 *     ▼
 * AuthService ── 眼下由进程内的 AuthBroker 实现
 *     │  密码、刷新令牌、凭据存储都在这一侧
 *     ▼
 * PikPak 官方认证服务（user.mypikpak.com）
 * ```
 *
 * 每个方法的参数与返回值都是可序列化的纯数据，没有回调、没有共享对象：
 * 以后把 AuthBroker 拆成独立进程（AuthBroker.exe，经命名管道）时，这个接口原样变成管道上的消息，主程序不用改。
 *
 * 主程序永远拿不到密码与刷新令牌，也不知道它们存在哪、怎么存。
 */
interface AuthService {
    /** 已知的账号与眼下用的那个。登录、退出、切换后更新。 */
    val accounts: StateFlow<KnownAccounts>

    /**
     * 某个账号的会话救不回来了（刷新令牌被服务端拒绝），发出它的账号名。
     * 主程序据此回登录页；会话已从本机删掉。
     */
    val reloginRequired: SharedFlow<String>

    /**
     * 读出本机保存的账号列表。不发网络请求。
     * 凭据存储读不出来时列表为空，等同于首次使用。
     */
    suspend fun restore(): KnownAccounts

    /**
     * 密码登录。[password] 用完即被本方法清零，调用方不要再留副本。
     * 成功后会话按 [CredentialPersistence] 的结果加密保存；存不了也算登录成功，只是下次启动要重新登录。
     *
     * @throws AuthRejectedException 服务端拒绝（密码错、要求验证等）
     * @throws java.io.IOException 网络不通
     */
    suspend fun login(account: String, password: CharArray): AuthSession

    /**
     * 用刷新令牌登录，给不想在这里输密码的人。[refreshToken] 用完即被清零。
     * 当场向服务端换一次，换不出来就是令牌无效。
     */
    suspend fun loginWithRefreshToken(account: String, refreshToken: CharArray): AuthSession

    /**
     * [account] 的可用会话：没过期的直接给，过期的先刷新。本机没有它的会话时为 null。
     *
     * @throws ReloginRequiredException 刷新令牌已失效，会话已删
     * @throws java.io.IOException 要刷新而网络不通；本机的会话原样留着
     */
    suspend fun session(account: String): AuthSession?

    /** 不管过没过期都刷新一次：访问令牌没到期却被服务端拒绝时用。异常同 [session]。 */
    suspend fun refresh(account: String): AuthSession

    /** 把 [account] 设为眼下用的，null 表示停在登录页。只改列表，不发请求。 */
    suspend fun select(account: String?)

    /** 更新 [account] 在列表里的资料（昵称、头像、用量），不含机密。不在列表里时不做事。 */
    suspend fun updateProfile(account: String, profile: AccountProfile)

    /** 退出 [account]：删掉本机的会话，从列表里去掉。不发请求。 */
    suspend fun logout(account: String)

    /** 安全页显示的状态。不含令牌与密码。 */
    suspend fun status(): AuthStatus
}

/**
 * 交给主程序的会话：只够发 API 请求。刷新令牌不在里面。
 * [expiresAtEpochSeconds] 已比服务端的到期时刻提前几分钟。
 */
class AuthSession(
    val account: String,
    val userId: String,
    val accessToken: String,
    val expiresAtEpochSeconds: Long,
) {
    /** 不打印令牌：会话进了日志或崩溃栈，等于把账号交出去。 */
    override fun toString(): String = "AuthSession(userId=$userId, expiresAt=$expiresAtEpochSeconds)"
}

/** 一个登录过、没退出的账号。只有资料，没有机密。 */
@Serializable
data class KnownAccount(
    val account: String,
    val name: String = "",
    val avatarUrl: String = "",
    /** 服务端返回时已打码（a***@example.com）。 */
    val email: String = "",
    val usageBytes: Long = -1,
    val limitBytes: Long = -1,
    /** 最近一次切到它的时刻，毫秒。 */
    val usedAt: Long = 0,
) {
    val displayName: String get() = name.ifBlank { account }
}

/** 账号列表。[current] 为 null 是停在登录页。顺序是首次登录的先后。 */
@Serializable
data class KnownAccounts(
    val current: String? = null,
    val accounts: List<KnownAccount> = emptyList(),
) {
    fun find(account: String): KnownAccount? = accounts.firstOrNull { it.account == account }

    fun upsert(entry: KnownAccount): KnownAccounts {
        val at = accounts.indexOfFirst { it.account == entry.account }
        return copy(accounts = if (at < 0) accounts + entry else accounts.toMutableList().apply { set(at, entry) })
    }

    fun without(account: String): KnownAccounts =
        copy(current = current.takeUnless { it == account }, accounts = accounts.filterNot { it.account == account })
}

/** [AuthService.updateProfile] 的参数：null 的项不改。 */
@Serializable
data class AccountProfile(
    val name: String? = null,
    val avatarUrl: String? = null,
    val email: String? = null,
    val usageBytes: Long? = null,
    val limitBytes: Long? = null,
)

/** 会话在本机是怎么放的。 */
enum class CredentialPersistence {
    /** 经 DPAPI（当前 Windows 用户）加密后存在数据目录里。 */
    Dpapi,

    /** 加密存储用不了，会话只在内存里，退出程序即失效。不退回明文。 */
    MemoryOnly,
}

class AuthStatus(
    val persistence: CredentialPersistence,
    /** 加密存储用不了的原因，可用时为 null。 */
    val storeProblem: String?,
    val knownAccounts: Int,
    /** 本机存着会话的账号数。 */
    val storedSessions: Int,
    /** 认证模块允许访问的主机，见 [AuthNetworkPolicy]。 */
    val allowedHosts: List<String>,
    /** 最近一次认证请求：用途、主机与时刻。没发过为 null。 */
    val lastRequest: AuthRequestRecord?,
    /** 被策略拦下、没有发出的请求数。 */
    val blockedRequests: Int,
)

class AuthRequestRecord(val purpose: String, val host: String, val atMillis: Long, val outcome: String)

/** 服务端明确拒绝。[code] 与 [error] 是它返回的错误码与名称。 */
open class AuthRejectedException(
    val code: Int,
    val error: String,
    val description: String?,
    val httpStatus: Int?,
    message: String,
) : RuntimeException(message)

/** 刷新令牌失效或本机没有会话，只能重新登录。 */
class ReloginRequiredException(val account: String) : IllegalStateException("登录已失效，请重新登录")

/** 认证模块要访问的地址不在白名单里，请求没有发出。 */
class AuthPolicyViolationException(val host: String) : IllegalStateException("认证请求的目标不在白名单内，已拦下：$host")
