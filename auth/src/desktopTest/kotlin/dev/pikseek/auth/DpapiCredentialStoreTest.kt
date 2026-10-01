package dev.pikseek.auth

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/** 真调 crypt32：只在 Windows 上跑。 */
class DpapiCredentialStoreTest {
    private val directory: Path = Files.createTempDirectory("pikseek-dpapi")
    private val store = DpapiCredentialStore(directory)

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun assumeWindows() = assumeTrue(System.getProperty("os.name").startsWith("Windows"))

    @Test
    fun roundTripsAndKeepsNoPlaintextOnDisk() {
        assumeWindows()
        assertNull(store.problem())
        val secret = """{"access_token":"eyJ.aaa.bbb","refresh_token":"os.refresh-secret-value"}""".toByteArray()
        store.write("session_abc", secret)

        val file = directory.resolve("session_abc.dpapi")
        val onDisk = Files.readAllBytes(file)
        val asText = String(onDisk, Charsets.ISO_8859_1)
        assertFalse("refresh-secret-value" in asText)
        assertFalse("access_token" in asText)
        assertTrue(onDisk.size > secret.size)
        assertContentEquals(secret, store.read("session_abc"))
        assertEquals(listOf("session_abc"), store.names())
        // 目录里只有密文，没有落下的临时文件
        assertEquals(listOf("session_abc.dpapi"), Files.list(directory).use { files -> files.map { it.fileName.toString() }.toList() })

        store.delete("session_abc")
        assertNull(store.read("session_abc"))
        assertTrue(store.names().isEmpty())
    }

    @Test
    fun tamperedOrForeignCiphertextIsRejectedNotGuessed() {
        assumeWindows()
        store.write("accounts", "hello".toByteArray())
        val file = directory.resolve("accounts.dpapi")
        val bytes = Files.readAllBytes(file)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        Files.write(file, bytes)
        assertFailsWith<IOException> { store.read("accounts") }
        // 一份明文冒充密文也读不出来：不存在「解不开就当明文读」的路
        Files.writeString(file, """{"refresh_token":"plain"}""")
        assertFailsWith<IOException> { store.read("accounts") }
    }

    @Test
    fun rawProtectUnprotectRoundTripsIncludingEmptyInput() {
        assumeWindows()
        val blob = Dpapi.protect("bound".toByteArray())
        assertContentEquals("bound".toByteArray(), Dpapi.unprotect(blob))
        assertFalse("bound" in String(blob, Charsets.ISO_8859_1))
        assertContentEquals(ByteArray(0), Dpapi.unprotect(Dpapi.protect(ByteArray(0))))
    }

    @Test
    fun namesAreRestrictedToSafeCharacters() {
        assumeWindows()
        assertFailsWith<IllegalArgumentException> { store.write("../escape", ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { store.read("a b") }
    }

    @Test
    fun brokerOnRealDpapiSurvivesRestartAndLeavesOnlyCiphertext(): Unit = runBlocking {
        assumeWindows()
        val transport = FakeTransport()
        val first = AuthBroker(store, transport)
        first.login("me@example.com", "pw-123456".toCharArray())
        first.select("me@example.com")
        assertEquals(CredentialPersistence.Dpapi, first.status().persistence)
        assertTrue(first.isPersisted("me@example.com"))

        // 磁盘上任何文件里都找不到令牌、账号名与密码
        Files.list(directory).use { files ->
            files.forEach { file ->
                val text = String(Files.readAllBytes(file), Charsets.ISO_8859_1)
                assertFalse("access-signin-1" in text, file.toString())
                assertFalse("refresh-signin-1" in text, file.toString())
                assertFalse("me@example.com" in text, file.toString())
                assertFalse("pw-123456" in text, file.toString())
                assertTrue(file.fileName.toString().endsWith(".dpapi"), file.toString())
            }
        }

        val restarted = AuthBroker(DpapiCredentialStore(directory), transport)
        assertEquals("me@example.com", restarted.restore().current)
        assertEquals("access-signin-1", restarted.session("me@example.com")?.accessToken)
    }

    @Test
    fun noPersistenceStoreNeverWrites(): Unit = runBlocking {
        val broker = AuthBroker(NoPersistenceStore("这个系统没有 DPAPI"), FakeTransport())
        broker.login("me@example.com", "pw-123456".toCharArray())
        val status = broker.status()
        assertEquals(CredentialPersistence.MemoryOnly, status.persistence)
        assertEquals("这个系统没有 DPAPI", status.storeProblem)
        assertEquals(0, status.storedSessions)
    }
}
