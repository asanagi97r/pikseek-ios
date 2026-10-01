import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.jetbrains.compose.desktop.application.dsl.AotMode
import org.jetbrains.compose.desktop.application.tasks.AbstractCheckNativeDistributionRuntime
import org.jetbrains.compose.desktop.application.tasks.AbstractJvmToolOperationTask
import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask
import org.jetbrains.compose.desktop.application.tasks.AbstractSuggestModulesTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
}

// PikSeek 只出 Windows x64 的便携版。打包不能交叉构建：jpackage 只出宿主系统的程序，mpv 与 skiko 的原生库也按宿主取
val hostArch = if (System.getProperty("os.arch") == "aarch64") "arm64" else "x64"
val hostPlatform = "windows-$hostArch"
val hostMpvRuntime = "org.openani.mediamp:mediamp-mpv-runtime-$hostPlatform:${libs.versions.mediamp.get()}"
// 等同 compose.desktop.currentOs，但版本跟界面库走，而不是跟打包插件走（两者版本不同，见 libs.versions.toml）
val composeDesktopRuntime = "org.jetbrains.compose.desktop:desktop-jvm-$hostPlatform:${libs.versions.composeMultiplatform.get()}"

kotlin {
    jvm("desktop")

    // Windows 原生能力经 JDK 的 FFM（java.lang.foreign）直调，需要 JDK 22+。钉死 25
    jvmToolchain(25)

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(project(":ui"))
                implementation(libs.windows.touch)
                implementation(composeDesktopRuntime)
                implementation(libs.cmp.material.icons.extended)
                implementation(libs.mediamp.all)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.logging)
                implementation(libs.coil.compose)
                implementation(libs.coil.network.okhttp)
                // MP4 无损切片（流复制，不断点转码）：纯 JVM，无需捆绑 ffmpeg。
                implementation(libs.mp4parser.isobox)
                // PikoUploadSources.open 返回 RawSource，shared 只以 implementation 引入
                implementation(libs.kotlinx.io.core)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.junit)
                // 控件的鼠标悬停只能用真实的指针事件序列验证；版本跟界面库走，理由同 composeDesktopRuntime
                implementation("org.jetbrains.compose.ui:ui-test:${libs.versions.composeMultiplatform.get()}")
                // 测试进程没有打包好的资源目录，mpv 的原生库仍从类路径解压
                runtimeOnly(hostMpvRuntime)
            }
        }
    }
}

// Compose 的 run 默认用 Gradle 自身所在的 JDK 启动，不看 jvmToolchain。run 由 Compose 在 afterEvaluate 中注册，
// 其注册动作会覆盖先登记的 configureEach，故在其后追加
val desktopJavaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
afterEvaluate {
    tasks.named<JavaExec>("run") {
        executable(desktopJavaLauncher.get().executablePath.asFile)
    }
}

// jlink、jpackage 与 ProGuard 要带 jmods 的 JDK 25：Temurin 25 的发行包不带，ProGuard 读不到 java.lang.Object，所以指定 Azul 的。
// gw.cmd 把项目内 _runtime/pikseek/jdk 的那份 Zulu 指给 Gradle，不自动下载。
// 放进 afterEvaluate：插件在它自己的 afterEvaluate 里给这些任务设 javaHome，先登记的会被盖掉
val packagingJdkHome = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
    vendor = JvmVendorSpec.AZUL
}.map { it.metadata.installationPath.asFile.absolutePath }
afterEvaluate {
    tasks.withType<AbstractJvmToolOperationTask>().configureEach { javaHome.set(packagingJdkHome) }
    tasks.withType<AbstractProguardTask>().configureEach { javaHome.set(packagingJdkHome) }
    tasks.withType<AbstractSuggestModulesTask>().configureEach { javaHome.set(packagingJdkHome) }
    tasks.withType<AbstractCheckNativeDistributionRuntime>().configureEach { jdkHome.set(packagingJdkHome) }
}

