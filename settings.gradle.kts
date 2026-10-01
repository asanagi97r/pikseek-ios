pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "pikseek"

// PikSeek 自己的模块：与 Piko 的代码分开，各自能单独更新
include(":auth")       // 独立认证：LocalAuthBroker、DPAPI、认证网络白名单
include(":core")       // 数据目录、网络审计、脱敏、安全报告、本机性能指标
include(":thumbnail")  // 时间轴缩略图引擎（与播放器分离）

// 取自 Piko（MIT，见 LICENSE.piko）的模块，只留桌面端
include(":shared")
include(":ui")
include(":desktopApp")

// iOS 版的入口与平台适配（只在 macOS 上构建），Xcode 工程在 iosApp/
include(":iosKit")
