package dev.pikseek.desktop

import dev.pikseek.auth.AuthBroker
import dev.pikseek.auth.AuthNetworkPolicy
import dev.pikseek.auth.CredentialPersistence
import dev.pikseek.auth.DpapiCredentialStore
import dev.pikseek.auth.PikPakAuthClient
import dev.pikseek.platform.AppPaths
import dev.pikseek.security.NetworkAudit
import dev.pikseek.security.PlaintextCredentialScan
import dev.pikseek.security.SecurityReport
import dev.pikseek.security.TelemetryCheck
import dev.pikseek.thumbnail.ByteRangeReader
import dev.pikseek.thumbnail.EpisodeSound
import dev.pikseek.thumbnail.MediaFingerprint
import dev.pikseek.thumbnail.MediaMarks
import dev.pikseek.thumbnail.MpvAudioDecoder
import dev.pikseek.thumbnail.SceneAnalysis
import dev.pikseek.thumbnail.ThumbnailFrame
import dev.pikseek.thumbnail.MpvFrameGrabber
import dev.pikseek.thumbnail.PreviewDensity
import dev.pikseek.thumbnail.SeekingSource
import dev.pikseek.thumbnail.ThumbnailCache
import dev.pikseek.thumbnail.ThumbnailEngine
import dev.pikseek.thumbnail.ThumbnailState
import dev.pikseek.ui.player.WebpSpriteCodec
import dev.pikseek.ui.settings.authSection
import java.io.File
import dev.pikseek.auth.AuthUrl
import dev.pikseek.thumbnail.directoryOf
import dev.pikseek.security.scan
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 打好的程序包里跑的自检：这些东西只有在真的启动器、真的裁剪过的运行时里跑一遍才算数
 * （ProGuard 裁没裁掉要用的类、jlink 的模块带没带全、FFM 调不调得通、自带的 libmpv 加不加载得上）。
 *
 * 带 `-Dpikseek.selftest=<名字>` 启动，见 SelfTest.kt。都不开主窗口、不联网、不登录。
 */