// Compose 的桌面运行库与 MediaMP 带进了 ui-test，连带 junit、truth、guava 与 kotlinx-coroutines-test，
// 全部进了程序包。运行时一个都用不到，只从打包用的运行时类路径里排除，测试类路径不受影响
configurations.named("desktopRuntimeClasspath") {
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test-desktop")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test-junit4")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test-junit4-desktop")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test-jvm")
    exclude(group = "junit", module = "junit")
    exclude(group = "org.hamcrest")
    exclude(group = "com.google.truth")
}

// mpv 与 FFmpeg 的原生库解开放进程序的资源目录。mediamp 默认在每次启动后首次播放时把近 40MB 的库从 jar 解压到
// 新建的临时目录，Windows 上 DLL 被进程占用删不掉，每次运行都留一份。改为随程序就位，启动时经
// MpvMediampPlayer.prepareLibraries 指过去，不再解压。缩略图引擎用的也是这一份 libmpv
val hostMpvRuntimeJar = configurations.detachedConfiguration(dependencies.create(hostMpvRuntime)).apply {
    isTransitive = false
}
val bundledAppResources by tasks.registering(Sync::class) {
    from({ hostMpvRuntimeJar.map { zipTree(it) } }) {
        include("*.dll", "*.txt")
        into("mpv")
    }
    from("src/desktopMain/resources/app-icon.png")
    // 随程序带上许可与第三方声明
    from(rootProject.file("LICENSE.piko")) { into("licenses") }
    from(rootProject.file("THIRD_PARTY_DEPENDENCIES.md")) { into("licenses") }
    into(layout.buildDirectory.dir("appResources/common"))
}

val desktopPackageName = "PikSeek"
val desktopPackageVersion = providers.gradleProperty("pikseekVersion").map { it.trim() }.filter { it.isNotEmpty() }.getOrElse("1.0.0")

compose.desktop {
    application {
        mainClass = "dev.piko.desktop.MainKt"
        // 写进 exe 的启动配置：FFM 的受限方法要显式放开，否则告警，未来的 JDK 会直接拦截
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        // AOT 缓存里不存机器码。JDK 25 会把训练时生成的调用适配代码与桩代码一并存进 app.aot，换一台机器也不核对 CPU 特性：
        // 打包机支持的指令集比运行的机器多时，装过去随机报非法指令。训练与运行都读这里的参数，两处一起关掉；
        // 类的加载与链接照常缓存，启动加速的大头仍在
        jvmArgs += listOf("-XX:+UnlockDiagnosticVMOptions", "-XX:-AOTAdapterCaching", "-XX:-AOTStubCaching")
        // JVM 默认在用户的临时目录下建 hsperfdata_<用户名>（给 jps、jstat 看的性能数据）。便携版不往程序目录之外写，关掉
        jvmArgs += "-XX:-UsePerfData"
        buildTypes.release.proguard {
            isEnabled = true
            configurationFiles.from(project.file("proguard-rules.pro"))
            // 只裁剪：优化轮次在这套依赖上耗时十几分钟，换来的体积差别很小
            optimize = false
            obfuscate = false
            joinOutputJars = true
        }
        // JDK 25 的 AOT 缓存（JEP 483/514）：打包时跑一遍训练，把启动路径上的类预先加载、链接好存进 app.aot，
        // 启动时直接映射。训练运行由 Main 在开窗后自行退出，见 AOT_TRAINING_PROPERTY
        buildTypes.release.aot {
            mode = AotMode.AotPrebuild
        }
        nativeDistributions {
            appResourcesRootDir = layout.buildDirectory.dir("appResources")
            // Compose 默认的 jlink 模块集之外补两个：jdk.unsupported（sun.misc.Unsafe）；
            // java.net.http 是认证模块用的 JDK 自带 HTTP 客户端
            modules("jdk.unsupported", "java.net.http")
            packageName = desktopPackageName
            packageVersion = desktopPackageVersion
            vendor = "PikSeek"
            // jpackage 把它写进 exe 的 FileDescription，任务管理器拿它当程序名显示，所以只写名字
            description = desktopPackageName
            copyright = "PikSeek; based on Piko, Copyright (c) 2026 NihilDigit (MIT)"
            windows {
                iconFile.set(project.file("package/windows/icon.ico"))
            }
        }
    }
}

