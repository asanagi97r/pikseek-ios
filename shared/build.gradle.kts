plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
}

kotlin {
    jvm("desktop")
    jvmToolchain(25)
    iosArm64()
    iosSimulatorArm64()

    // Piko 里 Android 与桌面共用的 JVM 代码放在 jvmShared；这里只剩桌面一个目标，目录原样留着
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withCompilations {
                    it.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            // 下载任务表以 JSON 存入偏好。SDK 以 implementation 声明它，不会传递过来
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            // PikPakClient 的构造参数里有 HttpClient，SDK 却只把 Ktor 声明为 runtime 依赖
            implementation(libs.ktor.client.core)
            // state holder 的公开 API 直接暴露 Compose 的 State，消费方要拿得到这些类型
            api(libs.cmp.runtime)
            api(libs.pikpak.kotlin)
            // 本机回环代理的服务端：只需 GET/HEAD 加 Range，原始 socket 足够
            implementation(libs.ktor.network)
            // 登录、会话与凭据全部经它：业务层只认 AuthService，碰不到密码与存储
            api(project(":auth"))
            api(project(":core"))
            // 把网盘的读取接给缩略图引擎；引擎本身不认识网盘
            api(project(":thumbnail"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // 冒烟测试用 MockEngine 顶替 PikPak 的 API 与 CDN，SDK 的请求、鉴权与解析仍走真实代码
        val desktopTest by getting {
            dependencies {
                implementation(libs.ktor.client.mock)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}

// 播放后端的测试真去解码：用程序自带的那份 libmpv 与仓库里的样片，不联网
val mpvDirectory = rootProject.project(":desktopApp").layout.buildDirectory.dir("appResources/common/mpv")
tasks.withType<Test> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    dependsOn(":desktopApp:bundledAppResources")
    systemProperty("pikseek.mpv.dir", mpvDirectory.get().asFile.absolutePath)
    systemProperty("pikseek.testdata", rootProject.file("testdata/media").absolutePath)
}