internal object PikSeekSelfTest {
    /**
     * 认证与存储：DPAPI 在这个包里能加解密；认证模块存下的只有密文；数据目录在程序旁；
     * 白名单拦得住别的主机；类路径上没有遥测库；JDK 的 HTTP 客户端在（jlink 带了 java.net.http）。
     * 认证服务换成进程内的假应答，一个字节也不出网。
     */
    fun security(report: (String) -> Unit): Boolean = runBlocking {
        val directory = AppPaths.dir("selftest-auth")
        try {
            val store = DpapiCredentialStore(directory)
            report("data.root=${AppPaths.dataRoot}")
            report("data.portable=${AppPaths.isPortable}")
            report("tmpdir=${System.getProperty("java.io.tmpdir")}")
            report("dpapi.problem=${store.problem()}")

            val fake = PikPakAuthClient.Transport { uri, _, _ ->
                PikPakAuthClient.Reply(
                    200,
                    if (uri.path.endsWith("captcha/init")) """{"captcha_token":"c"}"""
                    else """{"access_token":"selftest-access-token","refresh_token":"selftest-refresh-token","sub":"u","expires_in":7200}""",
                )
            }
            val broker = AuthBroker(store, fake)
            val session = broker.login("selftest@example.com", "selftest-password".toCharArray())
            val status = broker.status()
            report("auth.persistence=${status.persistence}")
            val files = Files.list(directory).use { stream -> stream.toList() }
            val leaked = files.any { file ->
                val text = String(Files.readAllBytes(file), Charsets.ISO_8859_1)
                "selftest-access-token" in text || "selftest-refresh-token" in text || "selftest-password" in text || "selftest@example.com" in text
            }
            report("auth.files=${files.map { it.fileName }} leaked=$leaked")
            val restored = AuthBroker(DpapiCredentialStore(directory), fake).also { it.restore() }.session("selftest@example.com")
            report("auth.restored=${restored?.accessToken == session.accessToken}")

            val blocked = runCatching { AuthNetworkPolicy.check(AuthUrl.parse("https://example.com/v1/auth/signin")) }.isFailure
            val allowed = runCatching { AuthNetworkPolicy.check(AuthUrl.parse("https://user.mypikpak.com/v1/auth/signin")) }.isSuccess
            report("policy.blocksForeign=$blocked policy.allowsOfficial=$allowed")

            // 只建客户端，不发请求：确认 java.net.http 这个模块随运行时带上了
            val httpClient = runCatching { java.net.http.HttpClient.newBuilder().build().version().name }.getOrElse { "MISSING: $it" }
            report("jdk.httpclient=$httpClient")

            val telemetry = TelemetryCheck.present()
            report("telemetry.libraries=$telemetry")
            val scan = PlaintextCredentialScan.scan(AppPaths.dataRoot)
            report("plaintext.credentialFiles=${scan.count} scanned=${scan.scannedFiles} ${scan.suspicious}")

            val text = SecurityReport.build(SecurityReport.facts("selftest"), listOf(authSection(status)))
            report("report.lines=${text.lines().size} mentionsDpapi=${"session stored with DPAPI: Yes" in text}")
            report("audit.hosts=${NetworkAudit.snapshot().map { it.host }}")

            store.problem() == null &&
                status.persistence == CredentialPersistence.Dpapi &&
                !leaked && restored?.accessToken == session.accessToken &&
                blocked && allowed &&
                !httpClient.startsWith("MISSING") &&
                telemetry.isEmpty() && scan.count == 0 &&
                "session stored with DPAPI: Yes" in text &&
                // 认证走的是假应答，审计里只该有它记下的官方认证主机
                NetworkAudit.unknownHosts().isEmpty()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /**
     * 时间轴预览：用包里的 libmpv 给 [video]（本机文件）做一整套缩略图，存成 WebP 雪碧图，再从磁盘读回来。
     */
    fun preview(video: File, report: (String) -> Unit): Boolean = runBlocking {
        if (!video.isFile) {
            report("no such file: $video")
            return@runBlocking false
        }
        val mpvDirectory = System.getProperty("compose.application.resources.dir")?.let { File(it, "mpv").toPath() }
        if (mpvDirectory == null || !Files.isRegularFile(mpvDirectory.resolve("libmpv-2.dll"))) {
            report("bundled libmpv not found under $mpvDirectory")
            return@runBlocking false
        }
        val cacheRoot = AppPaths.dir("selftest-thumbnails")
        try {
            val idle = MutableStateFlow(false)
            val one = MutableStateFlow(1)
            val started = System.nanoTime()
            val (duration, firstFrameMs) = MpvFrameGrabber(mpvDirectory).use { probe ->
                if (!probe.open(video.absolutePath)) {
                    report("cannot open video")
                    return@runBlocking false
                }
                probe.durationMs to (System.nanoTime() - started) / 1_000_000
            }
            report("video.durationMs=$duration decoderOpenMs=$firstFrameMs")
            val fingerprint = MediaFingerprint("selftest", "", video.length(), duration / 1000 * 1000)
            val engine = ThumbnailEngine(ThumbnailCache(cacheRoot, WebpSpriteCodec))
            val grabber = MpvFrameGrabber(mpvDirectory)
            val generateStarted = System.nanoTime()
            val session = engine.open(fingerprint, PreviewDensity.Medium, { listOf(SeekingSource(video.absolutePath, grabber, "本机文件")) }, { 0L }, idle, one)
            val done = withTimeout(120_000) { session.progress.first { it.state == ThumbnailState.Complete || it.state == ThumbnailState.Unavailable } }
            val generateMs = (System.nanoTime() - generateStarted) / 1_000_000
            session.close()
            session.join()
            // close 在后台收尾存盘
            kotlinx.coroutines.delay(800)
            grabber.close()
            report("generated state=${done.state} frames=${done.fullDone}/${done.fullTotal} coarse=${done.coarseDone}/${done.coarseTotal} in ${generateMs} ms")
            val middle = session.frameAt(duration / 2)
            report("frame@middle=${middle?.let { "${it.width}x${it.height} t=${it.timeMs}" }}")
            val files = Files.list(ThumbnailCache(cacheRoot, WebpSpriteCodec).directoryOf(fingerprint)).use { stream -> stream.map { "${it.fileName}:${Files.size(it)}" }.toList() }
            report("cache.files=$files")

            val again = ThumbnailEngine(ThumbnailCache(cacheRoot, WebpSpriteCodec))
                .open(fingerprint, PreviewDensity.Medium, { error("缓存齐全时不该要来源") }, { 0L }, idle, one)
            val reloaded = withTimeout(30_000) { again.progress.first { it.state == ThumbnailState.Complete || it.state == ThumbnailState.Unavailable } }
            again.close()
            report("reloaded fromCache=${reloaded.fromCache}/${reloaded.fullTotal} source=${reloaded.source}")

            done.state == ThumbnailState.Complete && done.fullDone == done.fullTotal && middle != null &&
                files.any { it.startsWith("sheet-000.webp") } && reloaded.fromCache == reloaded.fullTotal
        } finally {
            cacheRoot.toFile().deleteRecursively()
        }
    }

    /**
     * 进度条分段：用包里的 libmpv 从 [ts]（一段 TS，40～42 秒有一声「嘀」，其余静音）按字节取 30～50 秒的声音，
     * 看嘀声落在不在 40～42 秒；再拿合成的两种画面跑一次场景分点，看切点对不对；分段文件读写一遍。
     */
    fun marks(ts: File, report: (String) -> Unit): Boolean = runBlocking {
        if (!ts.isFile) {
            report("no such file: $ts")
            return@runBlocking false
        }
        val mpvDirectory = System.getProperty("compose.application.resources.dir")?.let { File(it, "mpv").toPath() }
        if (mpvDirectory == null || !Files.isRegularFile(mpvDirectory.resolve("libmpv-2.dll"))) {
            report("bundled libmpv not found under $mpvDirectory")
            return@runBlocking false
        }
        val temp = AppPaths.dir("selftest-marks")
        try {
            val bytes = ts.readBytes()
            val reader = object : ByteRangeReader {
                override val size: Long get() = bytes.size.toLong()

                override suspend fun read(offset: Long, length: Int): ByteArray =
                    bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))
            }
            val sound = EpisodeSound(MpvAudioDecoder(mpvDirectory, temp.resolve("audio")), temp.resolve("ts").toString())
            val started = System.nanoTime()
            val print = sound.fromTs(reader, 60_000, 30_000, 20_000)
            val loud = print?.let { p -> p.loud.indices.filter { p.loud[it] }.map(p::timeOf) }.orEmpty()
            val beep = loud.firstOrNull() to loud.lastOrNull()
            report("audio.sliceStartMs=${print?.startMs} beep=${beep.first}..${beep.second} read=${sound.networkBytes}/${bytes.size} in ${(System.nanoTime() - started) / 1_000_000} ms")
            val audioOk = print != null && beep.first != null && kotlin.math.abs(beep.first!! - 40_000) <= 500 && kotlin.math.abs(beep.second!! - 42_000) <= 500

            // 前 40 分钟蓝、后 20 分钟红（各带点随机块），切点该在 40 分钟之后的第一张图上
            fun frame(time: Long): ThumbnailFrame {
                val random = java.util.Random(time)
                val base = if (time < 2_400_000) 0xFF2A6F97.toInt() else 0xFFB03030.toInt()
                val pixels = IntArray(240 * 136) { base }
                repeat(4) {
                    val x0 = random.nextInt(140)
                    val y0 = random.nextInt(60)
                    for (y in y0 until y0 + 60) for (x in x0 until x0 + 90) pixels[y * 240 + x] = 0xFFE0C9A6.toInt()
                }
                return ThumbnailFrame(time, 240, 136, pixels)
            }
            val frames = (0 until 90).map { frame((it + 0.5).times(40_000).toLong()) }
            val cuts = SceneAnalysis.analyze(frames, 3_600_000, fetch = { time -> frame(time / 5_000 * 5_000) })
            report("scenes.cuts=$cuts")
            val scenesOk = cuts.size == 1 && cuts.single() in 2_400_000..2_406_000

            val marks = MediaMarks(gcid = "AB".repeat(20), durationMs = 3_600_000, scenes = cuts, scenesDone = true).withIntroEnd(90_000)
            val roundTrip = MediaMarks.decode(marks.encode()) == marks
            report("marks.roundTrip=$roundTrip file=${marks.fileName}")
            audioOk && scenesOk && roundTrip
        } finally {
            temp.toFile().deleteRecursively()
        }
    }
}
