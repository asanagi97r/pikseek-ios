package dev.pikseek.auth

import java.io.IOException
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 测试里顶替网络的传输层：记下每个请求，按脚本作答。 */
internal class FakeTransport : PikPakAuthClient.Transport {
    class Seen(val uri: URI, val headers: Map<String, String>, val body: JsonObject)

    val seen = ArrayList<Seen>()

    /** 按请求给回应；返回 null 时按缺省的成功回应。 */
    var answer: (Seen) -> PikPakAuthClient.Reply? = { null }
    var refreshes = 0
    var signIns = 0

    @Synchronized
    override fun post(uri: URI, headers: Map<String, String>, body: ByteArray): PikPakAuthClient.Reply {
        val request = Seen(uri, headers, Json.parseToJsonElement(body.decodeToString()).jsonObject)
        seen += request
        answer(request)?.let { return it }
        return when (uri.path) {
            "/v1/shield/captcha/init" -> reply(200, """{"captcha_token":"captcha-1","expires_in":300}""")
            "/v1/auth/signin" -> {
                signIns++
                reply(200, grant("access-signin-$signIns", "refresh-signin-$signIns"))
            }
            "/v1/auth/token" -> {
                refreshes++
                reply(200, grant("access-refresh-$refreshes", "refresh-refresh-$refreshes"))
            }
            else -> reply(404, "{}")
        }
    }

    fun grantType(index: Int): String? = seen[index].body["grant_type"]?.jsonPrimitive?.content

    companion object {
        fun reply(status: Int, body: String) = PikPakAuthClient.Reply(status, body)

        fun grant(access: String, refresh: String, expiresIn: Long = 7200) =
            """{"token_type":"Bearer","access_token":"$access","refresh_token":"$refresh","expires_in":$expiresIn,"sub":"user-42"}"""

        fun rejection(code: Int, error: String) = """{"error":"$error","error_code":$code,"error_description":"$error"}"""
    }
}

/** 内存里的存储，能按需模拟写入失败与解不开。 */
internal class FakeStore : SecureCredentialStore {
    val items = HashMap<String, ByteArray>()
    var failWrites = false
    var failReads = false
    var writes = 0

    override fun problem(): String? = null

    override fun write(name: String, secret: ByteArray) {
        if (failWrites) throw IOException("磁盘满了")
        writes++
        items[name] = secret.copyOf()
    }

    override fun read(name: String): ByteArray? {
        if (failReads && name in items) throw IOException("解不开")
        return items[name]?.copyOf()
    }

    override fun delete(name: String) {
        items.remove(name)
    }

    override fun names(): List<String> = items.keys.sorted()
}

/** 可拨动的时钟，毫秒。 */
internal class FakeClock(var millis: Long = 1_700_000_000_000L) {
    fun advanceSeconds(seconds: Long) {
        millis += seconds * 1000
    }
}
