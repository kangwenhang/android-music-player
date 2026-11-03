# -*- coding: utf-8 -*-
"""把控制栏的白色 PNG 图标做成日夜两套(v6.4 昼夜主题)。

背景:ic_mode_seq/one/shuf、ic_eq_bars 当初为夜间深色主题画的是纯白描边。
v6.4 加了日间浅色主题后,白色图标贴在浅灰按钮(#E8EAF0)上几乎不可见。

做法:
  1) 白色原版移到 drawable-night-mdpi / drawable-night-xhdpi(夜间继续用);
  2) 默认 drawable-mdpi / drawable-xhdpi 放染成深色的版本(日间用),
     白色像素 -> #3A3F4C(与 btn_text/eq_preset_text 同色系),黑色像素保留。

Android 资源系统按 uiMode 自动挑 drawable-night 版本,Java 一行不用改;
主题切换时 appcompat recreate Activity,重新解析资源即自动换图标。

PNG 处理用纯 zlib 手写(环境无 PIL):解 IDAT -> 改像素 -> filter 0 重压回写。
"""
import os
import shutil
import struct
import zlib

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "app", "src", "main", "res")

# 日间图标色:深灰蓝,和 btn_text(#1A1D26)/eq_preset_text(#3A3F4C) 同族
DAY_RGB = (0x3A, 0x3F, 0x4C)


def _paeth(a, b, c):
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def read_png(path):
    d = open(path, "rb").read()
    assert d[:8] == b"\x89PNG\r\n\x1a\n", path
    pos, idat = 8, b""
    while pos < len(d):
        ln = struct.unpack(">I", d[pos:pos + 4])[0]
        typ = d[pos + 4:pos + 8]
        if typ == b"IHDR":
            w, h, bd, ct = struct.unpack(">IIBB", d[pos + 8:pos + 18])
            assert bd == 8 and ct == 6, "%s: 仅支持 8bit RGBA" % path
        elif typ == b"IDAT":
            idat += d[pos + 8:pos + 8 + ln]
        pos += 12 + ln
    raw = zlib.decompress(idat)
    stride = w * 4
    rows = []
    prev = bytearray(stride)
    for y in range(h):
        ft = raw[y * (stride + 1)]
        cur = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        if ft == 1:      # Sub
            for i in range(4, stride):
                cur[i] = (cur[i] + cur[i - 4]) & 0xFF
        elif ft == 2:    # Up
            for i in range(stride):
                cur[i] = (cur[i] + prev[i]) & 0xFF
        elif ft == 3:    # Average
            for i in range(stride):
                left = cur[i - 4] if i >= 4 else 0
                cur[i] = (cur[i] + (left + prev[i]) // 2) & 0xFF
        elif ft == 4:    # Paeth
            for i in range(stride):
                left = cur[i - 4] if i >= 4 else 0
                ul = prev[i - 4] if i >= 4 else 0
                cur[i] = (cur[i] + _paeth(left, prev[i], ul)) & 0xFF
        rows.append(cur)
        prev = cur
    return w, h, rows


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


def tint_rows(rows, rgb):
    out = []
    for row in rows:
        r = row[:]
        for i in range(0, len(r), 4):
            if r[i + 3] > 0 and r[i] > 200 and r[i + 1] > 200 and r[i + 2] > 200:
                r[i], r[i + 1], r[i + 2] = rgb
        out.append(r)
    return out


JOBS = [
    ("drawable-mdpi/ic_mode_seq.png",  "drawable-night-mdpi"),
    ("drawable-mdpi/ic_mode_one.png",  "drawable-night-mdpi"),
    ("drawable-mdpi/ic_mode_shuf.png", "drawable-night-mdpi"),
    ("drawable-mdpi/ic_eq_bars.png",   "drawable-night-mdpi"),
    ("drawable-xhdpi/ic_eq_bars.png",  "drawable-night-xhdpi"),
]

for rel, night_dir in JOBS:
    src = os.path.normpath(os.path.join(RES, rel))
    dst_dir = os.path.normpath(os.path.join(RES, night_dir))
    os.makedirs(dst_dir, exist_ok=True)

    # 1) 白色原版 -> drawable-night-*(已存在则跳过,避免重复运行覆盖掉深色版)
    night_path = os.path.join(dst_dir, os.path.basename(rel))
    w, h, rows = read_png(src)
    if not os.path.exists(night_path):
        shutil.copyfile(src, night_path)
        print("[夜间] %-28s -> %s  (%dx%d 白色原版)" % (rel, night_dir, w, h))
    else:
        print("[夜间] %-28s 已存在,跳过" % rel)

    # 2) 默认目录染成日间深色版
    tinted = tint_rows(rows, DAY_RGB)
    write_png(src, w, h, tinted)
    print("[日间] %-28s 染成 #%02X%02X%02X" % (rel, DAY_RGB[0], DAY_RGB[1], DAY_RGB[2]))

print("完成")
