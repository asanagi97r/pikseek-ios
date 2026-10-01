package dev.pikseek.auth

import dev.pikseek.platform.currentTimeMillis
import dev.pikseek.security.Digests
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlinx.serialization.json.Json

/**
 * 本机的认证中枢（LocalAuthBroker），[AuthService] 眼下唯一的实现。
 *
 * 管四件事：登录、刷新、会话的保存与删除、账号列表。密码不保存，登录那一下用完即清；
 * 刷新令牌只在这个类与 [SecureCredentialStore] 之间流转，不出本模块。
 *
 * 存储的规则只有一条：**加密不了就不存。** 存失败时会话照常在内存里用到程序退出，
 * [status] 报 [CredentialPersistence.MemoryOnly]，界面提示下次要重新登录；磁盘上不留明文，也不留已作废的旧密文。
 *
 * 所有方法经一把锁串行：刷新令牌每换一次旧的就作废，两个刷新并发会有一个拿着废令牌去换，把会话换丢。
 * 等锁的调用方拿到锁后看到的是已经换好的会话，不再发请求。
 *
 * @param nowMillis 当前时刻，测试里换成假时钟
 */
class AuthBroker(
    private val store: SecureCredentialStore,
    transport: PikPakAuthClient.Transport = defaultAuthTransport(),
    refreshRoot: () -> String = { AuthNetworkPolicy.PRIMARY_ROOT },
    private val nowMillis: () -> Long = ::currentTimeMillis,
) : AuthService {
    private val client = PikPakAuthClient(refreshRoot, transport) { lastRequest = it }

    private val lock = Mutex()

    // 读过的会话留在内存里：存储一时读不出也不影响已登录的账号
    private val sessions = HashMap<String, StoredSession>()
    private val loaded = HashSet<String>()

    // 这几个账号的会话没能存下，只在内存里
    private val memoryOnly = HashSet<String>()
    private var restored = false

    @Volatile
    private var lastRequest: AuthRequestRecord? = null

    @Volatile
    private var lastStoreFailure: String? = null

    private val _accounts = MutableStateFlow(KnownAccounts())
    override val accounts: StateFlow<KnownAccounts> = _accounts.asStateFlow()

    private val _reloginRequired = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val reloginRequired: SharedFlow<String> = _reloginRequired.asSharedFlow()

    override suspend fun restore(): KnownAccounts = lock.withLock {
        if (!restored) {
            restored = true
            _accounts.value = readIndex()
        }
        _accounts.value
    }

    override suspend fun login(account: String, password: CharArray): AuthSession {
        val name = account.trim()
        try {
            require(name.isNotEmpty()) { "账号不能为空" }
            return lock.withLock {
                val grant = client.signIn(name, password)
                adopt(name, grant, previous = null).toPublic()
            }
        } finally {
            // signIn 自己也清；这里兜住在它之前就抛出的情况
            password.fill('\u0000')
        }
    }

    override suspend fun loginWithRefreshToken(account: String, refreshToken: CharArray): AuthSession {
        val name = account.trim()
        try {
            require(name.isNotEmpty()) { "账号不能为空" }
            val token = refreshToken.concatToString().trim()
            require(token.isNotEmpty()) { "刷新令牌不能为空" }
            return lock.withLock {
                val grant = client.refresh(name, token)
                adopt(name, grant, previous = token).toPublic()
            }
        } finally {
            refreshToken.fill('\u0000')
        }
    }

    override suspend fun session(account: String): AuthSession? = lock.withLock {
        val current = sessionOf(account) ?: return@withLock null
        if (current.isUsableAt(nowSeconds())) current.toPublic() else refreshLocked(current).toPublic()
    }

    override suspend fun refresh(account: String): AuthSession = lock.withLock {
        val current = sessionOf(account) ?: throw ReloginRequiredException(account)
        refreshLocked(current).toPublic()
    }

    override suspend fun select(account: String?) = lock.withLock {
        editIndex { index ->
            if (account == null) {
                index.copy(current = null)
            } else {
                val entry = index.find(account) ?: KnownAccount(account)
                index.upsert(entry.copy(usedAt = nowMillis())).copy(current = account)
            }
        }
    }

    override suspend fun updateProfile(account: String, profile: AccountProfile) = lock.withLock {
        editIndex { index ->
            val entry = index.find(account) ?: return@editIndex index
            index.upsert(
                entry.copy(
                    name = profile.name ?: entry.name,
                    avatarUrl = profile.avatarUrl ?: entry.avatarUrl,
                    email = profile.email ?: entry.email,
                    usageBytes = profile.usageBytes ?: entry.usageBytes,
                    limitBytes = profile.limitBytes ?: entry.limitBytes,
                ),
            )
        }
    }

    override suspend fun logout(account: String) = lock.withLock {
        forgetSession(account)
        editIndex { it.without(account) }
    }

    override suspend fun status(): AuthStatus = lock.withLock {
        val problem = store.problem() ?: lastStoreFailure
        AuthStatus(
            persistence = if (problem == null && memoryOnly.isEmpty()) CredentialPersistence.Dpapi else CredentialPersistence.MemoryOnly,
            storeProblem = problem,
            knownAccounts = _accounts.value.accounts.size,
            storedSessions = runCatching { store.names().count { it.startsWith(SESSION_PREFIX) } }.getOrDefault(0),
            allowedHosts = AuthNetworkPolicy.allowedHosts,
            lastRequest = lastRequest,
            blockedRequests = AuthNetworkPolicy.blockedCount,
        )
    }

    /** 最近一次登录或刷新的会话有没有存下。登录页据此提示「下次启动要重新登录」。 */
    suspend fun isPersisted(account: String): Boolean = lock.withLock { account !in memoryOnly && store.problem() == null }

    /** 加密存储用不了的原因，能用时为 null。登录之前就能问，不发请求。 */
    fun storeProblem(): String? = store.problem()

    // ---- 以下都在锁内调用 ----

    private suspend fun refreshLocked(current: StoredSession): StoredSession {
        if (current.refreshToken.isEmpty()) {
            dropDeadSession(current.account)
            throw ReloginRequiredException(current.account)
        }
        val grant = try {
            client.refresh(current.account, current.refreshToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthRejectedException) {
            // 服务端明确不认这个刷新令牌了。留着它每次启动都白试一次，删掉，回登录页
            dropDeadSession(current.account)
            throw ReloginRequiredException(current.account)
        }
        // 断网与 5xx 是 IOException，不在上面接：会话原样留着，网络好了再刷新
        return adopt(current.account, grant, previous = current.refreshToken)
    }

    private fun dropDeadSession(account: String) {
        forgetSession(account)
        editIndex { if (it.current == account) it.copy(current = null) else it }
        _reloginRequired.tryEmit(account)
    }

    /** 新拿到的令牌成为 [account] 的会话，存下并记进账号列表。 */
    private fun adopt(account: String, grant: TokenGrant, previous: String?): StoredSession {
        val session = StoredSession(
            account = account,
            userId = grant.userId,
            accessToken = grant.accessToken,
            // 服务端偶尔不回新的刷新令牌，这时旧的继续有效
            refreshToken = grant.refreshToken.ifEmpty { previous.orEmpty() },
            expiresAt = nowSeconds() + grant.expiresInSeconds - StoredSession.EXPIRY_SKEW_SECONDS,
        )
        sessions[account] = session
        loaded += account
        persist(session)
        editIndex { index ->
            val entry = index.find(account) ?: KnownAccount(account)
            index.upsert(entry.copy(usedAt = nowMillis()))
        }
        return session
    }

    private fun persist(session: StoredSession) {
        val bytes = json.encodeToString(StoredSession.serializer(), session).encodeToByteArray()
        try {
            store.write(sessionKey(session.account), bytes)
            memoryOnly -= session.account
            lastStoreFailure = null
        } catch (e: Exception) {
            // 存不下：会话只在内存里。磁盘上若还有上一份，它的刷新令牌此刻已被这次刷新作废，一并删掉
            memoryOnly += session.account
            lastStoreFailure = e.message ?: "保存登录信息失败"
            store.delete(sessionKey(session.account))
        } finally {
            bytes.fill(0)
        }
    }

    private fun sessionOf(account: String): StoredSession? {
        sessions[account]?.let { return it }
        if (account in loaded) return null
        val bytes = try {
            store.read(sessionKey(account))
        } catch (e: IOException) {
            // 解不开：换了 Windows 用户或换了电脑。当作没有会话，密文留着不动，同名账号重新登录时覆盖
            lastStoreFailure = e.message
            null
        }
        loaded += account
        if (bytes == null) return null
        return try {
            json.decodeFromString(StoredSession.serializer(), bytes.decodeToString()).also { sessions[account] = it }
        } catch (e: Exception) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    private fun forgetSession(account: String) {
        sessions.remove(account)
        memoryOnly -= account
        loaded += account
        store.delete(sessionKey(account))
    }

    private fun readIndex(): KnownAccounts {
        val bytes = try {
            store.read(INDEX_KEY)
        } catch (e: IOException) {
            lastStoreFailure = e.message
            null
        } ?: return KnownAccounts()
        return runCatching { json.decodeFromString(KnownAccounts.serializer(), bytes.decodeToString()) }
            .getOrDefault(KnownAccounts())
    }

    private fun editIndex(change: (KnownAccounts) -> KnownAccounts) {
        val next = change(_accounts.value)
        if (next == _accounts.value) return
        _accounts.value = next
        // 账号名是邮箱或手机号，不是机密，但同样只以密文落盘；存不下就只在内存里
        runCatching { store.write(INDEX_KEY, json.encodeToString(KnownAccounts.serializer(), next).encodeToByteArray()) }
            .onFailure { lastStoreFailure = it.message ?: "保存账号列表失败" }
    }

    private fun nowSeconds(): Long = nowMillis() / 1000L

    private companion object {
        const val INDEX_KEY = "accounts"
        const val SESSION_PREFIX = "session_"
        val json = Json { ignoreUnknownKeys = true }

        /** 账号名不进文件名，取摘要。 */
        fun sessionKey(account: String): String = SESSION_PREFIX +
            Digests.hex(Digests.sha256(account.encodeToByteArray())).take(24)
    }
}
