plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// 独立认证模块。不依赖 Piko 的任何代码，也不依赖 PikPak SDK：
// 登录、刷新、凭据存储与认证网络白名单都在这里，别的模块只经 AuthService 取会话。
// 规则（白名单、重试、存不了就不存）是各平台共用的一份；只有「发 HTTPS 请求」与「加密存储」按平台实现：
// 桌面是 JDK 的 HTTP 客户端 + DPAPI，iOS 是 URLSession + 钥匙串。
kotlin {
    jvm("desktop")
    jvmToolchain(25)
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.atomicfu)
            // 接口上的 IOException 是 kotlinx-io 的（桌面上就是 java.io.IOException）
            api(libs.kotlinx.io.core)
            api(project(":core"))
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.core)
                // 只在测试里：拿 PikPak SDK 发出的认证请求当参照，逐字段核对自己实现的请求与它一致
                implementation(libs.pikpak.kotlin)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.mock)
            }
        }
    }
}

tasks.withType<Test> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
