package dev.pikseek.auth

import dev.pikseek.platform.toByteArray
import dev.pikseek.platform.toNSData
import kotlinx.io.IOException
import platform.Foundation.NSData

/**
 * iOS 上认证模块要用的两样系统能力：发 HTTPS 请求（URLSession）与钥匙串。
 * 这两样用 Swift 实现（iosApp 里的 AuthBridges.swift），程序启动时在建 [AuthBroker] 之前交进来。
 * 这边只定接口与规则：白名单检查、失败怎么分类、读回比对都在 Kotlin 这一侧，和桌面版走同一份代码。
 */
object IosAuthPlatform {
    var http: AuthHttpBridge? = null
    var keychain: KeychainBridge? = null
}

/** 一次 POST 的结果。[failure] 非空表示没拿到响应。 */
class AuthHttpResult(
    val status: Int,
    val body: String,
    val failure: String?,
    /** 连接没建起来（请求没发出去）。 */
    val connectFailure: Boolean,
)

interface AuthHttpBridge {
    /** 同步发一个 POST 并等结果。实现不得跟随重定向，不得打印请求与响应。 */
    fun post(url: String, headers: Map<String, String>, body: NSData): AuthHttpResult
}

/** 钥匙串读一项的结果。[status] 是系统的 OSStatus：0 成功，-25300 没有这一项。 */
class KeychainReadResult(val data: NSData?, val status: Int)

interface KeychainBridge {
    /** 写入（已有则覆盖）。返回 OSStatus，0 为成功。 */
    fun write(name: String, data: NSData): Int

    fun read(name: String): KeychainReadResult

    fun delete(name: String)

    fun names(): List<String>
}

private class BridgedTransport(private val bridge: AuthHttpBridge) : PikPakAuthClient.Transport {
    override fun post(uri: AuthUrl, headers: Map<String, String>, body: ByteArray): PikPakAuthClient.Reply {
        // 第二道闸：就算有人绕过 PikPakAuthClient 直接用这个传输层，也出不了白名单
        AuthNetworkPolicy.check(uri)
        val result = bridge.post(uri.toString(), headers, body.toNSData())
        val failure = result.failure
        if (failure != null) {
            throw if (result.connectFailure) AuthConnectException(failure) else IOException(failure)
        }
        return PikPakAuthClient.Reply(result.status, result.body)
    }
}

actual fun defaultAuthTransport(): PikPakAuthClient.Transport =
    BridgedTransport(IosAuthPlatform.http ?: error("IosAuthPlatform.http 还没有设置"))

internal actual fun isConnectFailure(error: Throwable): Boolean = error is AuthConnectException

/**
 * 钥匙串里的机密。每一项只在本机、解锁过一次之后可读，不随 iCloud 同步、不进备份到别的设备
 * （Swift 一侧写入时用的是 AfterFirstUnlockThisDeviceOnly）。
 *
 * 规则与桌面的 DPAPI 存储相同：写完读回逐字节比对，对不上就删掉并报失败；没有明文兜底。
 */
class KeychainCredentialStore(private val bridge: KeychainBridge) : SecureCredentialStore {
    private val unusable: String? by lazy { probe() }

    override fun problem(): String? = unusable

    override fun write(name: String, secret: ByteArray) {
        unusable?.let { throw IOException(it) }
        val status = bridge.write(name, secret.toNSData())
        if (status != 0) {
            bridge.delete(name)
            throw IOException("钥匙串写入失败（$status）")
        }
        val back = bridge.read(name)
        val stored = back.data?.toByteArray()
        val same = back.status == 0 && stored != null && stored.contentEquals(secret)
        stored?.fill(0)
        if (!same) {
            bridge.delete(name)
            throw IOException("钥匙串写入后读回不一致（${back.status}）")
        }
    }

    override fun read(name: String): ByteArray? {
        val result = bridge.read(name)
        return when {
            result.status == ITEM_NOT_FOUND -> null
            result.status == 0 -> result.data?.toByteArray()
            else -> throw IOException("钥匙串读取失败（${result.status}）")
        }
    }

    override fun delete(name: String) = bridge.delete(name)

    override fun names(): List<String> = bridge.names()

    /** 真写一项、读回、删掉。钥匙串用不了（比如未签名的包没有钥匙串访问组）时在这里就知道。 */
    private fun probe(): String? {
        val name = "probe"
        val sample = byteArrayOf(1, 2, 3, 4)
        val status = bridge.write(name, sample.toNSData())
        if (status != 0) return "钥匙串不可用（$status），登录信息不会保存，下次启动要重新登录"
        val back = bridge.read(name)
        bridge.delete(name)
        return if (back.status == 0 && back.data?.toByteArray()?.contentEquals(sample) == true) null
        else "钥匙串读回不一致（${back.status}），登录信息不会保存，下次启动要重新登录"
    }

    private companion object {
        const val ITEM_NOT_FOUND = -25300
    }
}
