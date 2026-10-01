plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// 最底下的一层：数据目录、网络审计、脱敏、安全报告与本机性能指标。
// 只依赖标准库与 kotlinx 的几个基础库。它要能说清「进程访问过哪些主机」，自己就不该再带进别的网络代码。
kotlin {
    jvm("desktop")
    jvmToolchain(25)
    iosArm64()
    iosSimulatorArm64()
    // 英特尔芯片的 Mac 上的模拟器
    iosX64()

    sourceSets {
        all {
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.atomicfu)
            // 报告里的时区是公开参数
            api(libs.kotlinx.datetime)
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.junit)
            }
        }
    }
}
