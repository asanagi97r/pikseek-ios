# 第三方依赖清单

> 由 `docs/make_dependency_list.py` 生成，不要手改。列的是打进程序包的每一个第三方库：
> 版本是 Gradle 解析后实际用的版本，许可读自各库随包发布的 POM。

共 219 个库。会**主动**联网的（遥测、更新检查、崩溃上报、广告）：**0 个**。未归类：0 个。

「联网能力」的三档：

- **无**：库里没有发起网络连接的代码。
- **被动**：有网络代码，但只在程序让它连的时候连、连到程序给它的地址。程序给的地址只有 PikPak 官方域名、
  PikPak 下发的直链与上传端点，以及本机回环。
- **主动**：会自己决定去连某个地址。这份清单里不应该有；有的话上面的数字不是 0。

PikSeek 自己的认证模块（`auth/`）不在表里：它不用任何第三方网络库，用的是 JDK 自带的 `java.net.http`。

## Java 库

| 库 | 版本 | 许可 | 用途 | 联网能力 |
| --- | --- | --- | --- | --- |
| `androidx.annotation:annotation-jvm` | 1.9.1 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.annotation:annotation` | 1.9.1 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.arch.core:core-common` | 2.2.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.collection:collection-jvm` | 1.5.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.collection:collection` | 1.5.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.compose.runtime:runtime-annotation-jvm` | 1.12.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.compose.runtime:runtime-annotation` | 1.12.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.compose.runtime:runtime-desktop` | 1.12.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.compose.runtime:runtime-retain-desktop` | 1.12.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.compose.runtime:runtime-retain` | 1.12.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.compose.runtime:runtime-saveable-desktop` | 1.12.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.compose.runtime:runtime-saveable` | 1.12.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.compose.runtime:runtime` | 1.12.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.graphics:graphics-shapes-desktop` | 1.1.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.graphics:graphics-shapes` | 1.1.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.lifecycle:lifecycle-common-jvm` | 2.11.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.lifecycle:lifecycle-common` | 2.11.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.lifecycle:lifecycle-runtime-compose-desktop` | 2.11.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.11.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.lifecycle:lifecycle-runtime-desktop` | 2.11.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.lifecycle:lifecycle-runtime` | 2.11.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.lifecycle:lifecycle-viewmodel-compose-desktop` | 2.11.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.11.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.lifecycle:lifecycle-viewmodel-desktop` | 2.11.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate-desktop` | 2.11.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate` | 2.11.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.lifecycle:lifecycle-viewmodel` | 2.11.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.navigation3:navigation3-runtime-desktop` | 1.1.7 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.navigation3:navigation3-runtime` | 1.1.7 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.navigationevent:navigationevent-compose-desktop` | 1.1.2 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.navigationevent:navigationevent-compose` | 1.1.2 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.navigationevent:navigationevent-desktop` | 1.1.2 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.navigationevent:navigationevent` | 1.1.2 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.savedstate:savedstate-compose-desktop` | 1.4.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.savedstate:savedstate-compose` | 1.4.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.savedstate:savedstate-desktop` | 1.4.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.savedstate:savedstate` | 1.4.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.window:window-core-jvm` | 1.5.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `androidx.window:window-core` | 1.5.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期、集合等） | 无 |
| `com.googlecode.mp4parser:isoparser` | 1.1.22 | Apache-2.0 | MP4 无损切片（截取片段） | 无 |
| `com.squareup.okhttp3:okhttp-jvm` | 5.3.2 | Apache-2.0 | HTTP 引擎：Ktor 与图片加载都经它出网 | 被动 |
| `com.squareup.okhttp3:okhttp` | 5.3.2 | Apache-2.0 | HTTP 引擎：Ktor 与图片加载都经它出网 | 被动 |
| `com.squareup.okio:okio-jvm` | 3.18.1 | Apache-2.0 | 字节流与文件读写 | 无 |
| `com.squareup.okio:okio` | 3.18.1 | Apache-2.0 | 字节流与文件读写 | 无 |
| `dev.nihildigit:compose-windows-touch-jvm` | 0.1.0 | MIT | Windows 触摸与笔输入接入界面 | 无 |
| `dev.nihildigit:compose-windows-touch` | 0.1.0 | MIT | Windows 触摸与笔输入接入界面 | 无 |
| `io.coil-kt.coil3:coil-compose-core-jvm` | 3.6.3 | Apache-2.0 | 图片加载、解码与缓存 | 无 |
| `io.coil-kt.coil3:coil-compose-core` | 3.6.3 | Apache-2.0 | 图片加载、解码与缓存 | 无 |
| `io.coil-kt.coil3:coil-compose-jvm` | 3.6.3 | Apache-2.0 | 图片加载、解码与缓存 | 无 |
| `io.coil-kt.coil3:coil-compose` | 3.6.3 | Apache-2.0 | 图片加载、解码与缓存 | 无 |
| `io.coil-kt.coil3:coil-core-jvm` | 3.6.3 | Apache-2.0 | 图片加载、解码与缓存 | 无 |
| `io.coil-kt.coil3:coil-core` | 3.6.3 | Apache-2.0 | 图片加载、解码与缓存 | 无 |
| `io.coil-kt.coil3:coil-jvm` | 3.6.3 | Apache-2.0 | 图片加载、解码与缓存 | 无 |
| `io.coil-kt.coil3:coil-network-core-jvm` | 3.6.3 | Apache-2.0 | 图片加载的取图部分 | 被动：取界面要显示的缩略图与头像 |
| `io.coil-kt.coil3:coil-network-core` | 3.6.3 | Apache-2.0 | 图片加载的取图部分 | 被动：取界面要显示的缩略图与头像 |
| `io.coil-kt.coil3:coil-network-okhttp-jvm` | 3.6.3 | Apache-2.0 | 图片加载的取图部分 | 被动：取界面要显示的缩略图与头像 |
| `io.coil-kt.coil3:coil-network-okhttp` | 3.6.3 | Apache-2.0 | 图片加载的取图部分 | 被动：取界面要显示的缩略图与头像 |
| `io.coil-kt.coil3:coil` | 3.6.3 | Apache-2.0 | 图片加载、解码与缓存 | 无 |
| `io.github.nihildigit:pikpak-kotlin-jvm` | 1.3.0 | MIT | PikPak 接口：文件、离线任务、取流。认证不经它 | 被动：只连 PikPak 官方域名与其下发的直链、上传端点 |
| `io.github.nihildigit:pikpak-kotlin` | 1.3.0 | MIT | PikPak 接口：文件、离线任务、取流。认证不经它 | 被动：只连 PikPak 官方域名与其下发的直链、上传端点 |
| `io.ktor:ktor-client-content-negotiation-jvm` | 3.5.2 | Apache-2.0 | PikPak SDK 用的 HTTP 客户端 | 被动 |
| `io.ktor:ktor-client-content-negotiation` | 3.5.2 | Apache-2.0 | PikPak SDK 用的 HTTP 客户端 | 被动 |
| `io.ktor:ktor-client-core-jvm` | 3.5.2 | Apache-2.0 | PikPak SDK 用的 HTTP 客户端 | 被动 |
| `io.ktor:ktor-client-core` | 3.5.2 | Apache-2.0 | PikPak SDK 用的 HTTP 客户端 | 被动 |
| `io.ktor:ktor-client-logging-jvm` | 3.5.2 | Apache-2.0 | PikPak SDK 用的 HTTP 客户端 | 被动 |
| `io.ktor:ktor-client-logging` | 3.5.2 | Apache-2.0 | PikPak SDK 用的 HTTP 客户端 | 被动 |
| `io.ktor:ktor-client-okhttp-jvm` | 3.5.2 | Apache-2.0 | PikPak SDK 用的 HTTP 客户端 | 被动 |
| `io.ktor:ktor-client-okhttp` | 3.5.2 | Apache-2.0 | PikPak SDK 用的 HTTP 客户端 | 被动 |
| `io.ktor:ktor-events-jvm` | 3.5.2 | Apache-2.0 | Ktor 的基础工具 | 无 |
| `io.ktor:ktor-events` | 3.5.2 | Apache-2.0 | Ktor 的基础工具 | 无 |
| `io.ktor:ktor-http-cio-jvm` | 3.5.2 | Apache-2.0 | HTTP 的类型与解析 | 无 |
| `io.ktor:ktor-http-cio` | 3.5.2 | Apache-2.0 | HTTP 的类型与解析 | 无 |
| `io.ktor:ktor-http-jvm` | 3.5.2 | Apache-2.0 | HTTP 的类型与解析 | 无 |
| `io.ktor:ktor-http` | 3.5.2 | Apache-2.0 | HTTP 的类型与解析 | 无 |
| `io.ktor:ktor-io-jvm` | 3.5.2 | Apache-2.0 | Ktor 的基础工具 | 无 |
| `io.ktor:ktor-io` | 3.5.2 | Apache-2.0 | Ktor 的基础工具 | 无 |
| `io.ktor:ktor-network-jvm` | 3.5.2 | Apache-2.0 | 本机回环代理的服务端（只监听 127.0.0.1） | 被动：只在本机回环上收连接 |
| `io.ktor:ktor-network` | 3.5.2 | Apache-2.0 | 本机回环代理的服务端（只监听 127.0.0.1） | 被动：只在本机回环上收连接 |
| `io.ktor:ktor-serialization-jvm` | 3.5.2 | Apache-2.0 | Ktor 的 JSON 支持 | 无 |
| `io.ktor:ktor-serialization-kotlinx-json-jvm` | 3.5.2 | Apache-2.0 | Ktor 的 JSON 支持 | 无 |
| `io.ktor:ktor-serialization-kotlinx-json` | 3.5.2 | Apache-2.0 | Ktor 的 JSON 支持 | 无 |
| `io.ktor:ktor-serialization-kotlinx-jvm` | 3.5.2 | Apache-2.0 | Ktor 的 JSON 支持 | 无 |
| `io.ktor:ktor-serialization-kotlinx` | 3.5.2 | Apache-2.0 | Ktor 的 JSON 支持 | 无 |
| `io.ktor:ktor-serialization` | 3.5.2 | Apache-2.0 | Ktor 的 JSON 支持 | 无 |
| `io.ktor:ktor-sse-jvm` | 3.5.2 | Apache-2.0 | Ktor 的传递依赖，程序不用 SSE | 被动（未使用） |
| `io.ktor:ktor-sse` | 3.5.2 | Apache-2.0 | Ktor 的传递依赖，程序不用 SSE | 被动（未使用） |
| `io.ktor:ktor-utils-jvm` | 3.5.2 | Apache-2.0 | Ktor 的基础工具 | 无 |
| `io.ktor:ktor-utils` | 3.5.2 | Apache-2.0 | Ktor 的基础工具 | 无 |
| `io.ktor:ktor-websocket-serialization-jvm` | 3.5.2 | Apache-2.0 | Ktor 的传递依赖，程序不用 WebSocket | 被动（未使用） |
| `io.ktor:ktor-websocket-serialization` | 3.5.2 | Apache-2.0 | Ktor 的传递依赖，程序不用 WebSocket | 被动（未使用） |
| `io.ktor:ktor-websockets-jvm` | 3.5.2 | Apache-2.0 | Ktor 的传递依赖，程序不用 WebSocket | 被动（未使用） |
| `io.ktor:ktor-websockets` | 3.5.2 | Apache-2.0 | Ktor 的传递依赖，程序不用 WebSocket | 被动（未使用） |
| `net.java.dev.jna:jna-platform` | 5.13.0 | LGPL-2.1+、Apache-2.0 | 调用 Windows 原生接口（MediaMP 用） | 无 |
| `net.java.dev.jna:jna` | 5.13.0 | LGPL-2.1+、Apache-2.0 | 调用 Windows 原生接口（MediaMP 用） | 无 |
| `org.jetbrains.androidx.lifecycle:lifecycle-common` | 2.9.6 | Apache-2.0 | 界面框架的组成部分（导航、生命周期等） | 无 |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose-desktop` | 2.9.6 | Apache-2.0 | 界面框架的组成部分（导航、生命周期等） | 无 |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose` | 2.9.6 | Apache-2.0 | 界面框架的组成部分（导航、生命周期等） | 无 |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime` | 2.9.6 | Apache-2.0 | 界面框架的组成部分（导航、生命周期等） | 无 |
| `org.jetbrains.androidx.navigation3:navigation3-ui-desktop` | 1.1.2 | Apache-2.0 | 界面框架的组成部分（导航、生命周期等） | 无 |
| `org.jetbrains.androidx.navigation3:navigation3-ui` | 1.1.2 | Apache-2.0 | 界面框架的组成部分（导航、生命周期等） | 无 |
| `org.jetbrains.androidx.navigationevent:navigationevent-compose-desktop` | 1.1.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期等） | 无 |
| `org.jetbrains.androidx.navigationevent:navigationevent-compose` | 1.1.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期等） | 无 |
| `org.jetbrains.androidx.savedstate:savedstate-compose-desktop` | 1.3.6 | Apache-2.0 | 界面框架的组成部分（导航、生命周期等） | 无 |
| `org.jetbrains.androidx.savedstate:savedstate-compose` | 1.3.6 | Apache-2.0 | 界面框架的组成部分（导航、生命周期等） | 无 |
| `org.jetbrains.androidx.savedstate:savedstate` | 1.3.6 | Apache-2.0 | 界面框架的组成部分（导航、生命周期等） | 无 |
| `org.jetbrains.androidx.window:window-core-desktop` | 1.5.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期等） | 无 |
| `org.jetbrains.androidx.window:window-core` | 1.5.0 | Apache-2.0 | 界面框架的组成部分（导航、生命周期等） | 无 |
| `org.jetbrains.compose.animation:animation-core-desktop` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.animation:animation-core` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.animation:animation-desktop` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.animation:animation` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.desktop:desktop-jvm-windows-x64` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.desktop:desktop-jvm` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.desktop:desktop` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.foundation:foundation-desktop` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.foundation:foundation-layout-desktop` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.foundation:foundation-layout` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.foundation:foundation` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material3.adaptive:adaptive-desktop` | 1.3.0-rc01 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material3.adaptive:adaptive-layout-desktop` | 1.3.0-rc01 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material3.adaptive:adaptive-layout` | 1.3.0-rc01 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material3.adaptive:adaptive-navigation-desktop` | 1.3.0-rc01 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material3.adaptive:adaptive-navigation3-desktop` | 1.3.0-rc01 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material3.adaptive:adaptive-navigation3` | 1.3.0-rc01 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material3.adaptive:adaptive-navigation` | 1.3.0-rc01 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material3.adaptive:adaptive` | 1.3.0-rc01 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material3:material3-adaptive-navigation-suite-desktop` | 1.12.0-alpha03 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material3:material3-adaptive-navigation-suite` | 1.12.0-alpha03 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material3:material3-desktop` | 1.12.0-alpha03 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material3:material3` | 1.12.0-alpha03 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material:material-desktop` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material:material-icons-core-desktop` | 1.7.3 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material:material-icons-core` | 1.7.3 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material:material-icons-extended-desktop` | 1.7.3 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material:material-icons-extended` | 1.7.3 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material:material-ripple-desktop` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material:material-ripple` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.material:material` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.runtime:runtime-desktop` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.runtime:runtime-saveable-desktop` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.runtime:runtime-saveable` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.runtime:runtime` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.ui:ui-backhandler-desktop` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.ui:ui-backhandler` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.ui:ui-desktop` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.ui:ui-geometry-desktop` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.ui:ui-geometry` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.ui:ui-graphics-desktop` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.ui:ui-graphics` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.ui:ui-text-desktop` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.ui:ui-text` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.ui:ui-tooling-preview-desktop` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.ui:ui-tooling-preview` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.ui:ui-unit-desktop` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.ui:ui-unit` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.ui:ui-util-desktop` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.ui:ui-util` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.compose.ui:ui` | 1.12.0 | Apache-2.0 | 界面框架 Compose Multiplatform | 无 |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.10 | Apache-2.0 | Kotlin 标准库 | 无 |
| `org.jetbrains.kotlinx:atomicfu-jvm` | 0.28.0 | Apache-2.0 | 原子操作 | 无 |
| `org.jetbrains.kotlinx:atomicfu` | 0.28.0 | Apache-2.0 | 原子操作 | 无 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom` | 1.11.0 | Apache-2.0 | 协程 | 无 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | 1.11.0 | Apache-2.0 | 协程 | 无 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.11.0 | Apache-2.0 | 协程 | 无 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-slf4j` | 1.11.0 | Apache-2.0 | 协程 | 无 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-swing` | 1.11.0 | Apache-2.0 | 协程 | 无 |
| `org.jetbrains.kotlinx:kotlinx-datetime-jvm` | 0.7.1 | Apache-2.0 | 日期时间 | 无 |
| `org.jetbrains.kotlinx:kotlinx-datetime` | 0.7.1 | Apache-2.0 | 日期时间 | 无 |
| `org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm` | 0.9.1 | Apache-2.0 | 字节流 | 无 |
| `org.jetbrains.kotlinx:kotlinx-io-bytestring` | 0.9.1 | Apache-2.0 | 字节流 | 无 |
| `org.jetbrains.kotlinx:kotlinx-io-core-jvm` | 0.9.1 | Apache-2.0 | 字节流 | 无 |
| `org.jetbrains.kotlinx:kotlinx-io-core` | 0.9.1 | Apache-2.0 | 字节流 | 无 |
| `org.jetbrains.kotlinx:kotlinx-serialization-bom` | 1.11.0 | Apache-2.0 | JSON 序列化 | 无 |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm` | 1.11.0 | Apache-2.0 | JSON 序列化 | 无 |
| `org.jetbrains.kotlinx:kotlinx-serialization-core` | 1.11.0 | Apache-2.0 | JSON 序列化 | 无 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-io-jvm` | 1.11.0 | Apache-2.0 | JSON 序列化 | 无 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-io` | 1.11.0 | Apache-2.0 | JSON 序列化 | 无 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm` | 1.11.0 | Apache-2.0 | JSON 序列化 | 无 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.11.0 | Apache-2.0 | JSON 序列化 | 无 |
| `org.jetbrains.runtime:jbr-api` | 1.9.0 | Apache-2.0 | JetBrains 运行时的接口声明（界面框架的传递依赖） | 无 |
| `org.jetbrains.skiko:skiko-awt-runtime-windows-x64` | 0.150.1 | Apache-2.0 | 界面渲染（Skia）；缩略图雪碧图的 WebP 编解码 | 无 |
| `org.jetbrains.skiko:skiko-awt` | 0.150.1 | Apache-2.0 | 界面渲染（Skia）；缩略图雪碧图的 WebP 编解码 | 无 |
| `org.jetbrains.skiko:skiko` | 0.150.1 | Apache-2.0 | 界面渲染（Skia）；缩略图雪碧图的 WebP 编解码 | 无 |
| `org.jetbrains:annotations` | 23.0.0 | Apache-2.0 | 编译期注解 | 无 |
| `org.jspecify:jspecify` | 1.0.0 | Apache-2.0 | 编译期注解 | 无 |
| `org.kotlincrypto.bitops:bits-jvm` | 0.3.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto.bitops:bits` | 0.3.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto.bitops:endian-jvm` | 0.3.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto.bitops:endian` | 0.3.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto.core:core-jvm` | 0.8.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto.core:core` | 0.8.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto.core:digest-jvm` | 0.8.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto.core:digest` | 0.8.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto.core:mac-jvm` | 0.8.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto.core:mac` | 0.8.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto.hash:md-jvm` | 0.8.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto.hash:md` | 0.8.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto.hash:sha1-jvm` | 0.8.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto.hash:sha1` | 0.8.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto.hash:sha2-jvm` | 0.8.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto.hash:sha2` | 0.8.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto.macs:hmac-jvm` | 0.8.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto.macs:hmac-sha1-jvm` | 0.8.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto.macs:hmac-sha1` | 0.8.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto.macs:hmac` | 0.8.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto:error-jvm` | 0.4.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.kotlincrypto:error` | 0.4.0 | Apache-2.0 | 哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用 | 无 |
| `org.openani.mediamp:mediamp-all-desktop` | 0.5.0 | Apache-2.0 | 播放器的公共接口与界面表面 | 无 |
| `org.openani.mediamp:mediamp-all` | 0.5.0 | Apache-2.0 | 播放器的公共接口与界面表面 | 无 |
| `org.openani.mediamp:mediamp-api-desktop` | 0.5.0 | Apache-2.0 | 播放器的公共接口与界面表面 | 无 |
| `org.openani.mediamp:mediamp-api` | 0.5.0 | Apache-2.0 | 播放器的公共接口与界面表面 | 无 |
| `org.openani.mediamp:mediamp-internal-utils-desktop` | 0.5.0 | Apache-2.0 | 播放器的公共接口与界面表面 | 无 |
| `org.openani.mediamp:mediamp-internal-utils` | 0.5.0 | Apache-2.0 | 播放器的公共接口与界面表面 | 无 |
| `org.openani.mediamp:mediamp-mpv-desktop` | 0.5.0 | Apache-2.0 | 播放器：libmpv 的绑定与 D3D11 零拷贝画面 | 被动：mpv 读程序交给它的地址，正常情况下只有本机回环代理 |
| `org.openani.mediamp:mediamp-mpv` | 0.5.0 | Apache-2.0 | 播放器：libmpv 的绑定与 D3D11 零拷贝画面 | 被动：mpv 读程序交给它的地址，正常情况下只有本机回环代理 |
| `org.openani.mediamp:mediamp-native-loader-desktop` | 0.5.0 | Apache-2.0 | 播放器的公共接口与界面表面 | 无 |
| `org.openani.mediamp:mediamp-native-loader` | 0.5.0 | Apache-2.0 | 播放器的公共接口与界面表面 | 无 |
| `org.slf4j:slf4j-api` | 2.0.18 | MIT | 日志接口（依赖库用），输出只到本机 | 无 |
| `org.slf4j:slf4j-simple` | 2.0.17 | MIT | 日志接口（依赖库用），输出只到本机 | 无 |

