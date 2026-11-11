# -*- coding: utf-8 -*-
"""
作息表 OCR 服务（离线、零成本）
流程：OpenCV 形态学检测表格横/竖线 → 按行切带 + 按竖线切列 →
     RapidOCR 识别「名称」「时间」两个单元格 → 正则抽取起止时间。
无表格线（手机斜拍/无边框）时自动降级：整图 OCR + 按 y 聚行 + 按 x 分左右。
"""
import base64
import io
import re
import logging

import cv2
import numpy as np
from fastapi import FastAPI, File, UploadFile
from pydantic import BaseModel
from rapidocr_onnxruntime import RapidOCR

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("ocr")

app = FastAPI(title="Bell timetable OCR", version="1.0")
ocr = RapidOCR()

TIME_RE = re.compile(r"(\d{1,2})\s*[:：]\s*(\d{1,2})")


def _norm(h: int, m: int):
    if 0 <= h <= 23 and 0 <= m <= 59:
        return f"{h:02d}:{m:02d}"
    return None


def parse_time_pair(text: str):
    """从 '8:55——9:35' / '11:25-12:05（手机统一管理）' 中抽取起止时间"""
    if not text:
        return None, None
    pairs = TIME_RE.findall(text)
    times = []
    for h, m in pairs:
        t = _norm(int(h), int(m))
        if t and (not times or times[-1] != t):
            times.append(t)
    if len(times) >= 2:
        return times[0], times[1]
    return None, None


def _lines(mask: np.ndarray, axis: int, min_gap: int = 6, ratio: float = 0.35):
    """投影找线：axis=1 返回每行的水平线 y；axis=0 返回每列的竖线 x"""
    if mask.size == 0:
        return []
    proj = (mask > 0).sum(axis=axis)
    peak = int(proj.max()) if proj.size else 0
    if peak <= 0:
        return []
    idx = np.where(proj >= max(3, int(peak * ratio)))[0]
    groups = []
    for i in idx:
        if groups and i - groups[-1][-1] <= min_gap:
            groups[-1].append(int(i))
        else:
            groups.append([int(i)])
    return [int(np.mean(g)) for g in groups]


