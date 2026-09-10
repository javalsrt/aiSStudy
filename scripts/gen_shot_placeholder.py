# -*- coding: utf-8 -*-
"""
生成官网截图预览占位图（website/assets/screens/）。
用真实截图替换同名文件即可，无需改任何代码。
运行：python scripts/gen_shot_placeholder.py
"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUT = os.path.join(ROOT, "website", "assets", "screens")

FONT_PATH = "C:/Windows/Fonts/msyh.ttc"
BLUE = (10, 132, 255)
INK = (29, 29, 31)
GRAY = (134, 134, 139)
BG = (245, 245, 247)
LINE = (229, 231, 235)

# (文件名, 标题, 宽, 高)
SHOTS = [
    ("admin-dashboard.png", "数据看板", 1440, 900),
    ("admin-students.png", "学生管理", 1440, 900),
    ("admin-courses.png", "课程管理", 1440, 900),
    ("admin-stats.png", "成绩统计", 1440, 900),
    ("app-login.png", "登录", 390, 844),
    ("app-home.png", "学习首页", 390, 844),
    ("app-quiz.png", "智能刷题", 390, 844),
    ("app-report.png", "测评报告", 390, 844),
]


def font(size):
    return ImageFont.truetype(FONT_PATH, size)


def make(name, title, w, h):
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)
    d.rectangle([1, 1, w - 2, h - 2], outline=LINE, width=2)

    cx, cy = w // 2, h // 2

    # 标题
    f_title = font(56 if w > 800 else 34)
    d.text((cx, cy - 40), title, font=f_title, fill=BLUE, anchor="mm")

    # 副标题
    f_sub = font(26 if w > 800 else 17)
    d.text((cx, cy + 40), "占位图 · 请替换为真实截图", font=f_sub, fill=GRAY, anchor="mm")

    # 路径提示
    f_path = font(20 if w > 800 else 13)
    d.text((cx, cy + 92), f"website/assets/screens/{name}",
           font=f_path, fill=(160, 165, 175), anchor="mm")

    img.save(os.path.join(OUT, name), "PNG")
    print(f"[OK] {name} ({w}x{h})")


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, title, w, h in SHOTS:
        make(name, title, w, h)


if __name__ == "__main__":
    main()
