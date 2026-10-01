package dev.pikseek.auth

import java.io.IOException
import java.net.ConnectException
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonPrimitive

class AuthBrokerTest {
    private val transport = FakeTransport()
    private val store = FakeStore()
    private val clock = FakeClock()
    private fun broker(store: SecureCredentialStore = this.store) = AuthBroker(store, transport, nowMillis = { clock.millis })

    @Test
    fun loginSendsPasswordOnlyToSignInAndWipesIt(): Unit = runBlocking {
        val password = "correct horse".toCharArray()
        val session = broker().login(" me@example.com ", password)

        assertEquals("me@example.com", session.account)
        assertEquals("access-signin-1", session.accessToken)
        assertEquals("user-42", session.userId)
        // 调用方手里的那份已被清零
        assertTrue(password.all { it == '\u0000' })

        assertEquals(listOf("/v1/shield/captcha/init", "/v1/auth/signin"), transport.seen.map { it.uri.path })
        assertTrue(transport.seen.all { it.uri.host == "user.mypikpak.com" && it.uri.scheme == "https" })
        // 密码只出现在登录那一个请求里
        assertNull(transport.seen[0].body["password"])
        assertEquals("correct horse", transport.seen[1].body["password"]?.jsonPrimitive?.content)
        assertEquals("captcha-1", transport.seen[1].body["captcha_token"]?.jsonPrimitive?.content)
    }

    @Test
    fun passwordIsNeverStored(): Unit = runBlocking {
        broker().login("me@example.com", "correct horse".toCharArray())
        val everything = store.items.values.joinToString("\n") { it.decodeToString() }
        assertFalse("correct horse" in everything)
        // 存下的是会话与账号列表两项
        assertEquals(2, store.items.size)
        assertTrue(store.items.keys.any { it.startsWith("session_") })
        // 账号名不进存储项的名字
        assertTrue(store.items.keys.none { "example" in it })
    }

    @Test
    fun publicSessionHasNoRefreshTokenAndDoesNotPrintTokens(): Unit = runBlocking {
        val session = broker().login("me@example.com", "pw-123456".toCharArray())
        assertFalse("access-signin-1" in session.toString())
        // AuthSession 上没有任何字段带着刷新令牌
        val fields = AuthSession::class.java.declaredFields.map { it.name }
        assertTrue(fields.none { "refresh" in it.lowercase() })
    }

    @Test
    fun usableSessionIsReturnedWithoutNetwork(): Unit = runBlocking {
        val broker = broker()
        broker.login("me@example.com", "pw-123456".toCharArray())
        val before = transport.seen.size
        clock.advanceSeconds(60)
        assertEquals("access-signin-1", broker.session("me@example.com")?.accessToken)
        assertEquals(before, transport.seen.size)
    }

    @Test
    fun expiredSessionIsRefreshedAndStored(): Unit = runBlocking {
        val broker = broker()
        broker.login("me@example.com", "pw-123456".toCharArray())
        clock.advanceSeconds(7200)
        val session = broker.session("me@example.com")
        assertEquals("access-refresh-1", session?.accessToken)
        val refresh = transport.seen.last()
        assertEquals("/v1/auth/token", refresh.uri.path)
        assertEquals("refresh_token", refresh.body["grant_type"]?.jsonPrimitive?.content)
        assertEquals("refresh-signin-1", refresh.body["refresh_token"]?.jsonPrimitive?.content)
        // 换下来的新刷新令牌已经落盘：另起一个 broker（等于重启）读到的是新的
        val restarted = broker()
        restarted.restore()
        clock.advanceSeconds(7200)
        restarted.session("me@example.com")
        assertEquals("refresh-refresh-1", transport.seen.last().body["refresh_token"]?.jsonPrimitive?.content)
    }

    @Test
    fun concurrentCallersShareOneRefresh(): Unit = runBlocking {
        val broker = broker()
        broker.login("me@example.com", "pw-123456".toCharArray())
        clock.advanceSeconds(7200)
        val tokens = (1..16).map { async { broker.session("me@example.com")?.accessToken } }.awaitAll()
        assertEquals(setOf<String?>("access-refresh-1"), tokens.toSet())
        assertEquals(1, transport.refreshes)
    }

    @Test
    fun restartRestoresAccountsAndSessionWithoutPassword(): Unit = runBlocking {
        val first = broker()
        first.login("me@example.com", "pw-123456".toCharArray())
        first.select("me@example.com")
        first.updateProfile("me@example.com", AccountProfile(name = "我", usageBytes = 10, limitBytes = 100))

        val restarted = broker()
        val accounts = restarted.restore()
        assertEquals("me@example.com", accounts.current)
        assertEquals("我", accounts.accounts.single().name)
        assertEquals(10, accounts.accounts.single().usageBytes)
        val before = transport.seen.size
        assertEquals("access-signin-1", restarted.session("me@example.com")?.accessToken)
        assertEquals(before, transport.seen.size)
    }

