plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// 独立认证模块。不依赖 Piko 的任何代码，也不依赖 PikPak SDK：
// 登录、刷新、凭据存储与认证网络白名单都在这里，别的模块只经 AuthService 取会话。
kotlin {
    jvm("desktop")
    jvmToolchain(25)

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                api(project(":core"))
            }
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