## 原生库与运行时

| 组件 | 来源 | 许可 | 用途 | 联网能力 |
| --- | --- | --- | --- | --- |
| libmpv 0.41.0（`libmpv-2.dll`）、FFmpeg 8.0.1（`avcodec`、`avformat` 等）及其依赖的 DLL | MediaMP 的 `mediamp-mpv-runtime-windows-x64` 0.5.0，原样解到 `app/resources/mpv/` | mpv：LGPL-2.1+（这份构建带 `-Dgpl=false`，可在程序里问 mpv 的 `mpv-configuration` 属性核对）；FFmpeg：LGPL-2.1+；另有 libass（ISC）、FreeType、HarfBuzz、OpenSSL（Apache-2.0）、libplacebo（LGPL-2.1+）等 | 视频解码与播放；缩略图引擎另开一个无画面的 mpv 实例取帧 | 被动：读程序交给它的地址。主播放器读本机回环代理；缩略图引擎读本机临时文件或本机回环代理 |
| Skia（`skiko-windows-x64.dll`） | JetBrains skiko | BSD-3-Clause（Skia）、Apache-2.0（skiko） | 界面渲染、WebP 编解码 | 无 |
| Java 运行时（`runtime/`） | Azul Zulu JDK 25，经 jlink 裁剪 | GPL-2.0 with Classpath Exception | 运行程序。认证模块用其中的 `java.net.http` | 被动 |

## 说明

- PikSeek 的文件管理、取流与播放器部分取自 [Piko](https://github.com/NihilDigit/piko)（MIT，Copyright (c) 2026 NihilDigit），
  许可文本随程序放在 `app/resources/licenses/LICENSE.piko`。
- 程序包里的 mpv 与 FFmpeg 是 LGPL 许可的动态库，没有改动、没有静态链接，可以整个换成自己编译的同版本 DLL。
  对应源码：<https://github.com/mpv-player/mpv>（v0.41.0）、<https://ffmpeg.org>（8.0.1），构建脚本在 <https://github.com/open-ani/mediamp>。
  PikSeek 自己的源码在 `30_code/pikseek/`。
- 自动更新、遥测、统计、崩溃上报、广告：一个都没有集成。程序运行时还会按类名再查一遍类路径（设置 → 安全与隐私）。
