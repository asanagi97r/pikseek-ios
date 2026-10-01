package dev.pikseek.auth

import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.Session
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 认证代码是自己写的，而这台开发机上没有 PikPak 账号可试。所以拿一份已知能用的实现当参照：
 * PikPak SDK（Piko 所用的 pikpak-kotlin）发出的认证请求。两边对着同一组假回应各跑一遍，
 * 逐个请求比地址、关键请求头与请求体，必须完全一致。
 *
 * SDK 只在测试里出现，认证模块的正式代码不依赖它。
 */
class WireCompatibilityTest {
    private class Wire(val url: String, val userAgent: String?, val deviceId: String?, val contentType: String?, val body: JsonObject)

    private val account = "someone@example.com"
    private val password = "p@ss \"word\" \\ with ünïcode 密码"

    private fun answerFor(path: String): String = when (path) {
        "/v1/shield/captcha/init" -> """{"captcha_token":"captcha-from-server","expires_in":300}"""
        "/v1/auth/signin" -> FakeTransport.grant("access-A", "refresh-A")
        "/v1/auth/token" -> FakeTransport.grant("access-B", "refresh-B")
        else -> "{}"
    }

    private fun sdkClient(seen: MutableList<Wire>, store: InMemorySessionStore): PikPakClient {
        val engine = MockEngine { request: HttpRequestData ->
            val content = request.body as TextContent
            seen += Wire(
                url = request.url.toString(),
                userAgent = request.headers[HttpHeaders.UserAgent],
                deviceId = request.headers["X-Device-Id"],
                contentType = content.contentType.toString(),
                body = Json.parseToJsonElement(content.text).jsonObject,
            )
            respond(answerFor(request.url.encodedPath), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return PikPakClient(account = account, password = password, sessionStore = store, httpClient = HttpClient(engine))
    }

    private fun ours(seen: MutableList<Wire>): FakeTransport = FakeTransport().also { transport ->
        transport.answer = { request ->
            seen += Wire(
                url = request.uri.toString(),
                userAgent = request.headers["User-Agent"],
                deviceId = request.headers["X-Device-Id"],
                contentType = request.headers["Content-Type"],
                body = request.body,
            )
            FakeTransport.reply(200, answerFor(request.uri.path))
        }
    }

    private fun assertSameWire(expected: List<Wire>, actual: List<Wire>) {
        assertEquals(expected.map { it.url }, actual.map { it.url }, "请求的地址与顺序")
        expected.zip(actual).forEach { (sdk, mine) ->
            assertEquals(sdk.userAgent, mine.userAgent, "User-Agent @ ${sdk.url}")
            assertEquals(sdk.deviceId, mine.deviceId, "X-Device-Id @ ${sdk.url}")
            assertEquals(sdk.contentType, mine.contentType, "Content-Type @ ${sdk.url}")
            assertEquals(sdk.body, mine.body, "请求体 @ ${sdk.url}")
        }
    }

    @Test
    fun passwordSignInMatchesTheSdkByteForByte(): Unit = runBlocking {
        val fromSdk = ArrayList<Wire>()
        val sdk = sdkClient(fromSdk, InMemorySessionStore())
        val sdkSession = sdk.login()
        sdk.close()

        val fromOurs = ArrayList<Wire>()
        val session = AuthBroker(FakeStore(), ours(fromOurs)).login(account, password.toCharArray())

        assertEquals(2, fromSdk.size)
        assertSameWire(fromSdk, fromOurs)
        // 回应也解析成同样的会话
        assertEquals(sdkSession.accessToken, session.accessToken)
        assertEquals(sdkSession.sub, session.userId)
        assertEquals(sdkSession.expiresAt, session.expiresAtEpochSeconds, absoluteTolerance = 2)
    }

    @Test
    fun refreshMatchesTheSdkByteForByte(): Unit = runBlocking {
        val fromSdk = ArrayList<Wire>()
        val store = InMemorySessionStore()
        // 一份已过期、带刷新令牌的会话：SDK 的 login() 会拿它去刷新
        store.save(account, Session(accessToken = "stale", refreshToken = "refresh-A", sub = "user-42", expiresAt = 1))
        val sdk = sdkClient(fromSdk, store)
        val sdkSession = sdk.login()
        sdk.close()

        val fromOurs = ArrayList<Wire>()
        val session = AuthBroker(FakeStore(), ours(fromOurs)).loginWithRefreshToken(account, "refresh-A".toCharArray())

        assertEquals(1, fromSdk.size)
        assertSameWire(fromSdk, fromOurs)
        assertEquals(sdkSession.accessToken, session.accessToken)
    }

    private fun assertEquals(expected: Long, actual: Long, absoluteTolerance: Long) {
        kotlin.test.assertTrue(kotlin.math.abs(expected - actual) <= absoluteTolerance, "expected $expected ± $absoluteTolerance, got $actual")
    }
}
