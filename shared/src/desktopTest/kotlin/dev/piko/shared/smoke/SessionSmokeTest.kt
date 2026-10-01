package dev.piko.shared.smoke

import dev.piko.shared.data.PikoClientManager
import dev.pikseek.auth.AuthBroker
import dev.pikseek.auth.AuthRejectedException
import dev.pikseek.auth.CredentialPersistence
import dev.pikseek.auth.PikPakAuthClient
import dev.pikseek.auth.SecureCredentialStore
import io.github.nihildigit.pikpak.getQuota
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope

/**
 * 登录态的恢复、失败与退出，三样东西合在一起测：PikoClientManager、独立的认证模块（AuthBroker）与真实的 PikPak SDK。
 *
 * 网络分成两头，各接各的假服务端，正好对应正式程序里的两条路：
 * - 认证请求（取验证令牌、登录、刷新）只从认证模块发出，接到 [FakeAuthTransport]；
 * - SDK 的文件接口经 httpClient 参数接到 [FakePikPakServer]。
 *
 * 所以「SDK 有没有自己去碰认证接口」是直接数得出来的：看 [FakePikPakServer.calls] 里有没有认证的路径。
 */
class SessionSmokeTest {
    private val account = "smoke@piko.dev"

    /** 认证服务的假实现，与 [FakePikPakServer] 共用断网与拒绝登录两个开关。 */
    private class FakeAuthTransport(private val server: FakePikPakServer) : PikPakAuthClient.Transport {
        val signIns = AtomicInteger()
        val refreshes = AtomicInteger()
        val paths = java.util.concurrent.CopyOnWriteArrayList<String>()

        @Volatile
        var rejectRefresh = false

        override fun post(uri: URI, headers: Map<String, String>, body: ByteArray): PikPakAuthClient.Reply {
            if (server.offline) throw IOException("smoke: network is down")
            paths += uri.path
            return when (uri.path) {
                "/v1/shield/captcha/init" -> PikPakAuthClient.Reply(200, """{"captcha_token":"CAP","expires_in":300}""")
                "/v1/auth/signin" ->
                    if (server.rejectSignIn) {
                        PikPakAuthClient.Reply(400, """{"error_code":4002,"error":"invalid_account_or_password"}""")
                    } else {
                        token("AT-signin-${signIns.incrementAndGet()}")
                    }
                "/v1/auth/token" ->
                    if (rejectRefresh) {
                        PikPakAuthClient.Reply(400, """{"error_code":4126,"error":"invalid_grant"}""")
                    } else {
                        token("AT-refresh-${refreshes.incrementAndGet()}")
                    }
                else -> PikPakAuthClient.Reply(404, "{}")
            }
        }

        private fun token(access: String) =
            PikPakAuthClient.Reply(200, """{"access_token":"$access","refresh_token":"RT-$access","sub":"UID","expires_in":3600}""")
    }

    /** 内存里的凭据存储。正式程序里这个位置是 DPAPI。 */
    private class MemoryCredentialStore : SecureCredentialStore {
        val items = ConcurrentHashMap<String, ByteArray>()

        override fun problem(): String? = null

        override fun write(name: String, secret: ByteArray) {
            items[name] = secret.copyOf()
        }

        override fun read(name: String): ByteArray? = items[name]?.copyOf()

        override fun delete(name: String) {
            items.remove(name)
        }

        override fun names(): List<String> = items.keys.sorted()

        val sessionCount: Int get() = items.keys.count { it.startsWith("session_") }
    }

    private class Rig(val server: FakePikPakServer, scope: CoroutineScope, val store: MemoryCredentialStore = MemoryCredentialStore(), clock: () -> Long = System::currentTimeMillis) {
        val transport = FakeAuthTransport(server)
        val broker = AuthBroker(store, transport, nowMillis = clock)
        val manager = PikoClientManager(broker, scope, server.httpClient())
    }

    /** SDK 那一头有没有发过认证请求。它不该发：认证只从认证模块出去。 */
    private fun FakePikPakServer.authCalls(): List<String> = calls.filter { "/v1/auth/" in it || "captcha" in it }

    /**
     * 防的是离线冷启动被踢回登录页：访问令牌过期、刷新因断网失败时，应保留登录态先进主界面，
     * 网络恢复后在后台补上刷新。
     */
    @Test
    fun `an offline cold start keeps the session and refreshes once the network is back`() = smoke { scope ->
        val server = FakePikPakServer()
        val store = MemoryCredentialStore()
        var now = System.currentTimeMillis()
        // 上一次运行：登录过
        Rig(server, scope, store) { now }.also { first ->
            awaitUntil("恢复流程结束") { !first.manager.isInitializing.value }
            first.manager.login(account, "pw".toCharArray()).getOrThrow()
        }
        // 过了两小时，会话已过期；这一次启动时断着网
        now += 2 * 3600_000L
        server.offline = true
        val rig = Rig(server, scope, store) { now }
        awaitUntil("恢复流程结束") { !rig.manager.isInitializing.value }
        assertNotNull(rig.manager.currentClient.value, "断网不应等同于登录失效")
        assertEquals(0, rig.transport.refreshes.get())
        assertEquals(1, store.sessionCount)

        server.offline = false
        awaitUntil("网络恢复后会话被刷新", timeoutMs = 15_000) { rig.transport.refreshes.get() == 1 }
        assertNotNull(rig.manager.currentClient.value)
        // 刷新后的令牌真的被 SDK 拿去用了
        awaitUntil("重连完成") { rig.manager.currentClient.value?.currentSession?.accessToken == "AT-refresh-1" }
        assertNotNull(rig.manager.currentClient.value!!.getQuota())
        assertTrue(server.authCalls().isEmpty(), "SDK 自己发了认证请求：${server.authCalls()}")
    }

