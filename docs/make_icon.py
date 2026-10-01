"""按 docs/icon.svg 的同一组坐标画出程序图标，只用 Python 标准库。

    python docs/make_icon.py

写出两个文件（路径相对仓库根目录）：
    desktopApp/package/windows/icon.ico                 exe 与任务栏图标，256/128/64/48/32/16
    desktopApp/src/desktopMain/resources/app-icon.png   窗口图标，256 px

图形改了要三处一起改：docs/icon.svg、ui 模块的 PikoBrandIcons、这里。
"""

import math
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VIOLET = (0x67, 0x50, 0xF5)
SUPERSAMPLE = 4  # 每个像素 4x4 个采样点


def rounded_rect(px, py, cx, cy, hx, hy, r):
    """点到圆角矩形的有符号距离，内部为负。"""
    qx = abs(px - cx) - (hx - r)
    qy = abs(py - cy) - (hy - r)
    outside = math.hypot(max(qx, 0.0), max(qy, 0.0))
    return outside + min(max(qx, qy), 0.0) - r


def segment(px, py, ax, ay, bx, by):
    """点到线段的距离。"""
    dx, dy = bx - ax, by - ay
    t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def polygon(px, py, points):
    """点到多边形的有符号距离，内部为负。"""
    inside = False
    best = 1e9
    count = len(points)
    for i in range(count):
        ax, ay = points[i]
        bx, by = points[(i + 1) % count]
        best = min(best, segment(px, py, ax, ay, bx, by))
        if (ay > py) != (by > py) and px < (bx - ax) * (py - ay) / (by - ay) + ax:
            inside = not inside
    return -best if inside else best


PLAY = [(21.6, 13.4), (27.8, 17.0), (21.6, 20.6)]
TAIL = [(21.0, 25.6), (27.0, 25.6), (24.0, 29.2)]


def sample(x, y):
    """48x48 坐标系里一个点的颜色：(r, g, b, a)，a 为 0 或 1。"""
    if rounded_rect(x, y, 24, 24, 24, 24, 12) > 0:
        return (0, 0, 0, 0.0)
    white = 0.0
    if segment(x, y, 9, 35.5, 39, 35.5) <= 1.4:
        white = 0.45
    if (
        abs(rounded_rect(x, y, 24, 17, 12, 8, 3)) <= 1.3
        or polygon(x, y, PLAY) <= 0.6
        or polygon(x, y, TAIL) <= 0.0
        or segment(x, y, 9, 35.5, 24, 35.5) <= 1.4
        or math.hypot(x - 24, y - 35.5) <= 3.6
    ):
        white = 1.0
    r, g, b = (round(c + (255 - c) * white) for c in VIOLET)
    return (r, g, b, 1.0)


def render(size):
    """size x size 的 RGBA 行列表，预乘之前的直通 alpha。"""
    scale = 48.0 / size
    step = 1.0 / SUPERSAMPLE
    rows = []
    for py in range(size):
        row = bytearray()
        for px in range(size):
            total = [0.0, 0.0, 0.0, 0.0]
            for sy in range(SUPERSAMPLE):
                for sx in range(SUPERSAMPLE):
                    r, g, b, a = sample((px + (sx + 0.5) * step) * scale, (py + (sy + 0.5) * step) * scale)
                    total[0] += r * a
                    total[1] += g * a
                    total[2] += b * a
                    total[3] += a
            if total[3] == 0:
                row += b"\x00\x00\x00\x00"
            else:
                alpha = total[3] / (SUPERSAMPLE * SUPERSAMPLE)
                row += bytes((round(total[0] / total[3]), round(total[1] / total[3]), round(total[2] / total[3]), round(alpha * 255)))
        rows.append(bytes(row))
    return rows


def png(rows, size):
    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    raw = b"".join(b"\x00" + row for row in rows)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def dib(rows, size):
    """ICO 里的小尺寸用 32 位 DIB：自下而上的 BGRA，后面跟一份全 0 的 1 位掩码。"""
    header = struct.pack("<IiiHHIIiiII", 40, size, size * 2, 1, 32, 0, 0, 0, 0, 0, 0)
    pixels = bytearray()
    for row in reversed(rows):
        for i in range(0, len(row), 4):
            r, g, b, a = row[i : i + 4]
            pixels += bytes((b, g, r, a))
    mask_row = bytes(((size + 31) // 32) * 4)
    return header + bytes(pixels) + mask_row * size


def ico(images):
    """images: [(size, 数据)]。256 的那张是 PNG，其余是 DIB。"""
    out = struct.pack("<HHH", 0, 1, len(images))
    offset = 6 + 16 * len(images)
    for size, data in images:
        out += struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
    return out + b"".join(data for _, data in images)


def main():
    rendered = {size: render(size) for size in (256, 128, 64, 48, 32, 16)}
    images = [(256, png(rendered[256], 256))] + [(size, dib(rendered[size], size)) for size in (128, 64, 48, 32, 16)]
    (ROOT / "desktopApp/package/windows/icon.ico").write_bytes(ico(images))
    (ROOT / "desktopApp/src/desktopMain/resources/app-icon.png").write_bytes(png(rendered[256], 256))
    (ROOT / "docs/icon.png").write_bytes(png(rendered[256], 256))
    print("icon.ico, app-icon.png, docs/icon.png written")


if __name__ == "__main__":
    main()
