# 播放性能：做了什么、没做什么

基准是 Piko。PikSeek 的取流与播放器就是 Piko 的那一套（见 `PIKO_RESEARCH.md`），所以起点是「与 Piko 相同」；
这份文档只记在它之上的改动、量法，以及明确没做的。数字见 `BENCHMARK_CURRENT.md`。

## 怎么量

`PerformanceMetrics`（`core` 模块）在这些地方记时，只在本机内存里：

| 指标 | 起止 | 记在哪 |
| --- | --- | --- |
| `media_api` | 查文件详情（各清晰度、直链）的一次 API 往返；用上了预取的描述时备注 `prefetched`，接近 0 | `PikoMediaRepository.preparePlayback` |
| `media_url` | 详情到手后建好读取句柄与本机代理会话 | 同上 |
| `mpv_load` | 从点开到把地址交给 mpv | `PlayerScreenState.prepare` |
| `first_frame` | 从点开到第一帧 | `PlayerScreenState.onBackendEvent(Ready)` |
| `seek` | 拖动到画面重新走起来 | `PlayerScreenState.watchSeek` |
| `switch` | 换集到新的一集出第一帧 | `PlayerScreenState.switchTo` → `Ready` |

看：设置 → 播放预览 → 性能浮层，播放窗口左上角实时显示，另有 CDN 主机、丢帧数、预览进度。
导出：设置 → 播放预览 → 导出性能样本，得到每个指标的次数、中位数、最小、最大与逐条样本。

## 在 Piko 之上做的

### 1. 下一条的描述预取

这一集出第一帧 4 秒后，把下一条与再下一条的文件详情查好，留在内存里 5 分钟（`prefetchDescriptor`）。
点下一条时 `preparePlayback` 直接用，省掉一次 API 往返——换集从「查详情 → 取直链 → 建代理 → mpv」
变成「建代理 → mpv」。

只取描述：元数据、直链、时长。不读视频数据。只在内存里，过期作废；带签名的地址不落盘。
设置里可以关（「预先准备下一条」）。

复用的是 Piko 已有的 `freshDetails`（它原本只给信息流用），所以改动很小。

### 2. 拖动时不乱跳

Piko 原本是「拖动中只预览时间，松手才跳」。PikSeek 加了预览图，并让主画面在慢拖时低频地跟（自适应，默认），
快速扫过时一次都不跳。见 `THUMBNAIL_PLAN.md` 的「界面」一节与 `DragSeekPolicy`。

### 3. 缩略图不拖慢播放

缩略图引擎在第一帧之后才启动，读取走后台优先级，主播放器缓冲、拖动时让路。见 `THUMBNAIL_PLAN.md`。

## 没做的，以及为什么

### 双播放器实例预热（ActivePlayer + StandbyPlayer）

需求里写的是「必须 benchmark 后决定；显著增加 GPU / 内存就不要强行做」。**没有做，也没有量。**

量不了：开发机是没有显卡的虚拟机，播放器的 D3D11 画面走的是软件适配器，在上面量显存与初始化时间没有意义。
没有数据就不做这个决定。要做的话在宿主机上量三样：第二个 mpv 实例加画面表面的显存与内存、
它空闲时的功耗、以及「已初始化的实例 load」比「新建实例 load」省下的毫秒数（就是 `switch` 减去 `media_api` 之后剩下的那部分）。

已经拿到的一个相关数字：缩略图引擎用的无画面 mpv 实例，建出来加打开本机文件到第一帧是 67–142 毫秒（软件解码、不建渲染上下文）。
带画面的实例要另外量。

### 更激进的并发与缓存参数

SDK 的连接预算（每文件 8、每账号 16）、块大小（256 KiB）、预读（32 MiB）都是作者在线上量出来的，注释里有日期与数据。
没有账号重新量，就没有理由改。原样用。

### 换 CDN

SDK 已经在做（`HostHealth`：慢节点换成量过更快的兄弟节点）。没有再加一层。

## 要在有账号的机器上补的

1. 同一台机器、同一个账号、同一批视频，先用 Piko 量一遍填进 `BASELINE_PIKO.md`；
2. 用 PikSeek 量同一批，导出性能样本填进 `BENCHMARK_CURRENT.md`；
3. 对比 `first_frame`、`seek`、`switch`。预期：前两项与 Piko 持平（同一套取流与播放器），
   `switch` 在下一条已预取时少一次 API 往返的时间。**这是预期，不是测量结果。**
