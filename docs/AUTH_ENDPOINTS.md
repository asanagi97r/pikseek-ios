# 认证端点清单

认证模块（`auth/`）能访问的全部地址。代码里的白名单在 `AuthNetworkPolicy.kt`，每个认证请求发出前都要过它；
不在这张表里的地址，请求发不出去。

## 主机

| 主机 | 是否官方 | 用途 | 依据 |
| --- | --- | --- | --- |
| `user.mypikpak.com` | 是，PikPak 自己的域名 | 取验证令牌、密码登录、刷新会话 | ①② |
| `user.mypikpak.net` | 是，PikPak 的备用根域名 | 刷新会话（设置里选了这个根域名时） | ①② |
| `user.pikpak.me` | 同上 | 同上 | ①② |
| `user.pikpakdrive.com` | 同上 | 同上 | ①② |

密码登录固定走 `user.mypikpak.com`；另外三个只用于刷新会话，而且只在用户在「设置 → 传输与网络 → 服务器域名」
里选了对应根域名时才用（有的网络环境下 `.com` 连不上）。

依据：

1. **PikPak SDK（pikpak-kotlin 1.3.0）的源码**，`PikPakConstants.kt`、`AuthApi.kt`、`PikPakDomain.kt`。后者写明这四个根域名
   是「PikPak 官方网页端的包里列出的那四个」，并记有 2026-09-28 的实测：四个根下的 `user`、`api-drive` 各自解析到同一
   服务商的地址、证书与域名相符、同一组令牌通用；刷新令牌与验证令牌在 `mypikpak.net` 下实测成功；
   **别的根域名下的密码登录没有实测过**，所以这里密码登录只走 `.com`。SDK 的认证写法又出自
   [52funny/pikpakcli](https://github.com/52funny/pikpakcli)。
2. **本机实测（2026-10-01，不带任何凭据）**：对四个主机各发一个 `GET /v1/user/me`，TLS 证书校验全部通过，
   都回 HTTP 401、`error_code` 16（`unauthenticated`）——这是 PikPak 网关对未登录请求的固定回答，
   劫持页、过滤设备或别的服务回的不是这个。

没有实测的：密码登录与刷新会话这两个请求本身。开发机上没有 PikPak 账号。代替的验证是
`auth` 模块的 `WireCompatibilityTest`：同一组假回应下，本模块发出的三个请求（地址、`User-Agent`、`X-Device-Id`、
`Content-Type`、请求体的每个字段）与 SDK 发出的逐项相同，而 SDK 的这套请求是在线上用着的。

## 端点

| 方法与路径 | 用途 | 请求里的敏感内容 | 响应里的敏感内容 |
| --- | --- | --- | --- |
| `POST /v1/shield/captcha/init` | 登录前向服务端要一个验证令牌 | 账号名、设备标识（账号名的 MD5） | 验证令牌（一次性，只用于紧接着的登录） |
| `POST /v1/auth/signin` | 密码登录 | 账号名、**密码**、验证令牌 | **访问令牌、刷新令牌** |
| `POST /v1/auth/token` | 用刷新令牌换新的会话 | **刷新令牌** | **访问令牌、刷新令牌** |

三个请求都带：`User-Agent: ANDROID-com.pikcloud.pikpak/1.21.0`、`X-Device-Id`、`Content-Type: application/json`，
以及请求体里的 `client_id`、`client_secret`（PikPak 官方 Android 客户端的公开标识，不是用户的机密，写在 SDK 与
pikpakcli 的源码里）。

## 策略

- 只走 HTTPS，端口只能是 443，地址里不能带用户名。
- 不跟随重定向：服务端回 3xx 指向别处时，密码不会被带过去。
- 路径只能是上表的三个。
- 每个请求在 `PikPakAuthClient` 里过一次白名单，在传输层 `JdkTransport` 里再过一次（就算有人绕过前者直接用传输层也出不去）。
- 被拦下的请求数显示在「设置 → 安全与隐私 → 被白名单拦下的请求」，正常应当一直是 0。
- 每个认证请求只把「主机 + 用途」记进网络审计，不记地址里的任何别的部分，更不记请求体与响应。

## 不访问的

- 任何第三方登录（Google、Apple 等）的端点：没有实现，登录只有「账号 + 密码」与「刷新令牌」两种。
- PikPak 的人机验证页面（`.../captcha/v2/...`）：服务端要求人机验证时，登录报错并说明原因，**不绕过、不代答**。
- `access.<根域名>/access_controller/v1/area_accessible`（地区检查）：PikPak 网页端自己的闸，API 不依赖它，SDK 与本程序都不问。
- 更新服务器、遥测、崩溃上报：程序里没有这些代码。

## 认证之外，程序还会访问哪里

不属于认证模块，列在这里是为了对照「设置 → 安全与隐私 → 网络审计」里看到的主机：

| 主机 | 谁在访问 | 用途 |
| --- | --- | --- |
| `api-drive.<官方根域名>` | PikPak SDK | 文件列表、详情、离线任务、播放记录 |
| `user.<官方根域名>` 的 `/v1/shield/captcha/init`、`/v1/user/me` 等 | PikPak SDK | 文件接口要求的验证令牌（带签名，不含密码）、账号资料、域名测速 |
| 各 `dl-*`、`vod-*` 等边缘节点（在官方根域名下） | PikPak SDK | 视频取流、下载、缩略图用的转码流 |
| `*.aliyuncs.com` | PikPak SDK | 上传文件内容（端点由 PikPak 的接口下发） |
| 头像与文件缩略图所在的主机 | 图片加载（Coil） | 显示界面上的图片；地址来自 PikPak 的接口 |
| `127.0.0.1` | mpv（主播放器与缩略图引擎） | 读本机回环代理，不出本机 |

SDK 那一侧的 `captcha/init` 是文件接口的验证（请求里带的是按公开算法算的签名与用户 ID，没有密码）；
登录用的那一次 `captcha/init` 由认证模块发，带账号名。`SessionSmokeTest` 断言了 SDK 从不自己发 `/v1/auth/` 下的请求。
