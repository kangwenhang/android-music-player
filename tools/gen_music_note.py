# -*- coding: utf-8 -*-
"""把标题栏音符 ic_music_note 从矢量改为日夜两套真 PNG。

背景:项目 minSdk 17 < 21,AAPT2 会把 drawable/ 下的 <vector> 预渲染成
只按日间配色(灰 #8A90A0)的 PNG(生成到 drawable-*-v4),在 API 17 车机上
资源解析优先命中这张死 PNG —— 夜间模式标题栏变深色后,音符还是日间灰色,
几乎不可见。ic_mode_*/ic_eq_bars 当初已是 PNG 所以没这问题。

做法:删掉 drawable/ic_music_note.xml,直接发 4 张真 PNG:
  drawable-mdpi/ic_music_note.png   24px  日间 #3A3F4C(与其它日间图标同色)
  drawable-xhdpi/ic_music_note.png  48px  日间 #3A3F4C
  drawable-night-mdpi/...           24px  夜间 #FFFFFF
  drawable-night-xhdpi/...          48px  夜间 #FFFFFF

矢量路径(viewport 24)可精确分解为三个子形状的并集:
  pathData = "M12,3 v10.55 c...(圆) ... V7 h4 V3 h-6 z"
  1) 顶部横杠矩形  x[12,18] y[3,7]
  2) 符干矩形      x[12,14] y[3,17](下半段并入圆,不会越界)
  3) 符头圆        圆心(10,17) 半径 4
渲染用 8x 超采样 + 区域平均做抗锯齿,与矢量渲染观感一致。
"""
import os
import struct
import zlib

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "app", "src", "main", "res")

DAY_RGB = (0x3A, 0x3F, 0x4C)
NIGHT_RGB = (0xFF, 0xFF, 0xFF)

# 形状参数(viewport 24 坐标系)
CIRCLE = (10.0, 17.0, 4.0)          # cx, cy, r
RECT_TOP = (12.0, 3.0, 18.0, 7.0)   # x0, y0, x1, y1
RECT_STEM = (12.0, 3.0, 14.0, 17.0)

SS = 8  # 超采样倍数


def render(size, rgb):
    """渲染 size x size RGBA(透明底),viewport 24 线性缩放。"""
    scale = size / 24.0
    rows = []
    for py in range(size):
        row = bytearray()
        for px in range(size):
            vx0 = px / scale
            vy0 = py / scale
            cov = _coverage_viewport(vx0, vy0, 1.0 / scale)
            a = int(round(cov * 255))
            row += bytes((rgb[0], rgb[1], rgb[2], a))
        rows.append(row)
    return rows


def _coverage_viewport(vx0, vy0, vw):
    """viewport 坐标下边长 vw 的小方块覆盖率(SS*SS 采样)。"""
    hit = 0
    step = vw / SS
    for sy in range(SS):
        y = vy0 + (sy + 0.5) * step
        for sx in range(SS):
            x = vx0 + (sx + 0.5) * step
            inside = False
            cx, cy, r = CIRCLE
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                inside = True
            for (x0, y0, x1, y1) in (RECT_TOP, RECT_STEM):
                if x0 <= x <= x1 and y0 <= y <= y1:
                    inside = True
            if inside:
                hit += 1
    return hit / float(SS * SS)


def write_png(path, w, h, rows):
    def chunk(typ, data):
        return (struct.pack(">I", len(data)) + typ + data
                + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF))

    raw = b"".join(b"\x00" + bytes(r) for r in rows)
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


JOBS = [
    ("drawable-mdpi", 24, DAY_RGB),
    ("drawable-xhdpi", 48, DAY_RGB),
    ("drawable-night-mdpi", 24, NIGHT_RGB),
    ("drawable-night-xhdpi", 48, NIGHT_RGB),
]

for rel, size, rgb in JOBS:
    d = os.path.normpath(os.path.join(RES, rel))
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, "ic_music_note.png")
    rows = render(size, rgb)
    write_png(p, size, size, rows)
    print("[OK] %-46s %dx%d #%02X%02X%02X" % (os.path.relpath(p, RES), size, size,
                                              rgb[0], rgb[1], rgb[2]))

print("完成")