def detect_grid(gray: np.ndarray):
    """返回 (水平线 ys, 竖线 xs)"""
    # 反二值化：文字与线条为白
    bw = cv2.adaptiveThreshold(~gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY, 15, -2)
    h, w = bw.shape
    hk = cv2.getStructuringElement(cv2.MORPH_RECT, (max(20, w // 12), 1))
    vk = cv2.getStructuringElement(cv2.MORPH_RECT, (1, max(20, h // 25)))
    hor = cv2.morphologyEx(bw, cv2.MORPH_OPEN, hk, iterations=1)
    ver = cv2.morphologyEx(bw, cv2.MORPH_OPEN, vk, iterations=1)
    ys = _lines(hor, axis=1)
    xs = _lines(ver, axis=0)
    return ys, xs


def ocr_text(img: np.ndarray):
    """对单块区域 OCR，返回按 x 排序拼接的文本"""
    result, _ = ocr(img)
    if not result:
        return ""
    items = []
    for box, text, score in result:
        if score is not None and score < 0.35:
            continue
        x = min(p[0] for p in box)
        items.append((x, str(text)))
    items.sort(key=lambda t: t[0])
    return "".join(t for _, t in items).strip()


def _split_x(xs, w: int):
    """选择分列线：取靠近 55%~80% 宽度处最靠左的竖线，避免落到表格外框"""
    cands = [x for x in xs if 0.35 * w <= x <= 0.85 * w]
    return min(cands, key=lambda x: abs(x - 0.62 * w)) if cands else None


def recognize_rows(img_bgr: np.ndarray):
    """
    整图只跑一次 OCR，再用检测到的表格横线把文字框归属到「行」，
    按竖线（或无竖线时按 55% 宽度）分成「名称 / 时间」两列。
    这样避免了逐单元格 OCR（40+ 次），耗时从分钟级降到秒级。
    """
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    h, w = gray.shape
    # 限制宽度，控制耗时与内存
    if w > 1400:
        scale = 1400 / float(w)
        gray = cv2.resize(gray, (1400, max(1, int(h * scale))), interpolation=cv2.INTER_AREA)
        h, w = gray.shape

    ys, xs = detect_grid(gray)
    split = _split_x(xs, w) or int(w * 0.55)

    result, _ = ocr(gray)
    if not result:
        return []

    bands = {}
    for box, text, score in result:
        if score is not None and score < 0.3:
            continue
        cy = (float(box[0][1]) + float(box[2][1])) / 2
        cx = (float(box[0][0]) + float(box[1][0])) / 2
        band = None
        if len(ys) >= 3:
            for i in range(len(ys) - 1):
                if ys[i] <= cy <= ys[i + 1]:
                    band = i
                    break
        if band is None:
            band = int(cy // max(20, h // 16))
        d = bands.setdefault(band, {"left": [], "right": []})
        (d["left"] if cx < split else d["right"]).append((cx, str(text)))

    rows = []
    for band in sorted(bands):
        d = bands[band]
        left = "".join(t for _, t in sorted(d["left"])).strip()
        right = "".join(t for _, t in sorted(d["right"])).strip()
        st, et = parse_time_pair(right or left)
        if not (st and et):
            st, et = parse_time_pair(left)
        if st and et:
            rows.append({"name": left or right, "text": right or left, "start": st, "end": et})
    log.info("网格：横线 %d 条，分列线 %s；OCR 行数 %d", len(ys), split, len(rows))
    return rows


CN_NUM = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8}


def build_periods(rows):
    """
    把 OCR 得到的行映射成教学节次：
    - 名称含「节」或「晚自」才视为教学节次（早读/眼保健操/午休等自动忽略）
    - 名称里的数字/中文数字可直接定位；OCR 漏字（如「第节」「第K节」）按时间顺序补空缺
    """
    explicit = {}
    pending = []
    for idx, r in enumerate(rows):
        name = re.sub(r"\s+", "", r.get("name") or "")
        if "晚自" in name:
            pending.append((idx, "late"))
            continue
        if "节" not in name:
            continue
        m = re.search(r"([1-8])", name) or re.search(r"([一二三四五六七八])", name)
        if m:
            token = m.group(1)
            explicit[idx] = int(token) if token.isdigit() else CN_NUM[token]
        else:
            pending.append((idx, "unknown"))

    used = set(explicit.values())
    assigned = dict(explicit)
    for idx, kind in pending:
        if kind == "late":
            # 晚自习固定为第 8 节；若已存在「第八节」则忽略该行
            if 8 not in used:
                assigned[idx] = 8
                used.add(8)
            continue
        prev = max([v for k, v in explicit.items() if k < idx] or [0])
        nxt = min([v for k, v in explicit.items() if k > idx] or [9])
        cand = [n for n in range(prev + 1, nxt) if n not in used]
        if not cand:
            cand = [n for n in range(1, 9) if n not in used]
        if cand:
            assigned[idx] = cand[0]
            used.add(cand[0])

    periods, seen = [], set()
    for idx in sorted(assigned):
        node = assigned[idx]
        if node in seen:
            continue
        seen.add(node)
        periods.append({"node": node, "startTime": rows[idx]["start"], "endTime": rows[idx]["end"]})
    return sorted(periods, key=lambda p: p["node"])


@app.middleware("http")
async def log_request_meta(request, call_next):
    if request.url.path.startswith("/ocr"):
        log.info("REQ %s ct=%s cl=%s te=%s", request.url.path,
                 request.headers.get("content-type"),
                 request.headers.get("content-length"),
                 request.headers.get("transfer-encoding"))
    return await call_next(request)


@app.get("/health")
def health():
    return {"status": "ok"}


def _process(data: bytes):
    if not data:
        return {"error": "图片为空", "rows": [], "periods": [], "count": 0}
    arr = np.frombuffer(data, np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        return {"error": "无法解析图片（支持 jpg/png/webp）", "rows": [], "periods": [], "count": 0}
    try:
        rows = recognize_rows(img)
    except Exception as e:  # noqa: BLE001
        log.exception("识别失败")
        return {"error": f"识别失败：{e}", "rows": [], "periods": [], "count": 0}
    periods = build_periods(rows)
    log.info("识别到 %d 行带时间记录，映射出 %d 个教学节次", len(rows), len(periods))
    return {"rows": rows, "periods": periods, "count": len(periods)}


@app.post("/ocr")
async def ocr_image(file: UploadFile = File(...)):
    return _process(await file.read())


class Base64Payload(BaseModel):
    image: str


@app.post("/ocr/base64")
def ocr_base64(payload: Base64Payload):
    """后端调用入口：JSON + base64，避免手工拼 multipart 的兼容问题"""
    try:
        raw = base64.b64decode(payload.image.split(",")[-1])
    except Exception:
        return {"error": "base64 解码失败", "rows": [], "periods": [], "count": 0}
    return _process(raw)


# ===== 课程表照片识别 =====
# 与作息表不同，课程表是「星期 × 节次」二维网格，这里只做版面还原：
# 识别表头星期列与左侧节次行，把每格文字按从上到下拼好，交给大模型做语义解析。
DAY_CN = "一二三四五六日"
DAY_RE = re.compile(r"^星期([一二三四五六日天])$")
NODE_RE = re.compile(r"^\d{1,2}$")
# 合并单元格（上午/下午/午休）与表头文字不属于任何课程格
LAYOUT_NOISE = {"星期", "节次", "上午", "下午", "晚上", "中午", "午休", "午", "休"}


def recognize_course_table(img_bgr: np.ndarray):
    """
    还原课程表网格：
    1. OCR 全图，取每块文字的中心坐标；
    2. 「星期一~星期日」文字块定义列，左侧数字块定义行（节次）；
    3. 其余文字块按最近列/最近行归入单元格，格内按 y 排序后用 " / " 拼接。
    """
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    h, w = gray.shape
    # 限制宽度，避免大图 OCR 过慢
    if w > 1600:
        scale = 1600 / float(w)
        gray = cv2.resize(gray, (1600, max(1, int(h * scale))), interpolation=cv2.INTER_AREA)
        h, w = gray.shape

    result, _ = ocr(gray)
    if not result:
        return {"error": "图片中未识别到文字"}

    blocks = []
    for box, text, score in result:
        if score is not None and score < 0.35:
            continue
        raw_text = str(text).strip()
        if not raw_text:
            continue
        xs = [float(p[0]) for p in box]
        ys = [float(p[1]) for p in box]
        blocks.append({
            "text": raw_text,
            "plain": re.sub(r"\s+", "", raw_text),
            "cx": sum(xs) / 4.0,
            "cy": sum(ys) / 4.0,
        })

    # 1) 表头：星期列
    header_map = {}
    for b in blocks:
        m = DAY_RE.match(b["plain"])
        if not m:
            continue
        token = m.group(1)
        dow = 7 if token == "天" else DAY_CN.index(token) + 1
        b["dayOfWeek"] = dow
        header_map.setdefault(dow, b)
    if len(header_map) < 2:
        return {"error": "未识别到课程表表头（星期一~星期日），请上传清晰的课表照片"}
    header = sorted(header_map.values(), key=lambda b: b["cx"])
    col_centers = [b["cx"] for b in header]
    gap = (col_centers[-1] - col_centers[0]) / max(1, len(col_centers) - 1)
    # 左边界：表头首列中心往左偏 35% 列宽，既能排除「上午/下午」单元格，又能保留节次数字
    left_limit = col_centers[0] - gap * 0.35
    header_cy = sum(b["cy"] for b in header) / len(header)

    # 2) 行：优先用表格横线切带（节次数字可能漏识别，横线更稳定）
    grid_ys, _ = detect_grid(gray)
    bands = [(grid_ys[i], grid_ys[i + 1]) for i in range(len(grid_ys) - 1)
             if grid_ys[i + 1] - grid_ys[i] >= 25]
    header_band_idx = None
    for i, (y0, y1) in enumerate(bands):
        if y0 <= header_cy <= y1:
            header_band_idx = i
            break

    rows = []
    if header_band_idx is not None and len(bands) - 1 - header_band_idx >= 2:
        node_no = 0
        for i in range(header_band_idx + 1, len(bands)):
            y0, y1 = bands[i]
            band_blocks = [b for b in blocks if y0 <= b["cy"] < y1]
            left_nums = [int(b["plain"]) for b in band_blocks
                         if b["cx"] < left_limit and NODE_RE.match(b["plain"]) and 1 <= int(b["plain"]) <= 12]
            # 午休等合并单元格不是教学节次（带内只有「午/休」等字样）
            band_plain = "".join(b["plain"] for b in band_blocks)
            content_blocks = [b for b in band_blocks
                              if b["cx"] >= left_limit and b["plain"] not in ("午", "休", "午休", "上午", "下午")]
            if not content_blocks and any(k in band_plain for k in ("午", "休")):
                continue
            if left_nums:
                node_no = left_nums[0]
            else:
                node_no += 1
            rows.append({"node": node_no, "y0": y0, "y1": y1})
    else:
        # 回退：无横线时用左侧节次数字定位行
        node_blocks = []
        for b in blocks:
            if b["cx"] >= left_limit or not NODE_RE.match(b["plain"]):
                continue
            n = int(b["plain"])
            if 1 <= n <= 12:
                b["node"] = n
                node_blocks.append(b)
        node_blocks.sort(key=lambda b: b["cy"])
        seen = set()
        for b in node_blocks:
            if b["node"] in seen:
                continue
            seen.add(b["node"])
            rows.append({"node": b["node"], "y0": b["cy"] - 40, "y1": b["cy"] + 40})
    if not rows:
        return {"error": "未识别到课程表节次行，请确认照片包含完整表格"}

    # 3) 文字块归格：先按行带，再按最近星期列
    skip_ids = {id(b) for b in header}
    cells = [[[] for _ in header] for _ in rows]
    notes = []
    for b in blocks:
        if id(b) in skip_ids or not b["plain"]:
            continue
        if b["cx"] < left_limit or b["plain"] in LAYOUT_NOISE:
            notes.append(b["text"])
            continue
        row_idx = next((i for i, r in enumerate(rows) if r["y0"] <= b["cy"] < r["y1"]), None)
        if row_idx is None:
            notes.append(b["text"])
            continue
        col_idx = min(range(len(col_centers)), key=lambda i: abs(col_centers[i] - b["cx"]))
        cells[row_idx][col_idx].append(b)

    rows_out = []
    for i, r in enumerate(rows):
        row_cells = {}
        for j, hb in enumerate(header):
            ordered = sorted(cells[i][j], key=lambda x: (x["cy"], x["cx"]))
            # 同一格内完全相同的文字（OCR 重复框）只保留一次
            uniq, seen_text = [], set()
            for x in ordered:
                if x["plain"] in seen_text:
                    continue
                seen_text.add(x["plain"])
                uniq.append(x)
            row_cells[str(hb["dayOfWeek"])] = " / ".join(x["text"] for x in uniq)
        rows_out.append({"node": r["node"], "cells": row_cells})

    days = [{"dayOfWeek": b["dayOfWeek"], "name": "星期" + DAY_CN[b["dayOfWeek"] - 1]} for b in header]
    return {
        "days": days,
        "rows": rows_out,
        "notes": notes[:20],
        "count": len(rows_out),
    }


@app.post("/ocr/course-table")
def ocr_course_table(payload: Base64Payload):
    """课程表照片 → 二维网格（供课程导入预览使用）"""
    try:
        raw = base64.b64decode(payload.image.split(",")[-1])
    except Exception:
        return {"error": "base64 解码失败"}
    img = cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        return {"error": "无法解析图片（支持 jpg/png/webp）"}
    try:
        return recognize_course_table(img)
    except Exception as e:  # noqa: BLE001
        log.exception("课程表识别失败")
        return {"error": f"识别失败：{e}"}


if __name__ == "__main__":
    import os

    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("OCR_PORT", "8100")))
