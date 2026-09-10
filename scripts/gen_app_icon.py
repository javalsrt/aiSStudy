# -*- coding: utf-8 -*-
"""
统一品牌图标生成脚本（App / 管理端共用同一设计）。
SVG 母版：web-admin-react/public/logo.svg
设计：iOS 极简 —— 纯色强调蓝 #0A84FF 圆角方形，学士帽（学+职达）+ AI 星点（智）。
输出：
  1. Android mipmap-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi 的 ic_launcher(.round).png
  2. 管理端 public/favicon.ico
  3. docs/ 预览图
依赖：pip install resvg-py pillow
运行：python scripts/gen_app_icon.py
"""
import io
import os

import resvg_py
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SVG_PATH = os.path.join(ROOT, "web-admin-react", "public", "logo.svg")
RES_DIR = os.path.join(ROOT, "android", "app", "src", "main", "res")
ADMIN_PUBLIC = os.path.join(ROOT, "web-admin-react", "public")
DOCS_DIR = os.path.join(ROOT, "docs")

# 密度 -> 目标尺寸
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def svg_to_png(svg_text: str, size: int) -> Image.Image:
    """将 SVG 文本渲染为指定尺寸的 RGBA PIL 图像"""
    png_bytes = resvg_py.svg_to_bytes(svg_string=svg_text,
                                      width=size, height=size)
    return Image.open(io.BytesIO(bytes(png_bytes))).convert("RGBA")


def main():
    with open(SVG_PATH, "r", encoding="utf-8") as f:
        svg_text = f.read()

    # 圆形版（ic_launcher_round）：将圆角半径改为整圆
    round_svg = svg_text.replace('rx="224"', 'rx="512"')

    square_master = svg_to_png(svg_text, 1024)
    round_master = svg_to_png(round_svg, 1024)

    # 1. Android 各密度图标
    for name, size in DENSITIES.items():
        folder = os.path.join(RES_DIR, f"mipmap-{name}")
        os.makedirs(folder, exist_ok=True)
        square_master.resize((size, size), Image.LANCZOS).save(
            os.path.join(folder, "ic_launcher.png"), "PNG")
        round_master.resize((size, size), Image.LANCZOS).save(
            os.path.join(folder, "ic_launcher_round.png"), "PNG")
        print(f"[OK] mipmap-{name} -> {size}x{size}")

    # 2. 管理端 favicon.ico（含 16/32/48 多尺寸）
    os.makedirs(ADMIN_PUBLIC, exist_ok=True)
    sizes = [(16, 16), (32, 32), (48, 48)]
    square_master.resize((48, 48), Image.LANCZOS).save(
        os.path.join(ADMIN_PUBLIC, "favicon.ico"), format="ICO", sizes=sizes)
    print("[OK] web-admin-react/public/favicon.ico")

    # 3. 预览图
    os.makedirs(DOCS_DIR, exist_ok=True)
    square_master.save(os.path.join(DOCS_DIR, "app_icon_preview.png"), "PNG")
    round_master.save(os.path.join(DOCS_DIR, "app_icon_preview_round.png"), "PNG")
    print("[OK] preview saved to docs/")


if __name__ == "__main__":
    main()
