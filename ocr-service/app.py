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


if __name__ == "__main__":
    import os

    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("OCR_PORT", "8100")))
