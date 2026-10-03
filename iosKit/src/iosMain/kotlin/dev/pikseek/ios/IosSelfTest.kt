package dev.pikseek.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piko.shared.data.ChildFile
import dev.piko.shared.media.player.PlaybackBackendEvent
import dev.piko.shared.naming.parseMediaName
import dev.piko.shared.state.describeDriveFolder
import dev.piko.shared.media.player.PlaybackTarget
import dev.pikseek.auth.AuthBroker
import dev.pikseek.auth.AuthNetworkPolicy
import dev.pikseek.auth.AuthUrl
import dev.pikseek.auth.KeychainCredentialStore
import dev.pikseek.auth.PikPakAuthClient
import dev.pikseek.auth.defaultAuthTransport
import dev.pikseek.platform.IosPaths
import dev.pikseek.security.collectSecurityFacts
import dev.pikseek.thumbnail.IosThumbnailPlatform
import dev.pikseek.thumbnail.MediaFingerprint
import dev.pikseek.thumbnail.PreviewDensity
import dev.pikseek.thumbnail.PreviewPackName
import dev.pikseek.thumbnail.SeekingSource
import dev.pikseek.thumbnail.ThumbnailCache
import dev.pikseek.thumbnail.ThumbnailEngine
import dev.pikseek.ui.player.WebpSpriteCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 打好的 iOS 程序的自检：不联网、不登录，把 Swift 写的那几层连同架在上面的 Kotlin 逻辑真跑一遍。
 * 打包流程在模拟器里以自检方式启动程序、截图、取回 Documents/selftest.txt。与桌面版的 selftest.ps1 是一回事。
 *
 * 三项：认证存储（钥匙串加解密、重启后恢复、白名单拦截）、播放（libmpv 出画面、位置在走，含系统播放器播不了的格式）、
 * 时间轴预览（解码取帧、生成、存盘、从缓存重开）。
 */
