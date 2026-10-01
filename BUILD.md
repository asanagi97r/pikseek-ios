# 构建

只在 Windows x64 上构建、只出 Windows x64 的包（jpackage 不能交叉打包）。

## 工具都在项目里

构建不往系统里装任何东西，也不往用户目录写：全部在项目的 `_runtime/pikseek/` 下（这个目录不进 git，可以整个删掉重来）。

| 目录 | 内容 | 来源 | 体积 |
| --- | --- | --- | --- |
| `_runtime/pikseek/jdk/` | Azul Zulu JDK 25.0.4.1（带 jmods） | <https://cdn.azul.com/zulu/bin/zulu25.36.205-ca-jdk25.0.4.1-win_x64.zip>，SHA-256 `5e91bc55…5826`（Azul 元数据接口给的，下载后核过） | 380 MB |
| `_runtime/pikseek/dl/` | 上面那个 zip 的原件（留着重装用，可删） | 同上 | 220 MB |
| `_runtime/pikseek/g/` | Gradle 9.6.1 与全部依赖的缓存（`GRADLE_USER_HOME`） | services.gradle.org、Maven Central、Google Maven、JetBrains 的 Compose 仓库 | 720 MB |
| `_runtime/pikseek/b/` | 构建产物（各模块的 build 目录都指到这里，源码目录里不留产物） | — | 730 MB |
| `_runtime/pikseek/h/` | 构建进程的「用户目录」与 `AppData`（Kotlin 守护进程、测试进程往这里写） | — | 23 MB |
| `_runtime/pikseek/t/` | 构建进程的临时目录、日志、自检结果 | — | 几 MB |

体积是 2026-10-01 量的。

重装 JDK：把上面那个 zip 解开，文件夹改名为 `jdk` 放到 `_runtime/pikseek/` 下。必须是带 jmods 的发行版
（Temurin 25 不带，ProGuard 会读不到 `java.lang.Object`）。其余的 Gradle 会自己下回来。

`gw.cmd` 是入口：它把 `JAVA_HOME`、`GRADLE_USER_HOME`、`TEMP`、`LOCALAPPDATA`、`APPDATA` 与 JVM 的 `user.home`
都指到上面这些目录，只对本进程生效，不改系统设置。直接用 `gradlew.bat` 的话这些都会落到用户目录里。

## 常用命令

在 `30_code/pikseek/` 下：

```bat
.\gw.cmd :desktopApp:compileKotlinDesktop
```

```bat
.\gw.cmd :core:desktopTest :auth:desktopTest :thumbnail:desktopTest :shared:desktopTest :desktopApp:desktopTest
```

```bat
.\gw.cmd :desktopApp:createReleaseDistributable
```

打包产物在 `_runtime/pikseek/b/desktopApp/compose/binaries/main-release/app/PikSeek/`。打包途中会弹出程序窗口约 12 秒
（JDK 的 AOT 缓存训练），它自己会退出；训练用的数据目录在构建的临时目录里，不会留在包里。

打包时对 jpackage 出的启动器多做一步：`desktopApp/package/windows/utf8-manifest.ps1` 给 `PikSeek.exe` 的清单加上
`activeCodePage=UTF-8`。不加的话，程序放在系统代码页写不出的文件夹里（英文系统上的中文文件夹名）会找不到自带的运行时，
退出码 2、没有窗口。这一步挂在 AOT 训练之前，日志里有一行 `utf8-manifest: set on PikSeek.exe`。
不要在打包产物的文件夹里手动双击启动：会在里面留下 `data/`，`release.ps1` 见到就拒绝出包。

版本号：`-PpikseekVersion=1.0.1`（默认 1.0.0；jpackage 不接受 0.x）。

开发时直接跑：`.\gw.cmd :desktopApp:run`。数据目录是 `_runtime/pikseek/h/.pikseek`。

用完把构建的后台进程停掉（三个 java 进程，各占几百 MB 内存）：

```bat
.\gw.cmd --stop
```

## 自检

对打好的包（不联网、不登录，用单独的数据目录）：

```bat
powershell -ExecutionPolicy Bypass -File selftest.ps1 -App <PikSeek 文件夹>
```

三项：`security`（DPAPI、白名单、遥测检查、明文扫描）、`preview`（缩略图生成与缓存）、`play`（主播放器开窗播放样片），
加上可选的 `-LongVideo <本机的一个长视频>` 量预览生成的耗时。全部通过时退出码为 0。
`-App` 与 `-Out` 的路径可以带空格和中文——出包前值得在这样的路径下跑一遍。

## 出发行包

1. 跑全部测试；
2. `createReleaseDistributable`；
3. `selftest.ps1`；
4. 出包：

```bat
powershell -ExecutionPolicy Bypass -File release.ps1 -Name v1.0.0_20261001_PikSeek
```

它把 `PikSeek/` 整个文件夹拷到项目的 `80_release/<名字>/`，压一份 zip（条目名用正斜杠），写 `SHA256.txt`。
同名的发行目录已存在、或打包产物里有 `data/` 时拒绝执行。同一份打包产物出两次，zip 的 SHA-256 相同。

zip 只存本地时间、精确到 2 秒。在别的时区解压，jar 的修改时间会变，AOT 缓存因此作废——程序照常运行，只是启动慢一些。
直接拷文件夹没有这个问题。

## 改了依赖之后

```bat
.\gw.cmd :desktopApp:listRuntimeDependencies
```

```bat
python docs\make_dependency_list.py ..\..\_runtime\pikseek\b\desktopApp\runtime-dependencies.txt ..\..\_runtime\pikseek\g
```

重新生成 `THIRD_PARTY_DEPENDENCIES.md`。输出里的「unclassified」要是不为 0，在脚本的 `RULES` 里补上那个库的用途与联网能力。

## 改图标

改 `docs/icon.svg`、`ui` 模块的 `PikoBrandIcons`、`docs/make_icon.py` 三处（同一组坐标），然后：

```bat
python docs\make_icon.py
```

## 注意

- 界面库钉在 Compose 1.12.0、MediaMP 0.5.0，原因见 `docs/PIKO_RESEARCH.md`。升级前先确认播放器还能开。
- ProGuard 只裁剪不混淆。经 FFM 的调用（`MethodHandle.invoke`）ProGuard 认不出签名，规则里已对这两个类关了告警。
- 新加的经反射、ServiceLoader 或原生回调才用到的类要在 `desktopApp/proguard-rules.pro` 里写 keep，
  否则只在打好的包里悄悄失效——所以每次打包后都跑自检。
