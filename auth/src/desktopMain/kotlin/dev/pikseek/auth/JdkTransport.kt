package dev.pikseek.auth

import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * JDK 的 HTTP 客户端。认证这条路上没有第三方网络库。不跟随重定向；代理在建客户端时取进程默认的
 * ProxySelector，所以推迟到第一次请求才建，那时程序已经装好自己的代理选择。
 */
class JdkTransport : PikPakAuthClient.Transport {
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }

    override fun post(uri: AuthUrl, headers: Map<String, String>, body: ByteArray): PikPakAuthClient.Reply {
        // 第二道闸：就算有人绕过 PikPakAuthClient 直接用这个传输层，也出不了白名单
        AuthNetworkPolicy.check(uri)
        val request = HttpRequest.newBuilder(URI(uri.toString()))
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("认证请求被中断", e)
        }
        return PikPakAuthClient.Reply(response.statusCode(), response.body().orEmpty())
    }
}

actual fun defaultAuthTransport(): PikPakAuthClient.Transport = JdkTransport()

internal actual fun isConnectFailure(error: Throwable): Boolean =
    error is AuthConnectException || error is ConnectException || error is HttpConnectTimeoutException

/** 给拿着 java.net.URI 的调用方（代理选择、测试）。 */
fun AuthNetworkPolicy.check(uri: URI) {
    check(
        AuthUrl(
            scheme = uri.scheme.orEmpty(),
            host = uri.host.orEmpty(),
            port = uri.port,
            path = uri.path.orEmpty(),
            query = uri.rawQuery,
            userInfo = uri.rawUserInfo,
        ),
    )
}
