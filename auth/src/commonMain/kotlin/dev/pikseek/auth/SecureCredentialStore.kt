package dev.pikseek.auth

import kotlinx.io.IOException

/**
 * 机密在本机的存放处。实现只有一个原则：**加密不了就不存**，没有明文兜底。
 *
 * [AuthBroker] 只认这个接口，不知道背后是 DPAPI 还是别的；业务代码连这个接口也碰不到。
 */
interface SecureCredentialStore {
    /** 这个存储用不了的原因；能用时为 null。不抛异常。 */
    fun problem(): String?

    /**
     * 加密后写入。写完读回、解密、逐字节比对，对不上就删掉并抛异常。
     * @throws IOException 加密或写入失败。失败后这个名字下不留任何内容。
     */
    @Throws(IOException::class)
    fun write(name: String, secret: ByteArray)

    /**
     * 读出并解密。没有这一项时返回 null。
     * @throws IOException 文件在但解不开：换了 Windows 用户或换了电脑，密文原样留着
     */
    @Throws(IOException::class)
    fun read(name: String): ByteArray?

    /** 删掉这一项，不存在也算成功。 */
    fun delete(name: String)

    /** 存着的各项的名字。 */
    fun names(): List<String>
}

/**
 * 没有可用的加密存储时用它：什么都不存。会话只活在内存里，退出程序即失效，下次启动重新登录。
 */
class NoPersistenceStore(private val reason: String) : SecureCredentialStore {
    override fun problem(): String = reason

    override fun write(name: String, secret: ByteArray): Unit = throw IOException(reason)

    override fun read(name: String): ByteArray? = null

    override fun delete(name: String) = Unit

    override fun names(): List<String> = emptyList()
}
