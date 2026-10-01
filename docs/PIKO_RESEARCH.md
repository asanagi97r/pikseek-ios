# Piko 研究笔记

对象：[NihilDigit/piko](https://github.com/NihilDigit/piko)，提交 `5f950d7`（2026-10-01），MIT 许可；
以及它依赖的 PikPak SDK [pikpak-kotlin](https://github.com/NihilDigit/pikpak-kotlin) 1.3.0（同一作者，MIT）。
读的是源码，没有运行过原版 Piko。

## 它是什么

Kotlin Multiplatform + Compose 的 PikPak 第三方客户端，Android 与桌面共用界面。约 7.8 万行，四个模块：

| 模块 | 内容 |
| --- | --- |
| `shared` | 状态与业务：网盘页、传输、播放器的取流策略、本机回环代理 |
| `ui` | 全部界面（Material 3） |
| `app` | Android 入口 |
| `desktopApp` | Windows / macOS / Linux 入口、播放器窗口、Windows 原生胶水（FFM 直调） |

网盘能力全部来自 SDK；Piko 自己不拼 PikPak 的请求。

## 登录与凭据（PikSeek 没有沿用的部分）

- SDK 的 `PikPakClient(account, passwordSupplier, sessionStore)`：`login()` 依次试内存里的会话、存储里的会话、
  刷新令牌、最后用密码登录。**登录与刷新的请求由 SDK 自己发。**
- Piko 的 `PikoSessionStore` 把会话**与密码**按账号合成一份存下，为的是刷新令牌失效后能悄悄用密码重登。
- 桌面端 `DesktopSessionStore` 经 `LayeredVault` 存：先试系统保管处（Windows 是 DPAPI），**写不进时退到
  `accounts/<key>.plain` 明文文件**（`PlainFileVault`），数据在 `~/.piko`。
- 登录页的 `LoginState` 把密码作为 Compose 状态里的 `String` 一直拿着。

PikSeek 的做法见 `SECURITY_ARCHITECTURE.md`：不存密码、没有明文兜底、登录与刷新不经 SDK。

## PikPak 接口（沿用）

SDK 与 Piko 的 `CLAUDE.md` 里记着不少实测结论，直接用，没有重新推导：

- 没有服务端按名搜索，全盘搜索只能客户端递归遍历。
- 离线任务只吃整条磁力链接，不能按文件选。
- gcid 是内容哈希，与文件名、位置无关——PikSeek 的缩略图缓存据此认视频。
- 一条签名直链最多 8 条并发连接，整个账号约 16 条，再多会被 503 拒掉并且越要越少。
- 四个根域名通用，见 `AUTH_ENDPOINTS.md`。

## 取流与播放（沿用，这是 Piko 快的原因）

- **所有读取都经本机回环代理**（`PikoMediaProxy`）：播放器只拿到一个 `http://127.0.0.1:<端口>/...`。
  直链过期重取、连接预算、分块缓存、预读都在 SDK 的 `PikPakFileHandle` / `PikPakStreamReader` 里。
- **分块缓存**：256 KiB 一块，按块取、按 LRU 淘汰；mpv 拖动时新旧连接各有读位置、共用一份缓存。
- **多连接读取**：一个文件最多 8 路并发 Range 请求；被卡住的那一块优先级最高。
- **前后台**：`StreamRole.FOREGROUND / BACKGROUND`，后台读整体让着前台，有前台需求时后台每个文件至多两路。
  PikSeek 的缩略图引擎就用 `BACKGROUND`。
- **边缘节点调度**（`HostHealth`）：读得慢或失败的节点会被换成量过更快的兄弟节点。
- **取流顺序**：本机副本 → 回环代理 → 直链 → 转码流，只在首帧前失败才换下一个；播到一半断掉按 0.5、1.5、4 秒退避重连。
- **转码流是 MPEG-TS、没有索引**：mpv 在里面按时间戳二分查找，一次起播十来处跳读（实测 4 到 18 秒）。
  Piko 的信息流因此不让 mpv 跳，而是按码率估位置、找关键帧、把一小段截出来当短文件播（`SlicedByteSource`）。
  PikSeek 的缩略图引擎沿用了这个思路，见 `THUMBNAIL_PLAN.md`。

## mpv / MediaMP（沿用）

- 桌面端用 [MediaMP](https://github.com/open-ani/mediamp) 0.5.0 的 mpv 后端：libmpv 0.41.0 + FFmpeg 8.0.1（都是 LGPL 构建），
  画面经 D3D11 零拷贝进 Skia。这部分自己写成本最高，原样用。
- 版本钉死：Compose 1.12.0、MediaMP 0.5.0。Compose 1.13 的 skiko 改了渲染后端的包装，MediaMP 的画面表面拿不到
  `Direct3DRedrawer`，一开播放器就崩。**升级前要先确认这一点。**
- mpv 的句柄经反射从 MediaMP 取出（`getHandle$mediamp_mpv`），用来挂外挂字幕、旋转、读轨道。
- 原生库随程序放在 `app/resources/mpv/`，启动时指给 MediaMP，不让它每次往临时目录解一份。
- 打包：ProGuard 只裁剪不混淆；jlink 的精简运行时；JDK 25 的 AOT 缓存（训练时把程序真跑 12 秒）。

## Windows 胶水（沿用，有改动）

- 自绘标题栏、触摸与笔、任务栏进度、Toast、文件框：经 FFM 直调，原样用。
- 单实例：原来是 `~/.piko` 下的文件锁加 Unix domain socket。socket 路径上限约 108 字节，便携版放得深就绑不上；
  PikSeek 改成数据目录里的文件锁加本机回环端口。
- 磁力链接关联：原来首次启动会弹框问。PikSeek 是便携版，不在启动时碰注册表，只在设置里手动点了才登记，
  登记名也换成了 `PikSeek.*`，不与装着的 Piko 抢。
- 应用内更新（GitHub API、`release.json`、失败时退到 `ghfast.top` 镜像、zstd 差分、zsync）：**整个删掉**。

## 没有带过来的

`app`（Android）、`cli`、`shots`（截图工具）、macOS / Linux 的打包脚本、发版流程。桌面端里 macOS / Linux 的分支代码还在
（`MacOs.kt`、`LinuxDesktop.kt`），没有测过，PikSeek 只出 Windows x64。

## 留着没动、值得知道的行为

这些是 Piko 的功能，PikSeek 保留了，它们会在你的网盘里写东西或向 PikPak 报进度：

- **设置同步**：部分设置存成网盘根目录下 `.piko/settings-<时间戳>.json`（设置 → 账号与同步）。
  里面是界面设置与快速访问的文件夹，没有凭据。与 Piko 共用同一个文件夹与格式。
  **Piko 默认开着，PikSeek 改成了默认关**：往网盘里写文件该由用户自己打开。
- **`Piko-Temp` 文件夹**：预览磁力链接、打开归档条目时借用，用完清理。
- **同步播放记录**：把播放进度报给 PikPak 的播放历史，默认开着。

## 许可

Piko 与 pikpak-kotlin 都是 MIT。PikSeek 保留了 Piko 的版权声明（`LICENSE.piko`，随程序放在 `app/resources/licenses/`），
关于页写明出处。全部第三方依赖见 `THIRD_PARTY_DEPENDENCIES.md`。