    @Test
    fun rejectedRefreshDeletesSessionAndAsksForRelogin(): Unit = runBlocking {
        val broker = broker()
        broker.login("me@example.com", "pw-123456".toCharArray())
        broker.select("me@example.com")
        clock.advanceSeconds(7200)
        transport.answer = { request ->
            if (request.uri.path == "/v1/auth/token") FakeTransport.reply(400, FakeTransport.rejection(4126, "invalid_grant")) else null
        }
        // 先订阅再触发：这是一次性事件，没有人听的时候不留着
        val announced = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(2_000) { broker.reloginRequired.first() } }
        assertFailsWith<ReloginRequiredException> { broker.session("me@example.com") }
        assertEquals("me@example.com", announced.await())
        assertTrue(store.items.keys.none { it.startsWith("session_") })
        // 账号留在列表里（登录页据此预填账号名），只是不再是当前账号
        assertNull(broker.accounts.value.current)
        assertEquals(1, broker.accounts.value.accounts.size)
        // 之后再问，不再发请求
        val before = transport.seen.size
        assertNull(broker.session("me@example.com"))
        assertEquals(before, transport.seen.size)
    }

    @Test
    fun networkFailureKeepsSession(): Unit = runBlocking {
        val broker = broker()
        broker.login("me@example.com", "pw-123456".toCharArray())
        clock.advanceSeconds(7200)
        transport.answer = { request -> if (request.uri.path == "/v1/auth/token") throw IOException("断网") else null }
        assertFailsWith<IOException> { broker.session("me@example.com") }
        assertTrue(store.items.keys.any { it.startsWith("session_") })
        // 网络恢复后照常刷新
        transport.answer = { null }
        assertEquals("access-refresh-1", broker.session("me@example.com")?.accessToken)
    }

    @Test
    fun serverBusyIsTransientNotRelogin(): Unit = runBlocking {
        val broker = broker()
        broker.login("me@example.com", "pw-123456".toCharArray())
        clock.advanceSeconds(7200)
        transport.answer = { request -> if (request.uri.path == "/v1/auth/token") FakeTransport.reply(502, "bad gateway") else null }
        assertFailsWith<AuthUnavailableException> { broker.session("me@example.com") }
        assertTrue(store.items.keys.any { it.startsWith("session_") })
    }

    @Test
    fun connectFailureIsRetriedBeforeGivingUp(): Unit = runBlocking {
        var failures = 0
        transport.answer = { request ->
            if (request.uri.path == "/v1/shield/captcha/init" && failures < 2) {
                failures++
                throw ConnectException("拒绝连接")
            } else {
                null
            }
        }
        val session = broker().login("me@example.com", "pw-123456".toCharArray())
        assertEquals("access-signin-1", session.accessToken)
        assertEquals(2, failures)
    }

    @Test
    fun wrongPasswordSurfacesServerRejection(): Unit = runBlocking {
        transport.answer = { request ->
            if (request.uri.path == "/v1/auth/signin") FakeTransport.reply(400, FakeTransport.rejection(4022, "invalid_account_or_password")) else null
        }
        val password = "wrong-pw".toCharArray()
        val error = assertFailsWith<AuthRejectedException> { broker().login("me@example.com", password) }
        assertEquals(4022, error.code)
        assertEquals(400, error.httpStatus)
        assertTrue(password.all { it == '\u0000' })
        assertTrue(store.items.isEmpty())
        // 报错文案里没有密码
        assertFalse("wrong-pw" in error.message.orEmpty())
    }

    @Test
    fun captchaDemandIsReportedNotBypassed(): Unit = runBlocking {
        transport.answer = { request ->
            if (request.uri.path == "/v1/shield/captcha/init") FakeTransport.reply(200, """{"url":"https://user.mypikpak.com/captcha/v2/spritePuzzle.html"}""") else null
        }
        val error = assertFailsWith<AuthRejectedException> { broker().login("me@example.com", "pw-123456".toCharArray()) }
        assertEquals("captcha_required", error.error)
        // 没拿到验证令牌就不发密码
        assertTrue(transport.seen.none { it.uri.path == "/v1/auth/signin" })
    }

    @Test
    fun storeFailureMeansMemoryOnlyAndNothingOnDisk(): Unit = runBlocking {
        store.failWrites = true
        val broker = broker()
        val session = broker.login("me@example.com", "pw-123456".toCharArray())
        // 登录本身成功，本次运行照常能用
        assertEquals("access-signin-1", session.accessToken)
        assertEquals("access-signin-1", broker.session("me@example.com")?.accessToken)
        assertFalse(broker.isPersisted("me@example.com"))
        val status = broker.status()
        assertEquals(CredentialPersistence.MemoryOnly, status.persistence)
        assertNotNull(status.storeProblem)
        // 没有任何兜底：存储里什么都没有
        assertTrue(store.items.isEmpty())
        // 重启后要重新登录
        store.failWrites = false
        val restarted = broker()
        restarted.restore()
        assertNull(restarted.session("me@example.com"))
    }

    @Test
    fun failedSaveAfterRefreshRemovesTheStaleCiphertext(): Unit = runBlocking {
        val broker = broker()
        broker.login("me@example.com", "pw-123456".toCharArray())
        clock.advanceSeconds(7200)
        store.failWrites = true
        assertEquals("access-refresh-1", broker.session("me@example.com")?.accessToken)
        // 旧的那份里的刷新令牌已被这次刷新作废，留着只会让下次启动拿废令牌去试
        assertTrue(store.items.keys.none { it.startsWith("session_") })
        assertEquals(CredentialPersistence.MemoryOnly, broker.status().persistence)
    }

    @Test
    fun undecryptableStoreLooksLikeFirstRun(): Unit = runBlocking {
        broker().login("me@example.com", "pw-123456".toCharArray())
        // 整个数据目录被拷到了另一台电脑：密文都在，一个也解不开
        store.failReads = true
        val moved = broker()
        assertTrue(moved.restore().accounts.isEmpty())
        assertNull(moved.session("me@example.com"))
    }

    @Test
    fun logoutRemovesSessionAndAccount(): Unit = runBlocking {
        val broker = broker()
        broker.login("me@example.com", "pw-123456".toCharArray())
        broker.login("other@example.com", "pw-654321".toCharArray())
        broker.logout("me@example.com")
        assertNull(broker.session("me@example.com"))
        assertEquals(listOf("other@example.com"), broker.accounts.value.accounts.map { it.account })
        assertEquals(1, store.items.keys.count { it.startsWith("session_") })
        assertEquals("access-signin-2", broker.session("other@example.com")?.accessToken)
    }

    @Test
    fun refreshTokenLoginExchangesImmediately(): Unit = runBlocking {
        val token = "pasted-refresh-token".toCharArray()
        val session = broker().loginWithRefreshToken("me@example.com", token)
        assertEquals("access-refresh-1", session.accessToken)
        assertTrue(token.all { it == '\u0000' })
        assertEquals("pasted-refresh-token", transport.seen.single().body["refresh_token"]?.jsonPrimitive?.content)
    }

    @Test
    fun forcedRefreshIgnoresExpiry(): Unit = runBlocking {
        val broker = broker()
        broker.login("me@example.com", "pw-123456".toCharArray())
        assertEquals("access-refresh-1", broker.refresh("me@example.com").accessToken)
        assertFailsWith<ReloginRequiredException> { broker.refresh("nobody@example.com") }
    }

    @Test
    fun statusListsAllowedHostsAndLastRequestWithoutSecrets(): Unit = runBlocking {
        val broker = broker()
        broker.login("me@example.com", "pw-123456".toCharArray())
        val status = broker.status()
        assertEquals(CredentialPersistence.Dpapi, status.persistence)
        assertEquals(1, status.storedSessions)
        assertEquals(listOf("user.mypikpak.com", "user.mypikpak.net", "user.pikpak.me", "user.pikpakdrive.com"), status.allowedHosts)
        val last = status.lastRequest!!
        assertEquals("user.mypikpak.com", last.host)
        assertEquals("密码登录", last.purpose)
        assertEquals("成功", last.outcome)
    }
}

