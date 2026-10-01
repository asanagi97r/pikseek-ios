# PikSeek

Windows 上的 PikPak 桌面客户端，便携版。文件管理、取流与播放器取自开源项目 [Piko](https://github.com/NihilDigit/piko)（MIT），
在它之上换掉了登录与凭据存储，加了时间轴缩略图预览、网络审计与安全报告，去掉了自动更新。

第三方客户端，与 PikPak 官方无关。

## 用法

1. 把 `PikSeek` 整个文件夹放到任意位置（本地盘，不要在网络共享上直接跑），双击 `PikSeek.exe`。
   不需要安装任何东西：Java 运行时、mpv、FFmpeg、C/C++ 运行库都在文件夹里。文件夹名可以带空格和中文。
2. 登录：账号加密码，或者账号加刷新令牌。
3. 数据都在 `PikSeek\data\` 里（会话密文、设置、日志、预览缓存、临时文件）。删掉这个文件夹等于恢复出厂。

把文件夹拷到另一台电脑：能直接用，但要**重新登录一次**——会话是用 Windows DPAPI 按「当前 Windows 用户」加密的，
换了电脑或换了用户解不开，这是有意的。设置与预览缓存照常带过去。

程序目录不可写时（比如放进了 `Program Files`），数据改放 `%LOCALAPPDATA%\PikSeek`，安全页会写明。

系统要求：Windows 10 / 11，x64。

## 与 Piko 的不同

| | Piko | PikSeek |
| --- | --- | --- |
| 登录与刷新会话 | 由 PikPak SDK 发 | 由独立的 `auth` 模块发，不经 SDK；有主机白名单，不跟随重定向 |
| 密码 | 保存（为了刷新令牌失效后悄悄重登） | **从不保存** |
| 会话存储 | 系统保管处；写不进时退到明文文件 | 只有 DPAPI；**存不了就不存**，提示下次重新登录 |
| 刷新令牌 | 在 SDK 的内存里 | 只在 `auth` 模块里，SDK 拿到的会话不带它 |
| 进度条悬停 | 显示时间 | 显示该时刻的缩略图 + 时间；整条时间轴的预览在后台生成并存在本机 |
| 拖动进度条 | 松手才跳 | 可选：关 / 自适应（慢拖时画面跟着走）/ 始终 |
| 切到下一个视频 | 从查详情做起 | 提前备好下一条的描述，少一次请求 |
| 自动更新 | 有（GitHub，失败时走镜像站） | 删掉了，程序不联系任何更新服务器 |
| 设置同步到网盘 | 默认开 | 默认关 |
| 数据位置 | `~/.piko` | 程序旁的 `data\`；程序目录之外不写任何东西（连 JVM 默认建在用户临时目录的 `hsperfdata` 也关了） |
| 放在带中文的文件夹里 | 用的是 JDK 原样的启动器：系统代码页写不出的文件夹名（英文系统上的中文）下起不来，没有窗口 | 给启动器加了 UTF-8 清单，能启动 |
| 启动时写注册表 | 询问是否关联磁力链接 | 不碰；只在设置里手动点了才登记 |
| 能看到程序访问了哪 | — | 设置 → 安全与隐私：主机列表、认证状态、明文凭据扫描，可导出 |
| 性能数字 | 日志里 | 性能浮层 + 可导出的样本 |

没改的：文件浏览、搜索、最近、星标、新建、重命名、移动、删除、磁力与种子、离线任务、下载上传、
播放器本身（mpv，硬解，多音轨，内挂与外挂字幕，原画与转码切换）。

## 已验证与未验证

**已验证**（都在这台开发机上，没有显卡的虚拟机）：

- 374 个自动测试通过，另有 2 个是上游自己停用的。其中新写的：`core` 17、`auth` 32、`thumbnail` 40、
  `shared` 的会话冒烟 6、`desktopApp` 的界面与预览 5。
- 认证：真的 DPAPI 加解密；磁盘上只有密文；登录请求与 PikPak SDK 的线上实现逐字段一致。
- 预览：用程序自带的 libmpv 真解码；合成的转码流上每张图与时刻相符；本机 2 小时长片 180 张 5.1 秒。
- 打好的 `PikSeek.exe`：启动到登录页、播放本机样片、预览生成、认证存储自检，全部通过；从 zip 解开的那份也跑过一遍。
- 便携：拷到一个带空格和中文的文件夹里双击——约 1.7 秒出窗口，数据落在程序旁的 `data\`，再点一次是把已开的窗口叫到前面，
  关掉后没有残留进程，用户目录、`AppData`、用户临时目录里没有多出任何东西。
- 外部依赖：扫了包里全部 110 个 exe / dll 的导入表，包外用到的 37 个都是 Windows 自带的系统库；
  VC++ 运行库、UCRT、MinGW 运行库都在包里。宿主机不用装任何东西。
- 四个认证主机不带凭据探测：证书有效，按 PikPak 网关的方式应答。

**未验证，需要你在有账号的机器上试**：

- **用真账号登录。** 这台机器上没有 PikPak 账号。登录失败的话把报错的那句话和「设置 → 关于 → 导出日志」给我。
- **网盘视频的缩略图。** 真的转码流没见过；用不了时会自动退到原画，开性能浮层能看到用的是哪个来源。
- **与 Piko 的速度对比。** `docs/BASELINE_PIKO.md` 与 `docs/BENCHMARK_CURRENT.md` 里这些格子写着「未测」。
- 有显卡的机器上的播放（硬解、D3D11 画面）。这部分代码没动过，是 Piko 原样的。

## 文档

| 文件 | 内容 |
| --- | --- |
| `docs/SECURITY_ARCHITECTURE.md` | 认证的边界、机密怎么流动、有哪些保证、哪些没做到 |
| `docs/AUTH_ENDPOINTS.md` | 认证模块访问的每个主机与端点、依据 |
| `docs/THUMBNAIL_PLAN.md` | 时间轴预览的做法 |
| `docs/PERFORMANCE_PLAN.md` | 性能方面做了什么、没做什么、怎么量 |
| `docs/BASELINE_PIKO.md`、`docs/BENCHMARK_CURRENT.md` | 基准：已测的数字与待测的格子 |
| `docs/PIKO_RESEARCH.md` | 对 Piko 的研究笔记 |
| `THIRD_PARTY_DEPENDENCIES.md` | 程序包里每个第三方库的版本、许可、用途、联网能力 |
| `BUILD.md` | 怎么构建、怎么出发行包 |

## 代码结构

```
core/        数据目录、网络审计、脱敏、安全报告、性能指标、拖动策略   （只依赖 JDK 与协程）
auth/        AuthService、AuthBroker、PikPakAuthClient、DpapiCredentialStore、AuthNetworkPolicy
thumbnail/   ThumbnailEngine、ThumbnailPlan、TsScan、MpvFrameGrabber、ThumbnailCache
shared/      取自 Piko：状态与业务、取流、本机代理；PikoClientManager 与 BrokerSessionStore 是照新边界重写的
ui/          取自 Piko：界面；dev/pikseek/ui 下是新写的登录页、预览气泡、性能浮层、两节设置
desktopApp/  取自 Piko：Windows 入口与播放器窗口；dev/pikseek/desktop 下是预览的接线与自检
```

`core`、`auth`、`thumbnail` 三个模块不依赖 Piko 的代码，可以各自单独更新。

## 许可

Piko 的部分：MIT，Copyright (c) 2026 NihilDigit，见 `LICENSE.piko`。mpv 与 FFmpeg 是 LGPL 的动态库。
其余见 `THIRD_PARTY_DEPENDENCIES.md`。
