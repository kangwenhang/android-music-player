# -*- coding: utf-8 -*-
"""把 512x512 的播放列表源图做成控制栏用的日/夜两套 PNG 图标。

背景:v6.4 起控制栏位图图标统一两套 —— 白天深色(#3A3F4C,与 btn_text 同族)
放 drawable-mdpi / drawable-xhdpi,夜间白色放 drawable-night-mdpi /
drawable-night-xhdpi;主题切换由资源系统按 uiMode 自动挑,Java 一行不用改
(与 tools/tint_icons.py 同一套约定,只是这个图标要缩放,不能直接染色)。

源图特点:
  - 512x512 画布,但内容只占 bbox(43,70)-(468,404),四周留白极不对称;
  - 纯色 (#292929) + alpha,色彩无层次 —— 所以只需要把 **alpha 通道** 缩放,
    再统一填目标色,避免缩放时半透明边缘混出深/浅描边(俗称黑边)。

做法:
  1) 取 alpha 通道 -> 裁到内容 bbox -> LANCZOS 缩小到目标内容框;
  2) 在 24x24 / 48x48 画布里垂直居中放置(内容宽高比 1.27,天生扁);
  3) RGB 全部填目标色,alpha 低于阈值归零(去掉 LANCZOS 拖尾的雾状像素)。

用法:
  python tools/make_playlist_icon.py <源图路径>
  默认源图取 ~/Downloads/playlist_icon_227431.png
"""
import os
import sys

from PIL import Image

RES = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                    "..", "app", "src", "main", "res"))

# mdpi 32x32 / xhdpi 64x64 —— 比控制栏其它图标(24dp)大一档:
# 这张源图的墨迹细(3 条 42/512 的横条 + 一个三角),压到 24px 时横条只剩 2px、
# 三角左缘会被裁,实机(1024x600)看上去像三条头发丝。放大到 32dp 后横条 3px,
# 与相邻的 ic_mode_seq / ic_eq_bars 视觉重量仍协调(见 2026-11-22 决策)。
SIZES = [("drawable-mdpi", 32), ("drawable-xhdpi", 64)]

DAY_RGB = (0x3A, 0x3F, 0x4C)      # 白天:深灰蓝,与 btn_text 同族
NIGHT_RGB = (0xFF, 0xFF, 0xFF)    # 夜间:纯白

# 内容宽度占画布比例:留 1px(mdpi)呼吸位,避免三角左缘被裁
CONTENT_RATIO = 30.0 / 32.0
ALPHA_FLOOR = 8                   # 低于此 alpha 直接透明


def load_alpha(src_path):
    """取源图 alpha 通道并裁到内容 bbox。"""
    im = Image.open(src_path).convert("RGBA")
    alpha = im.split()[3]
    box = alpha.getbbox()
    if box:
        alpha = alpha.crop(box)
    return alpha


def build(alpha, size, rgb):
    """把 alpha 缩放并居中放进 size x size 的方形画布,填 rgb。"""
    cw = int(round(size * CONTENT_RATIO))
    ch = int(round(cw * alpha.size[1] / float(alpha.size[0])))
    if ch > cw:
        ch = cw
        cw = int(round(ch * alpha.size[0] / float(alpha.size[1])))
    small = alpha.resize((cw, ch), Image.LANCZOS, reducing_gap=3.0)

    canvas_alpha = Image.new("L", (size, size), 0)
    canvas_alpha.paste(small, ((size - cw) // 2, (size - ch) // 2))
    # LANCZOS 会在纯透明区留下 1-7 级别的拖尾,阈值清掉,免得贴到浅底上发灰
    canvas_alpha = canvas_alpha.point(lambda v: 0 if v < ALPHA_FLOOR else v)

    out = Image.new("RGBA", (size, size), rgb + (0,))
    out.putalpha(canvas_alpha)
    return out


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser(
        "~/Downloads/playlist_icon_227431.png")
    if not os.path.exists(src):
        raise SystemExit("源图不存在: %s" % src)
    alpha = load_alpha(src)
    print("源图 alpha bbox 内容尺寸: %dx%d" % alpha.size)

    for rel_dir, size in SIZES:
        for night in (False, True):
            rgb = NIGHT_RGB if night else DAY_RGB
            out_dir = os.path.join(RES, rel_dir.replace("drawable-", "drawable-night-")) \
                if night else os.path.join(RES, rel_dir)
            os.makedirs(out_dir, exist_ok=True)
            path = os.path.join(out_dir, "ic_playlist.png")
            build(alpha, size, rgb).save(path, optimize=True)
            print("%-6s %-28s %dx%d -> %s" % (
                "夜间" if night else "白天", rel_dir, size, size,
                os.path.relpath(path, RES)))


if __name__ == "__main__":
    main()