    /** 防的是密码错误时界面卡在加载中、或把什么东西存了下来。 */
    @Test
    fun `a rejected password surfaces the reason and stores nothing`() = smoke { scope ->
        val server = FakePikPakServer().apply { rejectSignIn = true }
        val rig = Rig(server, scope)
        awaitUntil("恢复流程结束") { !rig.manager.isInitializing.value }

        val password = "wrong".toCharArray()
        val failure = rig.manager.login(account, password).exceptionOrNull()
        assertTrue(failure is AuthRejectedException, "应当报出服务端的拒绝，实际是 $failure")
        assertEquals(4002, (failure as AuthRejectedException).code)
        assertTrue(password.all { it == '\u0000' }, "密码用完应当清零")
        assertNull(rig.manager.currentClient.value)
        assertTrue(rig.store.items.isEmpty(), "登录失败不能落下任何东西")
        assertNull(rig.manager.accounts.value.current)
    }

    /**
     * 防的是退出一个账号时连带清掉别的账号的会话，或退出后停在登录页、不切到还保存着的账号。
     */
    @Test
    fun `logging out of one account switches to another and keeps its session`() = smoke { scope ->
        val server = FakePikPakServer()
        val rig = Rig(server, scope)
        val manager = rig.manager
        awaitUntil("恢复流程结束") { !manager.isInitializing.value }
        val other = "other@piko.dev"

        manager.login(account, "pw-a".toCharArray()).getOrThrow()
        manager.beginAddingAccount()
        manager.login(other, "pw-b".toCharArray()).getOrThrow()
        assertEquals(other, manager.currentClient.value?.account)
        assertEquals(false, manager.addingAccount.value)
        assertEquals(listOf(account, other), manager.accounts.value.accounts.map { it.account })
        assertEquals(2, rig.store.sessionCount)

        manager.switchTo(account).getOrThrow()
        assertEquals(account, manager.currentClient.value?.account)
        assertEquals(account, manager.accounts.value.current)

        manager.logout().join()
        assertEquals(other, manager.currentClient.value?.account)
        assertEquals(listOf(other), manager.accounts.value.accounts.map { it.account })
        // 退出的那个账号的会话删了，另一个的还在
        assertEquals(1, rig.store.sessionCount)
        assertEquals(2, rig.transport.signIns.get())
    }

    /** 防的是退出登录后本机还留着能用的会话。密码从一开始就没存过。 */
    @Test
    fun `logging out leaves no session and no password was ever stored`() = smoke { scope ->
        val server = FakePikPakServer()
        val rig = Rig(server, scope)
        awaitUntil("恢复流程结束") { !rig.manager.isInitializing.value }

        rig.manager.login(account, "pw-secret-123".toCharArray()).getOrThrow()
        assertNotNull(rig.manager.currentClient.value)
        assertEquals(CredentialPersistence.Dpapi, rig.broker.status().persistence)
        val stored = rig.store.items.values.joinToString("\n") { it.decodeToString() }
        assertFalse("pw-secret-123" in stored, "密码不该出现在存储里")

        rig.manager.logout().join()
        assertNull(rig.manager.currentClient.value)
        assertEquals(0, rig.store.sessionCount)
        assertNull(rig.manager.accounts.value.current)
        assertTrue(rig.manager.accounts.value.accounts.isEmpty())
    }

    /**
     * 主程序与 SDK 这一侧从不自己做认证：登录、过期后的刷新都只经认证模块；
     * SDK 手里的会话不带刷新令牌，它想刷新也刷新不了。
     */
    @Test
    fun `the sdk never authenticates on its own and never holds the refresh token`() = smoke { scope ->
        val server = FakePikPakServer()
        var now = System.currentTimeMillis()
        val rig = Rig(server, scope) { now }
        awaitUntil("恢复流程结束") { !rig.manager.isInitializing.value }
        val client = rig.manager.login(account, "pw".toCharArray()).getOrThrow()

        assertNotNull(client.getQuota())
        assertEquals("AT-signin-1", client.currentSession?.accessToken)
        assertEquals("", client.currentSession?.refreshToken, "刷新令牌不该进 SDK")
        assertEquals(listOf("/v1/shield/captcha/init", "/v1/auth/signin"), rig.transport.paths.toList())
        assertTrue(server.authCalls().none { "/v1/auth/" in it }, "SDK 自己发了登录或刷新请求：${server.authCalls()}")
    }

    /** 用着用着刷新令牌失效了：认证模块删掉会话，主程序回登录页，账号名留着预填。 */
    @Test
    fun `a dead refresh token sends the user back to sign in`() = smoke { scope ->
        val server = FakePikPakServer()
        val store = MemoryCredentialStore()
        var now = System.currentTimeMillis()
        Rig(server, scope, store) { now }.also { first ->
            awaitUntil("恢复流程结束") { !first.manager.isInitializing.value }
            first.manager.login(account, "pw".toCharArray()).getOrThrow()
        }
        now += 2 * 3600_000L
        val rig = Rig(server, scope, store) { now }.apply { transport.rejectRefresh = true }
        awaitUntil("恢复流程结束") { !rig.manager.isInitializing.value }

        assertNull(rig.manager.currentClient.value)
        assertEquals(account, rig.manager.addingPrefill)
        assertEquals(0, store.sessionCount)
        // 账号还在列表里，只是不再是当前账号
        assertEquals(listOf(account), rig.manager.accounts.value.accounts.map { it.account })
        assertNull(rig.manager.accounts.value.current)
        assertTrue(server.authCalls().isEmpty(), "SDK 不该自己去登录：${server.authCalls()}")
    }
}
