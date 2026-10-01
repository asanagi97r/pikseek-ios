package dev.pikseek.security

import java.nio.file.Files
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostClassifierTest {
    @Test
    fun officialHostsAreSortedByRole() {
        assertEquals(HostCategory.PikPakAuth, HostClassifier.classify("user.mypikpak.com"))
        assertEquals(HostCategory.PikPakAuth, HostClassifier.classify("USER.MyPikPak.net."))
        assertEquals(HostCategory.PikPakApi, HostClassifier.classify("api-drive.pikpak.me"))
        assertEquals(HostCategory.PikPakCdn, HostClassifier.classify("dl-a10b-0621.mypikpak.com"))
        assertEquals(HostCategory.PikPakCdn, HostClassifier.classify("sg-thumbnail-drive.mypikpak.com"))
        assertEquals(HostCategory.PikPakOther, HostClassifier.classify("mypikpak.com"))
        assertEquals(HostCategory.Upload, HostClassifier.classify("vip-lixian-07.oss-cn-beijing.aliyuncs.com"))
    }

    @Test
    fun lookalikesAreNotOfficial() {
        // 名字里带着 pikpak 不算，要整段后缀对上
        assertEquals(HostCategory.Other, HostClassifier.classify("mypikpak.com.evil.example"))
        assertEquals(HostCategory.Other, HostClassifier.classify("user-mypikpak.com"))
        assertEquals(HostCategory.Other, HostClassifier.classify("notmypikpak.com"))
        assertEquals(HostCategory.Other, HostClassifier.classify("api.github.com"))
        assertNull(HostClassifier.officialRootOf("xmypikpak.net"))
    }

    @Test
    fun loopbackIsLiteralOnly() {
        assertEquals(HostCategory.Loopback, HostClassifier.classify("127.0.0.1"))
        assertEquals(HostCategory.Loopback, HostClassifier.classify("localhost"))
        assertEquals(HostCategory.Loopback, HostClassifier.classify("[::1]"))
        assertEquals(HostCategory.Other, HostClassifier.classify("127.example.com"))
    }
}

class SecretRedactorTest {
    private val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhYmMxMjMifQ.c2lnbmF0dXJlLXNpZ25hdHVyZQ"

    @Test
    fun tokensAndPasswordsAreRemoved() {
        val text = """
            Authorization: Bearer $jwt
            {"access_token":"$jwt","refresh_token":"os.AbCdEf123456","password":"hunter2-secret","expires_in":7200}
            password=hunter2-secret&username=a
            GET https://dl-a10b-0621.mypikpak.com/download/?fid=abc&sign=DEADBEEF&expire=1700000000
            contact someone@example.com
        """.trimIndent()
        val redacted = SecretRedactor.redact(text)
        assertFalse(jwt in redacted)
        assertFalse("hunter2-secret" in redacted)
        assertFalse("os.AbCdEf123456" in redacted)
        assertFalse("DEADBEEF" in redacted)
        assertFalse("someone@example.com" in redacted)
        // 不是机密的留着：主机名、字段名、数值
        assertTrue("dl-a10b-0621.mypikpak.com" in redacted)
        assertTrue("\"expires_in\":7200" in redacted)
        assertTrue("s***@example.com" in redacted)
        assertTrue(SecretRedactor.containsSecret(text))
    }

    @Test
    fun plainStatusLinesSurvive() {
        val lines = "session stored with DPAPI: Yes\nplaintext credential files: 0\ntelemetry: Disabled\nhosts: 3"
        assertEquals(lines, SecretRedactor.redact(lines))
        assertFalse(SecretRedactor.containsSecret(lines))
    }

    @Test
    fun hostOfDropsEverythingElse() {
        assertEquals("dl-a10b.mypikpak.com", SecretRedactor.hostOf("https://dl-a10b.mypikpak.com/download/?sign=abc"))
        assertEquals("127.0.0.1", SecretRedactor.hostOf("http://127.0.0.1:54321/stream/x.mkv"))
        assertEquals("example.com", SecretRedactor.hostOf("https://user:pass@Example.com:8443/a?b#c"))
        assertEquals("[::1]", SecretRedactor.hostOf("http://[::1]:8080/a"))
        assertNull(SecretRedactor.hostOf("not a url"))
    }
}