class AuthNetworkPolicyTest {
    @Test
    fun onlyOfficialAuthEndpointsPass() {
        AuthNetworkPolicy.check(URI("https://user.mypikpak.com/v1/auth/signin"))
        AuthNetworkPolicy.check(URI("https://user.mypikpak.net/v1/auth/token"))
        AuthNetworkPolicy.check(URI("https://user.pikpakdrive.com:443/v1/shield/captcha/init"))
    }

    @Test
    fun everythingElseIsBlocked() {
        val before = AuthNetworkPolicy.blockedCount
        val blocked = listOf(
            "http://user.mypikpak.com/v1/auth/signin", // 明文
            "https://api-drive.mypikpak.com/v1/auth/signin", // 不是认证主机
            "https://user.mypikpak.com.evil.example/v1/auth/signin",
            "https://evil.example/v1/auth/signin",
            "https://user.mypikpak.com:8443/v1/auth/signin",
            "https://someone@user.mypikpak.com/v1/auth/signin",
            "https://user.mypikpak.com/v1/user/me", // 不在三个端点里
            "https://127.0.0.1/v1/auth/signin",
        )
        blocked.forEach { url -> assertFailsWith<AuthPolicyViolationException>(url) { AuthNetworkPolicy.check(URI(url)) } }
        assertEquals(before + blocked.size, AuthNetworkPolicy.blockedCount)
    }

    @Test
    fun unknownRootCannotBeBuilt() {
        assertFailsWith<AuthPolicyViolationException> {
            AuthNetworkPolicy.uri("example.com", AuthNetworkPolicy.Endpoint.Token)
        }
    }

    @Test
    fun realTransportRefusesForeignHostsBeforeSending() {
        // 就算绕过 PikPakAuthClient 直接拿传输层，白名单外的地址也发不出去
        assertFailsWith<AuthPolicyViolationException> {
            JdkTransport().post(AuthUrl.parse("https://example.com/v1/auth/signin"), emptyMap(), ByteArray(0))
        }
    }
}