@Composable
internal fun IosSelfTestScreen(native: NativeServices, sample: String, otherSample: String, resultPath: String) {
    val lines = remember { mutableStateListOf<String>() }
    val player = rememberIosPlayer(native)

    LaunchedEffect(Unit) {
        var failed = 0
        // 「时间轴预览」做好的那个视频，「预览缓存」一项拿它打包
        var timelineFingerprint: MediaFingerprint? = null

        suspend fun step(name: String, block: suspend () -> String) {
            val outcome = runCatching { block() }
            val line = outcome.fold({ "PASS $name: $it" }, { "FAIL $name: ${it::class.simpleName}: ${it.message}" })
            if (outcome.isFailure) failed++
            lines += line
        }

        step("keychain store") {
            val store = KeychainCredentialStore(KeychainAdapter(native.keychain()))
            store.problem()?.let { error(it) }
            val fake = PikPakAuthClient.Transport { uri, _, _ ->
                val body = if (uri.path.endsWith("captcha/init")) {
                    """{"captcha_token":"c"}"""
                } else {
                    """{"access_token":"selftest-access","refresh_token":"selftest-refresh","expires_in":7200,"sub":"u"}"""
                }
                PikPakAuthClient.Reply(200, body)
            }
            val account = "selftest@example.invalid"
            val broker = AuthBroker(store, fake)
            broker.login(account, "not-a-real-password".toCharArray())
            val persisted = broker.isPersisted(account)
            // 另起一个，等于重启程序：会话该从钥匙串里读回来，不再发请求
            val restarted = AuthBroker(store, PikPakAuthClient.Transport { _, _, _ -> error("恢复会话不该发请求") })
            val known = restarted.restore().accounts.size
            val restored = restarted.session(account) != null
            restarted.logout(account)
            val left = store.names().size
            check(persisted && restored && known == 1) { "persisted=$persisted restored=$restored known=$known" }
            "persisted=$persisted restored=$restored itemsAfterLogout=$left"
        }

        step("auth allowlist") {
            val blocked = runCatching { AuthNetworkPolicy.check(AuthUrl.parse("https://example.com/v1/auth/signin")) }.isFailure
            val allowed = runCatching { AuthNetworkPolicy.check(AuthUrl.parse("https://user.mypikpak.com/v1/auth/signin")) }.isSuccess
            // 真的传输层：不在白名单里的地址在发出之前就被拦下
            val transportRefuses = withContext(Dispatchers.IO) {
                runCatching { defaultAuthTransport().post(AuthUrl.parse("https://example.com/v1/auth/signin"), emptyMap(), ByteArray(0)) }.isFailure
            }
            check(blocked && allowed && transportRefuses) { "blocked=$blocked allowed=$allowed transportRefuses=$transportRefuses" }
            "blocksForeign=$blocked allowsOfficial=$allowed transportRefuses=$transportRefuses"
        }

        // 网盘列表的文件名解析：正则多，iOS 的正则引擎与 Java 的不完全一样，有一个不认整个文件就初始化失败。
        // 1.0.0 第 10 版就是这样一登录就崩（频道推广的正则用了 \p{script=Han}）
        step("file names") {
            val samples = listOf(
                "www.98T.la@ABC-123-C", "[thz.la]SSIS-001 片名 [FHD]", "TG频道@channel丨FC2-PPV-1234567",
                "一本道 010124_001", "Sword.Art.Online - 01 [1080p].mkv", "BlackedRaw.19.05.17.Name.XXX.1080p.mp4",
            )
            val parsed = samples.map { parseMediaName(it).title }
            val folders = samples.map { describeDriveFolder(it, listOf(ChildFile("$it.mp4"))).title }
            "parsed=${parsed.count { it != null }}/${samples.size} folders=${folders.count { it != null }}/${samples.size}"
        }

        step("security facts") {
            val facts = withContext(Dispatchers.IO) { collectSecurityFacts("selftest") }
            check(facts.telemetryLibraries.isEmpty() && facts.credentialScan.count == 0) {
                "telemetry=${facts.telemetryLibraries} plaintext=${facts.credentialScan.suspicious}"
            }
            "telemetry=[] plaintextFiles=0 scanned=${facts.credentialScan.scannedFiles}"
        }

        for ((label, path) in listOf("play mp4" to sample, "play wmv" to otherSample)) {
            step(label) {
                val backend = player?.backend ?: error("建不出播放器")
                var ready = false
                val watcher = launch { backend.events.collect { if (it == PlaybackBackendEvent.Ready) ready = true } }
                try {
                    withTimeoutOrNull(20_000) { backend.open(PlaybackTarget.LocalFile(path), 0) } ?: error("20 秒内没加载好")
                    withTimeoutOrNull(20_000) { while (!ready) delay(50) } ?: error("没等到第一帧")
                    withTimeoutOrNull(10_000) { while (backend.positionMillis < 800) delay(50) } ?: error("位置不走，停在 ${backend.positionMillis}")
                    val vo = player.bridge.getProperty("current-vo")
                    val hwdec = player.bridge.getProperty("hwdec-current")
                    check(vo == "gpu-next") { "画面输出是 $vo" }
                    "position=${backend.positionMillis} duration=${backend.durationMillis} aspect=${backend.videoAspect} vo=$vo hwdec=$hwdec audioTracks=${backend.audioTracks.size}"
                } finally {
                    watcher.cancel()
                }
            }
            // 留一会儿给截图
            delay(1_500)
        }

        step("timeline preview") {
            val root = "${IosPaths.temp}/selftest-thumbnails"
            val cache = ThumbnailCache(root, WebpSpriteCodec)
            cache.clear()
            val grabber = withContext(Dispatchers.IO) { IosThumbnailPlatform.newGrabber() } ?: error("建不出取帧解码器")
            val duration = withContext(Dispatchers.IO) {
                check(grabber.open(sample)) { "取帧解码器打不开样片" }
                grabber.durationMs / 1000 * 1000
            }
            val fingerprint = MediaFingerprint("selftest", "", IosFiles.length(sample), duration)
            timelineFingerprint = fingerprint
            val idle = MutableStateFlow(false)
            val one = MutableStateFlow(1)
            val session = ThumbnailEngine(cache).open(fingerprint, PreviewDensity.Medium, { listOf(SeekingSource(sample, grabber, "本机文件")) }, { 0L }, idle, one)
            withTimeoutOrNull(60_000) { session.join() } ?: error("60 秒内没做完")
            val progress = session.progress.value
            val middle = session.frameAt(duration / 2) ?: error("中间没有图")
            session.close()
            delay(500)
            withContext(Dispatchers.IO) { grabber.close() }
            val again = ThumbnailEngine(ThumbnailCache(root, WebpSpriteCodec))
                .open(fingerprint, PreviewDensity.Medium, { error("缓存齐全时不该要来源") }, { 0L }, idle, one)
            withTimeoutOrNull(20_000) { again.join() } ?: error("从缓存重开没做完")
            val reloaded = again.progress.value
            again.close()
            check(progress.fullDone == progress.fullTotal && reloaded.fromCache == progress.fullTotal) {
                "generated=${progress.fullDone}/${progress.fullTotal} fromCache=${reloaded.fromCache}"
            }
            "frames=${progress.fullDone}/${progress.fullTotal} frame=${middle.width}x${middle.height} cacheBytes=${progress.cacheBytes} reloadedFromCache=${reloaded.fromCache} source=${reloaded.source}"
        }

        // 预览缓存（存到网盘上的那种）：上一项做好的预览打成包，解进另一台「设备」的缓存，按设置里别的档次打开也直接用它
        step("preview pack") {
            val original = timelineFingerprint ?: error("上一项没做成")
            val stored = ThumbnailCache("${IosPaths.temp}/selftest-thumbnails", WebpSpriteCodec)
            val pack = stored.exportPack(original) ?: error("打不出包")
            val other = ThumbnailCache("${IosPaths.temp}/selftest-thumbnails-other", WebpSpriteCodec)
            other.clear()
            val elsewhere = MediaFingerprint("another-device", "ABCDEF0123456789ABCDEF0123456789ABCDEF01", 1, original.durationMs)
            val imported = other.importPack(elsewhere, pack) ?: error("解不开包")
            val idle = MutableStateFlow(false)
            val one = MutableStateFlow(1)
            val session = ThumbnailEngine(other).open(elsewhere, PreviewDensity.High, { error("包里齐全时不该要来源") }, { 0L }, idle, one)
            withTimeoutOrNull(20_000) { session.join() } ?: error("从包里打开没做完")
            val fromPack = session.progress.value.fromCache
            session.close()
            val name = PreviewPackName(elsewhere.contentHash, PreviewDensity.Medium, imported.frameCount, imported.slotCount)
            check(fromPack == imported.slotCount && PreviewPackName.parse(name.fileName) == name) { "fromPack=$fromPack slots=${imported.slotCount}" }
            "packBytes=${pack.size} frames=${imported.frameCount}/${imported.slotCount} openedFromPack=$fromPack name=${name.fileName.takeLast(24)}"
        }

        lines += if (failed == 0) "ALL PASS" else "FAILED $failed"
        runCatching { IosFiles.writeText(resultPath, lines.joinToString("\n") + "\n") }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (player != null) IosPlayerSurface(player, Modifier.fillMaxWidth().height(240.dp))
            Column(Modifier.padding(12.dp)) {
                Text("PikSeek self-test", color = Color.White, fontSize = 14.sp)
                lines.forEach { line ->
                    Text(
                        line,
                        color = if (line.startsWith("FAIL")) Color(0xFFFF6B6B) else Color(0xFF9BE79B),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