// FFM（Linker、SymbolLookup）属于受限方法，显式开 native-access
tasks.withType<Test> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // 播放冒烟读仓库里的样片，路径由这里给出，不依赖测试进程的工作目录
    systemProperty("piko.testdata", rootProject.file("testdata/media").absolutePath)
    // 时间轴预览的测试真去解码：用随程序带的那份 libmpv。界面截图留在构建目录里
    dependsOn(bundledAppResources)
    systemProperty("pikseek.mpv.dir", layout.buildDirectory.dir("appResources/common/mpv").get().asFile.absolutePath)
    systemProperty("pikseek.shots.dir", layout.buildDirectory.dir("shots").get().asFile.absolutePath)
    // 测试进程的数据目录也收在构建目录里，不落到用户目录
    systemProperty("pikseek.data.dir", layout.buildDirectory.dir("test-data").get().asFile.absolutePath)
}
tasks.withType<JavaExec> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
// AOT 缓存按 jar 的修改时间校验，差一毫秒也整份作废。zip 只存到偶数秒，解压后的 jar 时间被取整，缓存随之失效。
// 训练之前先把 jar 的时间取整到偶数秒，打包前后就是同一个值
tasks.matching { it.name == "createReleaseAotArchive" }.configureEach {
    doFirst {
        layout.buildDirectory.dir("compose/binaries/main-release/app").get().asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "jar" }
            .forEach { it.setLastModified(it.lastModified() / 2000 * 2000) }
    }
}

// 启动器放在系统代码页写不出的文件夹里（英文系统上的中文文件夹名）会找不到自带的运行时，退出码 2、没有窗口：
// JDK 的启动代码用系统代码页传自己的路径。给 exe 的清单加 activeCodePage=UTF-8 后这个进程的代码页就是 UTF-8。
// 放在训练之前做，训练与实际运行用的是同一个启动器
val launcherManifestScript = project.file("package/windows/utf8-manifest.ps1")
val packagedLauncher = layout.buildDirectory.file("compose/binaries/main-release/app/$desktopPackageName/$desktopPackageName.exe")
tasks.matching { it.name == "createReleaseAotArchive" }.configureEach {
    inputs.file(launcherManifestScript)
    doFirst {
        val process = ProcessBuilder(
            "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", launcherManifestScript.absolutePath,
            "-Exe", packagedLauncher.get().asFile.absolutePath,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val code = process.waitFor()
        logger.lifecycle(output)
        if (code != 0) throw GradleException("utf8-manifest.ps1 失败（退出码 $code）")
    }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(bundledAppResources) }

// compose 的 run 任务在 afterEvaluate 里重写 jvmArgs，会盖掉上面的配置，这里后注册、后执行，把 flag 补回去
project.afterEvaluate {
    tasks.named<JavaExec>("run") {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

// 依赖审计用：把程序包里实际带着的第三方库（解析后的版本）列成文件，docs/make_dependency_list.py 据此生成 THIRD_PARTY_DEPENDENCIES.md
tasks.register("listRuntimeDependencies") {
    val components = configurations.named("desktopRuntimeClasspath").map { it.incoming.resolutionResult.allComponents }
    val output = layout.buildDirectory.file("runtime-dependencies.txt")
    outputs.file(output)
    doLast {
        val lines = components.get()
            .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .map { "${it.group}:${it.module}:${it.version}" }
            .distinct()
            .sorted()
        output.get().asFile.writeText(lines.joinToString("\n") + "\n")
    }
}
