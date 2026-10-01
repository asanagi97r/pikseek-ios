package dev.piko.shared.data

import dev.piko.shared.log.PikoLog
import dev.pikseek.auth.AccountProfile
import dev.pikseek.auth.AuthRejectedException
import dev.pikseek.auth.AuthService
import dev.pikseek.auth.KnownAccount
import dev.pikseek.auth.KnownAccounts
import dev.pikseek.auth.ReloginRequiredException
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/**
 * 眼下用哪个账号、它的 SDK client 是哪一个。同一时刻只有一个账号在用（[currentClient]），
 * 各仓库只认它，按账号的东西在账号变了时各自换一份。
 *
 * 这里**不碰密码、不碰刷新令牌、不管凭据怎么存**：登录、刷新、保存与退出全部经 [AuthService] 交给认证模块，
 * 这个类只负责在认证结果出来之后建 client、换 client。交给 SDK 的密码来源永远抛异常，SDK 自己登录不了；
 * 它的会话存储是 [BrokerSessionStore]，读到的只有访问令牌。
 *
 * @param httpClient 交给 SDK 的 API 客户端。缺省由 SDK 自建；冒烟测试在这里换成 Ktor 的 MockEngine。
 */
class PikoClientManager(
    /** 认证模块。安全页读它的状态；别处不要直接用，登录与退出走本类的方法。 */
    val auth: AuthService,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient? = null,
) : PikoClientProvider {
    private val _currentClient = MutableStateFlow<PikPakClient?>(null)
    override val currentClient: StateFlow<PikPakClient?> = _currentClient.asStateFlow()

    private val _isInitializing = MutableStateFlow(true)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()

    /** 本机登录过、没退出的账号。只有资料，没有机密。 */
    val accounts: StateFlow<KnownAccounts> get() = auth.accounts

    /** 登录着一个账号、又打开登录页去加另一个。登录成功或取消时结束；这期间当前账号照常在后台工作。 */
    private val _addingAccount = MutableStateFlow(false)
    val addingAccount: StateFlow<Boolean> = _addingAccount.asStateFlow()

    /** 登录页打开时读一次：要预先填上的账号名，见 [beginAddingAccount] 与会话失效时。 */
    @Volatile
    var addingPrefill: String = ""
        private set

    // 被送回登录页的原因。登录页打开时取走显示一次；用状态而不是事件流，因为它发生在登录页出现之前
    @Volatile
    private var signInNotice: String? = null

    /** 登录页打开时取一次：为什么回到了这里（会话失效）。没有原因时为 null。取过即清。 */
    fun takeSignInNotice(): String? = signInNotice.also { signInNotice = null }

    // 切换、登录与退出都在换 client，交错时后完成的会把先完成的换掉
    private val switchLock = Mutex()
    private var reconnectJob: Job? = null
    private var logoutJob: Job? = null

    init {
        scope.launch(Dispatchers.Default) { restore() }
        // 用着用着刷新令牌失效了：认证模块已把会话删掉，这里把人送回登录页，账号名预先填好
        scope.launch(Dispatchers.Default) {
            auth.reloginRequired.collect { account ->
                switchLock.withLock {
                    val current = _currentClient.value
                    if (current?.account != account) return@withLock
                    PikoLog.w(TAG, "会话已失效，回登录页")
                    reconnectJob?.cancel()
                    addingPrefill = account
                    signInNotice = SESSION_EXPIRED
                    _currentClient.value = null
                    current.close()
                }
            }
        }
    }

    /** 用的是 SDK 自建的网络客户端。注入的是测试的 MockEngine 时为 false，这时测根域名的速度没有意义。 */
    val reachesRealNetwork: Boolean get() = httpClient == null

    /**
     * 启动时恢复上次的账号。
     *
     * 本机的会话没过期时一个请求都不发；过期了由认证模块刷新。刷新时断网不算失败：保留 client 先进主界面
     * （已下载的文件照样能看），后台等网络恢复再试。只有认证模块明确说要重新登录才回登录页。
     */
    suspend fun restore() {
        try {
            val known = auth.restore()
            val account = known.current?.takeIf { it.isNotBlank() } ?: run {
                PikoLog.i(TAG, "启动：没有上次的账号，进登录页，已保存 ${known.accounts.size} 个账号")
                return
            }
            PikoLog.i(TAG, "启动：恢复上次的账号，已保存 ${known.accounts.size} 个账号")
            val client = clientFor(account)
            when (tryConnect(client, "恢复会话")) {
                Outcome.Ready -> _currentClient.value = client
                Outcome.Offline -> {
                    PikoLog.i(TAG, "先以离线状态进主界面，后台等网络恢复")
                    _currentClient.value = client
                    scheduleReconnect(client)
                }
                Outcome.NeedsLogin -> {
                    client.close()
                    addingPrefill = account
                    signInNotice = SESSION_EXPIRED
                    auth.select(null)
                }
            }
        } finally {
            _isInitializing.value = false
        }
    }

    /**
     * 密码登录。[password] 交给认证模块后即被清零，这里不留副本、不写日志。
     */
    suspend fun login(account: String, password: CharArray): Result<PikPakClient> = signIn(account, "密码登录") {
        auth.login(account, password)
    }

    /** 用刷新令牌登录。[refreshToken] 同样用完即清。 */
    suspend fun loginWithToken(account: String, refreshToken: CharArray): Result<PikPakClient> = signIn(account, "令牌登录") {
        auth.loginWithRefreshToken(account, refreshToken)
    }

    private suspend fun signIn(account: String, how: String, authenticate: suspend () -> Unit): Result<PikPakClient> =
        runSuspendCatching {
            val name = account.trim()
            val started = TimeSource.Monotonic.markNow()
            PikoLog.i(TAG, how)
            authenticate()
            PikoLog.i(TAG, "$how 成功，用时 ${started.elapsedNow().inWholeMilliseconds} ms")
            val client = clientFor(name)
            try {
                // 会话刚由认证模块存好，这一步只是让 SDK 读到它，不发请求
                client.login()
                switchLock.withLock { adopt(client) }
                client
            } catch (e: Throwable) {
                client.close()
                throw e
            }
        }.onFailure { PikoLog.w(TAG, "$how 失败，${describe(it)}") }

    /**
     * 切到已保存的 [account]。会话还有效时不发请求，当场换好；断网时照样切过去，后台等网络恢复。
     * 会话已失效时失败，仍停在原来的账号，由界面引导重新登录。
     */
    suspend fun switchTo(account: String): Result<Unit> = runSuspendCatching {
        switchLock.withLock {
            if (_currentClient.value?.account == account) return@withLock
            PikoLog.i(TAG, "切换账号")
            val client = clientFor(account)
            when (tryConnect(client, "切换账号")) {
                Outcome.NeedsLogin -> {
                    client.close()
                    throw ReloginRequiredException(account)
                }
                Outcome.Ready -> adopt(client)
                Outcome.Offline -> {
                    adopt(client)
                    scheduleReconnect(client)
                }
            }
        }
    }

    /** 打开登录页去加一个账号，当前账号不退出。[account] 非空时登录页预先填上它。 */
    fun beginAddingAccount(account: String = "") {
        addingPrefill = account
        _addingAccount.value = true
    }

    fun cancelAddingAccount() {
        _addingAccount.value = false
    }

    /** 登录信息能不能加密保存在本机。为 false 时登录页写明「仅本次运行有效」。 */
    suspend fun credentialsEncrypted(): Boolean = runSuspendCatching { auth.status().storeProblem == null }.getOrDefault(false)

    /**
     * 退出当前账号：删掉它在本机的会话，从列表里去掉，再切到最近用过的另一个已保存账号；一个也没有时回登录页。
     *
     * 在进程级的 [scope] 里做，调用方被取消也照样做完：退出按钮在对话框里，对话框一关它的协程就取消了，
     * 在那里清的话界面已显示退出、磁盘上的会话却还在。正在退出时再调，返回同一个 Job。
     */
    fun logout(): Job = logoutJob?.takeIf { it.isActive } ?: startLogout().also { logoutJob = it }

    private fun startLogout(): Job = scope.launch {
        switchLock.withLock {
            val leaving = _currentClient.value ?: return@withLock
            PikoLog.i(TAG, "退出登录")
            reconnectJob?.cancel()
            // 先摘下、关掉 client 再清：界面不再拿它发请求
            _currentClient.value = null
            leaving.close()
            runSuspendCatching { auth.logout(leaving.account) }.onFailure { PikoLog.w(TAG, "清除会话失败，${describe(it)}") }
            val next = accounts.value.accounts.sortedByDescending { it.usedAt }.firstNotNullOfOrNull { candidate ->
                val client = clientFor(candidate.account)
                when (val outcome = tryConnect(client, "退出后切换账号")) {
                    Outcome.NeedsLogin -> null.also { client.close() }
                    else -> client to outcome
                }
            }
            if (next == null) {
                PikoLog.i(TAG, "没有可用的已保存账号，回登录页")
                addingPrefill = ""
                return@withLock
            }
            val (client, outcome) = next
            adopt(client)
            if (outcome == Outcome.Offline) scheduleReconnect(client)
        }
    }

    /** 从列表里去掉一个不在用的账号，连同它的会话。在用的那个走 [logout]。 */
    fun forget(account: String): Job = scope.launch {
        switchLock.withLock {
            if (_currentClient.value?.account == account) return@withLock
            PikoLog.i(TAG, "移除已保存的账号")
            runSuspendCatching { auth.logout(account) }.onFailure { PikoLog.w(TAG, "移除账号失败，${describe(it)}") }
        }
    }

    /** 改 [account] 在列表里记着的资料或用量。不在列表里（已经退出）时不写。 */
    suspend fun updateAccount(account: String, edit: (KnownAccount) -> KnownAccount) {
        val current = accounts.value.find(account) ?: return
        val next = edit(current)
        if (next == current) return
        runSuspendCatching {
            auth.updateProfile(
                account,
                AccountProfile(
                    name = next.name,
                    avatarUrl = next.avatarUrl,
                    email = next.email,
                    usageBytes = next.usageBytes,
                    limitBytes = next.limitBytes,
                ),
            )
        }.onFailure { PikoLog.w(TAG, "账号资料保存失败，${describe(it)}") }
    }

    /** 登录或切换成功的 [client] 成为当前账号。调用方持有 [switchLock]。 */
    private suspend fun adopt(client: PikPakClient) {
        auth.select(client.account)
        reconnectJob?.cancel()
        val previous = _currentClient.value
        _currentClient.value = client
        if (previous != null && previous !== client) previous.close()
        _addingAccount.value = false
    }

    /** 断网启动后按退避重试，直到连上、要重新登录或 client 被替换。 */
    private fun scheduleReconnect(client: PikPakClient) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.Default) {
            var backoffMs = RECONNECT_INITIAL_DELAY_MS
            var attempt = 1
            while (_currentClient.value === client) {
                delay(backoffMs)
                when (tryConnect(client, "第 $attempt 次重连（等了 ${backoffMs / 1000} 秒）")) {
                    Outcome.Ready -> return@launch
                    Outcome.Offline -> backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
                    Outcome.NeedsLogin -> {
                        PikoLog.w(TAG, "重连时会话已失效，回登录页")
                        if (_currentClient.compareAndSet(client, null)) {
                            client.close()
                            addingPrefill = client.account
                            signInNotice = SESSION_EXPIRED
                            auth.select(null)
                        }
                        return@launch
                    }
                }
                attempt++
            }
        }
    }

    private enum class Outcome { Ready, Offline, NeedsLogin }

    /** 让 [client] 拿到可用的会话。[step] 写进日志，说明这次因何而起。 */
    private suspend fun tryConnect(client: PikPakClient, step: String): Outcome {
        val started = TimeSource.Monotonic.markNow()
        fun took() = "用时 ${started.elapsedNow().inWholeMilliseconds} ms"
        return try {
            client.login()
            PikoLog.i(TAG, "$step：会话可用，${took()}")
            Outcome.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: ReloginRequiredException) {
            PikoLog.w(TAG, "$step：需要重新登录，${took()}")
            Outcome.NeedsLogin
        } catch (e: AuthRejectedException) {
            PikoLog.w(TAG, "$step：认证服务拒绝，${describe(e)}，${took()}")
            Outcome.NeedsLogin
        } catch (e: Throwable) {
            // 断网、超时、认证服务暂时不可用：会话原样留着
            PikoLog.w(TAG, "$step：网络出错，稍后重试，${describe(e)}，${took()}")
            Outcome.Offline
        }
    }

    // 预算与 SDK 默认值相同，显式写出是因为它们直接决定下载与播放的并发上限，改 SDK 版本时这里不该悄悄跟着变
    private fun clientFor(account: String): PikPakClient = PikPakClient(
        account = account,
        // SDK 在会话不可用时会转去用密码登录。密码不在这里，也不该由它来登录
        passwordSupplier = { throw ReloginRequiredException(account) },
        // 每个 client 一份：它要认出「同一个 client 把同一个令牌又要了一次」
        sessionStore = BrokerSessionStore(auth),
        httpClient = httpClient,
        connectionBudget = CONNECTION_BUDGET,
        accountConnectionBudget = ACCOUNT_CONNECTION_BUDGET,
    )

    private companion object {
        const val TAG = "Auth"
        const val CONNECTION_BUDGET = 8
        const val ACCOUNT_CONNECTION_BUDGET = 16
        const val RECONNECT_INITIAL_DELAY_MS = 2_000L
        const val RECONNECT_MAX_DELAY_MS = 60_000L
        const val SESSION_EXPIRED = "登录已失效，请重新登录"
    }
}

/** 日志里写的失败原因：只有错误码、错误名与异常类型，不带服务端的原文与任何令牌。 */
private fun describe(error: Throwable): String = when (error) {
    is AuthRejectedException -> "错误码 ${error.code}（${error.error}）" + (error.httpStatus?.let { "，HTTP $it" } ?: "")
    is PikPakException -> "错误码 ${error.errorCode}（${error.errorMessage}）" + (error.httpStatus?.let { "，HTTP $it" } ?: "")
    else -> error::class.simpleName ?: "未知异常"
}