class NetworkAuditTest {
    @Test
    fun recordsHostsOnlyAndFlagsUnknown() {
        NetworkAudit.clear()
        NetworkAudit.record("user.mypikpak.com", "Auth：密码登录", nowMillis = 1_000)
        NetworkAudit.record("user.mypikpak.com", nowMillis = 5_000)
        NetworkAudit.recordUrl("https://dl-a10b-0621.mypikpak.com/download/?fid=FILEID&sign=SIGNATURE")
        NetworkAudit.record("tracker.example.org", nowMillis = 2_000)
        NetworkAudit.record(null)
        NetworkAudit.record("  ")

        val entries = NetworkAudit.snapshot()
        assertEquals(3, entries.size)
        // 非 PikPak 的排最前
        assertEquals("tracker.example.org", entries.first().host)
        assertEquals(listOf("tracker.example.org"), NetworkAudit.unknownHosts())
        val auth = entries.single { it.host == "user.mypikpak.com" }
        assertEquals(2, auth.count)
        assertEquals(1_000, auth.firstSeenMillis)
        assertEquals(5_000, auth.lastSeenMillis)
        // 调用方给过的用途留着，后面没给用途的一次不把它冲掉
        assertEquals("Auth：密码登录", auth.purpose)

        val text = NetworkAudit.exportText(nowMillis = 10_000, zone = TimeZone.UTC)
        assertTrue("dl-a10b-0621.mypikpak.com" in text)
        assertFalse("FILEID" in text)
        assertFalse("SIGNATURE" in text)
        assertTrue("non-PikPak hosts: 1" in text)
        NetworkAudit.clear()
    }
}

class PlaintextCredentialScanTest {
    @Test
    fun findsLeakedCredentialsAndIgnoresCiphertext() {
        val root = Files.createTempDirectory("pikseek-scan")
        try {
            Files.createDirectories(root.resolve("auth"))
            // DPAPI 密文的样子：二进制，没有可认的键名
            Files.write(root.resolve("auth/session_abc.dpapi"), ByteArray(600) { (it * 37 % 251).toByte() })
            Files.writeString(root.resolve("settings.properties"), "theme=dark\nproxy.mode=SYSTEM\n")
            assertEquals(0, PlaintextCredentialScan.scan(root).count)

            Files.writeString(root.resolve("session.json"), """{"access_token":"abcdef123456","refresh_token":"zzz999888777"}""")
            Files.writeString(root.resolve("auth/note.txt"), "password=correct-horse-battery")
            val result = PlaintextCredentialScan.scan(root)
            assertEquals(2, result.count)
            assertTrue(result.suspicious.any { it.endsWith("session.json") })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

class SecurityReportTest {
    @Test
    fun reportCarriesFactsAndNoSecrets() {
        NetworkAudit.clear()
        NetworkAudit.record("user.mypikpak.com", "Auth：刷新会话")
        val facts = SecurityReport.Facts(
            appVersion = "0.1.0",
            dataRoot = "X:/PikSeek/data",
            portable = true,
            dataLocation = "程序旁，便携",
            credentialScan = PlaintextCredentialScan.Result(12, emptyList()),
            telemetryLibraries = TelemetryCheck.present(),
            unknownHosts = emptyList(),
        )
        val section = SecurityReport.Section(
            "Authentication",
            listOf("session stored with DPAPI" to "Yes", "password stored" to "Never"),
        )
        val text = SecurityReport.build(facts, listOf(section), nowMillis = 0, zone = TimeZone.UTC)
        assertTrue("session stored with DPAPI: Yes" in text)
        assertTrue("plaintext credential files: 0" in text)
        assertTrue("telemetry: Disabled" in text)
        assertTrue("telemetry libraries on classpath: none" in text)
        assertTrue("user.mypikpak.com" in text)
        assertFalse(SecretRedactor.containsSecret(text))
        NetworkAudit.clear()
    }
}
