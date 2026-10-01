plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// 时间轴缩略图引擎。与播放器完全分开：自己起一个无画面的 libmpv 解码低清晰度流，
// 生成雪碧图存到本机，悬停时只读缓存。不依赖 Piko 的代码，也不依赖 Compose。
// 排程、TS 切片、缓存是各平台共用的一份；只有「解码取帧」按平台实现（FrameGrabber）。
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
            implementation(libs.kotlinx.io.core)
            // 摘要与时钟
            implementation(project(":core"))
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.junit)
            }
        }
    }
}

// 测试真去解码：用的是程序自带的那份 libmpv（由 desktopApp 的 bundledAppResources 从 MediaMP 的运行库里解出来），
// 以及仓库里的样片。测试自己不联网。
val mpvDirectory = rootProject.project(":desktopApp").layout.buildDirectory.dir("appResources/common/mpv")
tasks.withType<Test> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    dependsOn(":desktopApp:bundledAppResources")
    systemProperty("pikseek.mpv.dir", mpvDirectory.get().asFile.absolutePath)
    systemProperty("pikseek.testdata", rootProject.file("testdata/media").absolutePath)
}
