plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// 最底下的一层：数据目录、网络审计、脱敏、安全报告与本机性能指标。
// 只依赖 JDK 与协程。它要能说清「进程访问过哪些主机」，自己就不该再带进别的网络代码。
kotlin {
    jvm("desktop")
    jvmToolchain(25)

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.junit)
            }
        }
    }
}
