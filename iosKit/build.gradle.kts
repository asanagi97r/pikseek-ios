import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

// iOS 版的 Kotlin 一侧：入口、平台适配、播放页，连同它依赖的全部共用代码，打成一个静态框架 PikSeekKit，
// 由 iosApp 的 Xcode 工程链接进去。只能在 macOS 上构建（GitHub 的流程里）：
//   ./gradlew :iosKit:assemblePikSeekKitDebugXCFramework
// 产物在 iosKit/build/XCFrameworks/<debug|release>/PikSeekKit.xcframework，真机与模拟器两份都在里面。
kotlin {
    val xcframework = XCFramework("PikSeekKit")
    // 模拟器的那一份跟着打包机的芯片走：苹果芯片的 Mac 用 arm64，英特尔芯片的用 x86_64（-Ppikseek.simulatorX64）
    val simulator = if (providers.gradleProperty("pikseek.simulatorX64").isPresent) iosX64() else iosSimulatorArm64()
    listOf(iosArm64(), simulator).forEach { target ->
        target.binaries.framework {
            baseName = "PikSeekKit"
            isStatic = true
            xcframework.add(this)
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("androidx.compose.ui.ExperimentalComposeUiApi")
            languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        iosMain.dependencies {
            implementation(project(":shared"))
            implementation(project(":ui"))
            implementation(libs.cmp.runtime)
            implementation(libs.cmp.foundation)
            implementation(libs.cmp.ui)
            implementation(libs.cmp.material3)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
            implementation(libs.coil.compose)
            // 图片（文件缩略图、头像）经 Ktor 取，引擎用系统的 URLSession
            implementation(libs.coil.network.ktor3)
            implementation(libs.ktor.client.darwin)
        }
    }
}
