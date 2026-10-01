"""生成 THIRD_PARTY_DEPENDENCIES.md：程序包里实际带着的每个第三方库的版本、许可、用途与联网能力。

    gw.cmd :desktopApp:listRuntimeDependencies
    python docs/make_dependency_list.py <构建目录>/desktopApp/runtime-dependencies.txt <Gradle 用户目录>

版本取自 Gradle 解析后的结果，许可读各库随包发布的 POM（本机 Gradle 缓存里就有，不联网）。
用途与联网能力是按库逐类写在下面的表里的：新加了依赖而这里没有归类，生成的清单会把它标成「未归类」，要人补上。
只用 Python 标准库。
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# (坐标前缀, 用途, 联网能力)。从上往下第一条匹配的生效。
# 联网能力三档：
#   无            库里没有发起网络连接的代码
#   被动          有网络代码，但只在程序让它连的时候连、连到程序给的地址
#   主动          会自己决定去连某个地址（遥测、更新检查、崩溃上报之类）——本清单里不应该出现
RULES = [
    ("io.github.nihildigit:pikpak-kotlin", "PikPak 接口：文件、离线任务、取流。认证不经它", "被动：只连 PikPak 官方域名与其下发的直链、上传端点"),
    ("dev.nihildigit:compose-windows-touch", "Windows 触摸与笔输入接入界面", "无"),
    ("org.openani.mediamp:mediamp-mpv", "播放器：libmpv 的绑定与 D3D11 零拷贝画面", "被动：mpv 读程序交给它的地址，正常情况下只有本机回环代理"),
    ("org.openani.mediamp", "播放器的公共接口与界面表面", "无"),
    ("io.ktor:ktor-client", "PikPak SDK 用的 HTTP 客户端", "被动"),
    ("io.ktor:ktor-network", "本机回环代理的服务端（只监听 127.0.0.1）", "被动：只在本机回环上收连接"),
    ("io.ktor:ktor-serialization", "Ktor 的 JSON 支持", "无"),
    ("io.ktor:ktor-http", "HTTP 的类型与解析", "无"),
    ("io.ktor:ktor-websocket", "Ktor 的传递依赖，程序不用 WebSocket", "被动（未使用）"),
    ("io.ktor:ktor-sse", "Ktor 的传递依赖，程序不用 SSE", "被动（未使用）"),
    ("io.ktor:", "Ktor 的基础工具", "无"),
    ("com.squareup.okhttp3", "HTTP 引擎：Ktor 与图片加载都经它出网", "被动"),
    ("com.squareup.okio", "字节流与文件读写", "无"),
    ("io.coil-kt.coil3:coil-network", "图片加载的取图部分", "被动：取界面要显示的缩略图与头像"),
    ("io.coil-kt.coil3", "图片加载、解码与缓存", "无"),
    ("org.jetbrains.compose", "界面框架 Compose Multiplatform", "无"),
    ("org.jetbrains.androidx", "界面框架的组成部分（导航、生命周期等）", "无"),
    ("androidx.", "界面框架的组成部分（导航、生命周期、集合等）", "无"),
    ("org.jetbrains.skiko", "界面渲染（Skia）；缩略图雪碧图的 WebP 编解码", "无"),
    ("org.jetbrains.kotlinx:kotlinx-coroutines", "协程", "无"),
    ("org.jetbrains.kotlinx:kotlinx-serialization", "JSON 序列化", "无"),
    ("org.jetbrains.kotlinx:kotlinx-datetime", "日期时间", "无"),
    ("org.jetbrains.kotlinx:kotlinx-io", "字节流", "无"),
    ("org.jetbrains.kotlinx:atomicfu", "原子操作", "无"),
    ("org.jetbrains.kotlinx", "Kotlin 官方扩展库", "无"),
    ("org.jetbrains.kotlin", "Kotlin 标准库", "无"),
    ("org.jetbrains.runtime:jbr-api", "JetBrains 运行时的接口声明（界面框架的传递依赖）", "无"),
    ("org.jetbrains:annotations", "编译期注解", "无"),
    ("org.jspecify", "编译期注解", "无"),
    ("com.googlecode.mp4parser", "MP4 无损切片（截取片段）", "无"),
    ("org.kotlincrypto", "哈希算法（MD5、SHA），PikPak 的内容哈希与验证签名用", "无"),
    ("net.java.dev.jna", "调用 Windows 原生接口（MediaMP 用）", "无"),
    ("org.slf4j", "日志接口（依赖库用），输出只到本机", "无"),
    ("com.github.luben:zstd-jni", "zstd 解压", "无"),
]

NOTABLE = {
    "org.openani.mediamp": "随它带着 libmpv 与 FFmpeg 的 Windows 原生库（见下面「原生库」一节）",
}


def classify(coordinate):
    for prefix, purpose, network in RULES:
        if coordinate.startswith(prefix):
            return purpose, network
    return "未归类，请补上", "未归类，请核对"


def pom_of(cache, group, name, version):
    directory = cache / "caches" / "modules-2" / "files-2.1" / group / name / version
    if not directory.is_dir():
        return None
    for pom in directory.glob("*/*.pom"):
        return pom
    return None


def licenses_in(pom):
    try:
        root = ET.parse(pom).getroot()
    except ET.ParseError:
        return []
    names = []
    for element in root.iter():
        if element.tag.endswith("}license") or element.tag == "license":
            for child in element:
                if child.tag.endswith("name") and child.text:
                    names.append(child.text.strip())
    return names


def parent_of(pom):
    try:
        root = ET.parse(pom).getroot()
    except ET.ParseError:
        return None
    for element in root:
        if element.tag.endswith("parent"):
            fields = {child.tag.split("}")[-1]: (child.text or "").strip() for child in element}
            if fields.get("groupId") and fields.get("artifactId") and fields.get("version"):
                return fields["groupId"], fields["artifactId"], fields["version"]
    return None


def license_of(cache, group, name, version, depth=0):
    """POM 里写的许可；自己没写就看父 POM，Kotlin 多平台库的平台构件还要看去掉后缀的那个。"""
    pom = pom_of(cache, group, name, version)
    if pom is not None:
        found = licenses_in(pom)
        if found:
            return found
        parent = parent_of(pom)
        if parent and depth < 4:
            found = license_of(cache, *parent, depth=depth + 1)
            if found:
                return found
    for suffix in ("-desktop", "-jvm", "-windows-x64", "-jvmstubs"):
        if name.endswith(suffix) and depth < 4:
            found = license_of(cache, group, name[: -len(suffix)], version, depth=depth + 1)
            if found:
                return found
    return []


def short(license_name):
    text = license_name.lower()
    if "apache" in text:
        return "Apache-2.0"
    if "mit" in text:
        return "MIT"
    if "gnu general public license" in text and "lesser" not in text or text.startswith("gpl"):
        return "GPL-3.0" if "3" in text else "GPL"
    if "lesser" in text or "lgpl" in text:
        return "LGPL-2.1+" if "2.1" in text else "LGPL"
    if "bsd" in text:
        return "BSD"
    return license_name


def main():
    listing = Path(sys.argv[1])
    cache = Path(sys.argv[2])
    rows = []
    for line in listing.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        group, name, version = line.split(":")
        found = license_of(cache, group, name, version)
        license_text = "、".join(dict.fromkeys(short(item) for item in found)) or "POM 未写明"
        purpose, network = classify(f"{group}:{name}")
        rows.append((f"{group}:{name}", version, license_text, purpose, network))

    unknown = [row for row in rows if "未归类" in row[3]]
    active = [row for row in rows if row[4].startswith("主动")]
    out = []
    out.append("# 第三方依赖清单")
    out.append("")
    out.append("> 由 `docs/make_dependency_list.py` 生成，不要手改。列的是打进程序包的每一个第三方库：")
    out.append("> 版本是 Gradle 解析后实际用的版本，许可读自各库随包发布的 POM。")
    out.append("")
    out.append(f"共 {len(rows)} 个库。会**主动**联网的（遥测、更新检查、崩溃上报、广告）：**{len(active)} 个**。未归类：{len(unknown)} 个。")
    out.append("")
    out.append("「联网能力」的三档：")
    out.append("")
    out.append("- **无**：库里没有发起网络连接的代码。")
    out.append("- **被动**：有网络代码，但只在程序让它连的时候连、连到程序给它的地址。程序给的地址只有 PikPak 官方域名、")
    out.append("  PikPak 下发的直链与上传端点，以及本机回环。")
    out.append("- **主动**：会自己决定去连某个地址。这份清单里不应该有；有的话上面的数字不是 0。")
    out.append("")
    out.append("PikSeek 自己的认证模块（`auth/`）不在表里：它不用任何第三方网络库，用的是 JDK 自带的 `java.net.http`。")
    out.append("")
    out.append("## Java 库")
    out.append("")
    out.append("| 库 | 版本 | 许可 | 用途 | 联网能力 |")
    out.append("| --- | --- | --- | --- | --- |")
    for coordinate, version, license_text, purpose, network in rows:
        out.append(f"| `{coordinate}` | {version} | {license_text} | {purpose} | {network} |")
    out.append("")
    out.append("## 原生库与运行时")
    out.append("")
    out.append("| 组件 | 来源 | 许可 | 用途 | 联网能力 |")
    out.append("| --- | --- | --- | --- | --- |")
    out.append("| libmpv 0.41.0（`libmpv-2.dll`）、FFmpeg 8.0.1（`avcodec`、`avformat` 等）及其依赖的 DLL | MediaMP 的 `mediamp-mpv-runtime-windows-x64` 0.5.0，原样解到 `app/resources/mpv/` | mpv：LGPL-2.1+（这份构建带 `-Dgpl=false`，可在程序里问 mpv 的 `mpv-configuration` 属性核对）；FFmpeg：LGPL-2.1+；另有 libass（ISC）、FreeType、HarfBuzz、OpenSSL（Apache-2.0）、libplacebo（LGPL-2.1+）等 | 视频解码与播放；缩略图引擎另开一个无画面的 mpv 实例取帧 | 被动：读程序交给它的地址。主播放器读本机回环代理；缩略图引擎读本机临时文件或本机回环代理 |")
    out.append("| Skia（`skiko-windows-x64.dll`） | JetBrains skiko | BSD-3-Clause（Skia）、Apache-2.0（skiko） | 界面渲染、WebP 编解码 | 无 |")
    out.append("| Java 运行时（`runtime/`） | Azul Zulu JDK 25，经 jlink 裁剪 | GPL-2.0 with Classpath Exception | 运行程序。认证模块用其中的 `java.net.http` | 被动 |")
    out.append("")
    out.append("## 说明")
    out.append("")
    out.append("- PikSeek 的文件管理、取流与播放器部分取自 [Piko](https://github.com/NihilDigit/piko)（MIT，Copyright (c) 2026 NihilDigit），")
    out.append("  许可文本随程序放在 `app/resources/licenses/LICENSE.piko`。")
    out.append("- 程序包里的 mpv 与 FFmpeg 是 LGPL 许可的动态库，没有改动、没有静态链接，可以整个换成自己编译的同版本 DLL。")
    out.append("  对应源码：<https://github.com/mpv-player/mpv>（v0.41.0）、<https://ffmpeg.org>（8.0.1），构建脚本在 <https://github.com/open-ani/mediamp>。")
    out.append("  PikSeek 自己的源码在 `30_code/pikseek/`。")
    out.append("- 自动更新、遥测、统计、崩溃上报、广告：一个都没有集成。程序运行时还会按类名再查一遍类路径（设置 → 安全与隐私）。")
    out.append("")
    (ROOT / "THIRD_PARTY_DEPENDENCIES.md").write_text("\n".join(out), encoding="utf-8", newline="\n")
    print(f"{len(rows)} libraries, {len(unknown)} unclassified, {len(active)} active-network")
    for row in unknown:
        print("  unclassified:", row[0])
    missing = [row[0] for row in rows if row[2] == "POM 未写明"]
    for name in missing:
        print("  no license in POM:", name)


if __name__ == "__main__":
    main()
