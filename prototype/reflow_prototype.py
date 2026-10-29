#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
pictext 参考原型 —— 无 OCR 的文字长图「左右对半切分 + 按行回插」重排。

这不是 Android 实现，而是给编码 agent 的“可运行算法规格”：
所有参数、判定顺序、失败回退都在这里跑通，并输出调试图与前后对比图。

用法:
    python reflow_prototype.py --make-fixture     # 生成 3 张测试图并全部跑一遍
    python reflow_prototype.py --input a.png      # 只跑指定图片

输出（prototype/out/）:
    source*.png      三张测试图：亮色长文 / 深色长文 / 纯图片
    after_*.png      重排结果
    debug_*.png      行带、图块、切分列的可视化
    compare_*.png    同宽前后对比（左原图 / 右重排）

依赖: Python 3.10+, numpy, Pillow；中文字体用 Windows 自带的微软雅黑。
"""
from __future__ import annotations

import argparse
import json
import time
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "out"
FONT = r"C:\Windows\Fonts\msyh.ttc"
FONT_BOLD = r"C:\Windows\Fonts\msyhbd.ttc"

# ---------------------------------------------------------------- 合成测试图

TITLE = "把长图读成书：小屏幕上的重排阅读"
PARAS = [
    "在手机上打开一张信息密度很高的长图，最直接的感受是字太小。手指不断放大、拖动，"
    "视线在屏幕里来回游走，读三行就丢一次位置。这不是内容的问题，而是排版比例的问题："
    "一张为宽屏准备的图片，被硬塞进六英寸的窗口里，每个汉字只剩下几个像素。",
    "如果只判断哪些地方是文字、哪些地方是图片，而不去识别文字内容，问题会简单很多。"
    "文字在图像上呈现出稳定的纹理：行与行之间有空隙，字与字之间有笔画，整段文字的"
    "左右边界大致对齐。这些规律用投影分析就能抓住，不需要任何模型，也不需要联网。",
    "找到文字行之后，可以沿水平方向把每一行切成左右两半，再按阅读顺序重新插回。"
    "原来的 A、B、C 三行，变成 A1、A2、B1、B2、C1、C2 六行，字号随之放大接近一倍。"
    "对中文这种方块字来说，一倍的字号差距，往往就是从放弃阅读到愿意读完的差距。",
    "图片和图表不应该被切开。它们需要的是整体放大，而不是重排。因此算法还要区分"
    "文字块与非文字块：连续的大面积色块、超高的墨迹游程、异常的行高，都是判断依据。"
    "分错的那一块，最多只是排版难看；只要不丢内容，就仍然可用。",
    "真正难的是边界情况：两端对齐的正文里偶尔夹一行居中的小标题，页边留着页码，"
    "段末只剩三五个字。切分线必须落在字缝里，否则会把一个汉字劈成两半；短行则应该"
    "保持原样，只按同样的比例放大，而不是硬切成两段。",
    "最后是阅读体验。重排之后，文章会变长两三倍，一次滑动跨过的内容反而变少。"
    "因此还需要记住阅读位置、支持原图对照、允许把某一张图单独放大查看。"
    "这些都不是算法问题，但决定了这个工具能不能真的被用下去。",
]
CAPTION = "图 1 重排前后的行结构对比"
CAPTION2 = "图 2 非文字块整体放大，不参与切分"


def wrap_cjk(text: str, font: ImageFont.FreeTypeFont, max_w: float) -> list[str]:
    lines, cur = [], ""
    for ch in text:
        if font.getlength(cur + ch) > max_w and cur:
            lines.append(cur)
            cur = ch
        else:
            cur += ch
    if cur:
        lines.append(cur)
    return lines


def photo_block(w: int = 620, h: int = 320) -> Image.Image:
    yy, xx = np.mgrid[0:h, 0:w]
    base = np.zeros((h, w, 3), np.uint8)
    base[..., 0] = (55 + 120 * xx / w).astype(np.uint8)
    base[..., 1] = (80 + 90 * yy / h).astype(np.uint8)
    base[..., 2] = (120 + 60 * (1 - xx / w)).astype(np.uint8)
    im = Image.fromarray(base)
    d = ImageDraw.Draw(im)
    d.ellipse((70, 50, 250, 230), fill=(238, 216, 176))
    d.rectangle((350, 90, 560, 270), fill=(38, 56, 88))
    return im


def make_fixture(path: Path, width: int = 1080, dark: bool = False) -> Path:
    """模拟一张 1080 宽、文字偏小的“转发过的长图”：正文 + 图表 + 照片块 + 图注。"""
    margin = 60
    text_w = width - margin * 2
    body = ImageFont.truetype(FONT, 22)
    bold = ImageFont.truetype(FONT_BOLD, 34)
    small = ImageFont.truetype(FONT, 17)

    if dark:
        page_bg, title_c, body_c, cap_c = (20, 21, 24), (236, 236, 240), (214, 215, 220), (150, 152, 160)
    else:
        page_bg, title_c, body_c, cap_c = (255, 255, 255), (28, 28, 30), (38, 38, 42), (120, 120, 126)

    line_h = 36
    img = Image.new("RGB", (width, 3200), page_bg)
    d = ImageDraw.Draw(img)

    y = 56
    d.text((margin, y), TITLE, font=bold, fill=title_c)
    y += 70

    def draw_caption(text: str) -> None:
        nonlocal y
        d.text(((width - small.getlength(text)) / 2, y), text, font=small, fill=cap_c)
        y += 34

    for i, para in enumerate(PARAS):
        for line in wrap_cjk(para, body, text_w):
            d.text((margin, y), line, font=body, fill=body_c)
            y += line_h
        y += 14
        if i == 1:
            card_w, card_h = 560, 250
            cx = (width - card_w) // 2
            d.rounded_rectangle((cx, y, cx + card_w, y + card_h), 12, fill=(244, 244, 246) if not dark else (38, 39, 44))
            base = y + card_h - 40
            for k, (h, c) in enumerate([(70, (86, 112, 160)), (110, (110, 140, 190)), (150, (140, 166, 206))]):
                bx = cx + 60 + k * 150
                d.rounded_rectangle((bx, base - h, bx + 80, base), 6, fill=c)
            d.line((cx + 40, base, cx + card_w - 40, base), fill=(180, 182, 188), width=2)
            y += card_h + 12
            draw_caption(CAPTION)
        if i == 3:
            pb = photo_block()
            img.paste(pb, ((width - pb.width) // 2, y))
            y += pb.height + 12
            draw_caption(CAPTION2)

    y += 20
    img = img.crop((0, 0, width, y))
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)
    return path


def make_photo_only(path: Path, w: int = 900, h: int = 1200) -> Path:
    """没有任何文字的图片：重排器应当放弃切分，直接展示原图。"""
    yy, xx = np.mgrid[0:h, 0:w]
    base = np.zeros((h, w, 3), np.uint8)
    base[..., 0] = (40 + 160 * xx / w).astype(np.uint8)
    base[..., 1] = (60 + 120 * yy / h).astype(np.uint8)
    base[..., 2] = (150 - 90 * xx / w).astype(np.uint8)
    im = Image.fromarray(base)
    d = ImageDraw.Draw(im)
    for k in range(9):
        cx, cy = (k * 97 + 120) % w, (k * 211 + 80) % h
        r = 30 + (k * 37) % 90
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(240, 220 - k * 12, 180 + k * 6))
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path)
    return path


# ---------------------------------------------------------------- 基础算子


def to_gray(rgb: np.ndarray) -> np.ndarray:
    a = rgb.astype(np.float32)
    return a[..., 0] * 0.299 + a[..., 1] * 0.587 + a[..., 2] * 0.114


def smooth1d(a: np.ndarray, k: int = 3) -> np.ndarray:
    if k <= 1:
        return a.astype(np.float32)
    pad = k // 2
    ap = np.pad(a.astype(np.float32), (pad, pad), mode="edge")
    return np.convolve(ap, np.ones(k, np.float32) / k, mode="valid")


def otsu_threshold(v: np.ndarray) -> int:
    h = np.bincount(np.clip(v, 0, 255).astype(np.uint8).ravel(), minlength=256).astype(np.float64)
    total = h.sum()
    if total == 0:
        return 0
    p = h / total
    omega = np.cumsum(p)
    mu = np.cumsum(p * np.arange(256))
    denom = np.clip(omega * (1.0 - omega), 1e-12, None)
    sigma_b = (mu[-1] * omega - mu) ** 2 / denom
    return int(np.argmax(sigma_b))


def estimate_background(g: np.ndarray, cell: int = 32) -> tuple[np.ndarray, bool]:
    """背景曲面：用全局分位数取“纸色”种子，只用接近纸色的像素做网格中值，补洞后双线性放大。

    关键点是深色图块内部的格子不会被当成本地背景（朴素的“分块 90 分位”会，
    结果图表被吃成背景，只剩边缘噪声）。O(N) 且有梯度光照时仍然成立。
    """
    h, w = g.shape
    border = np.concatenate([g[0, :], g[-1, :], g[:, 0], g[:, -1]])
    dark = float(np.median(border)) < 110
    bg0 = float(np.percentile(g, 8 if dark else 92))
    cand = (g <= bg0 + 22) if dark else (g >= bg0 - 22)

    ny, nx = max(1, h // cell), max(1, w // cell)
    grid = np.full((ny, nx), np.nan, np.float32)
    need = max(12, int(0.10 * cell * cell))
    for j in range(ny):
        for i in range(nx):
            sl = (slice(j * cell, (j + 1) * cell), slice(i * cell, (i + 1) * cell))
            vals = g[sl][cand[sl]]
            if vals.size >= need:
                grid[j, i] = np.median(vals)

    grid = np.where(np.isnan(grid), bg0, grid)
    gp = np.pad(grid, 1, mode="edge")
    stack = np.stack([gp[dy:dy + ny, dx:dx + nx] for dy in range(3) for dx in range(3)])
    grid = np.median(stack, axis=0)

    small = Image.fromarray(grid.astype(np.float32), mode="F").resize((w, h), Image.BILINEAR)
    return np.asarray(small, dtype=np.float32), dark


def neighbor_count(mask: np.ndarray) -> np.ndarray:
    s = np.zeros(mask.shape, np.uint8)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dy == 0 and dx == 0:
                continue
            s += np.roll(np.roll(mask, dy, 0), dx, 1).astype(np.uint8)
    return s


def runs_of(flags: np.ndarray) -> list[tuple[int, int]]:
    d = np.diff(flags.astype(np.int8))
    starts = list(np.flatnonzero(d == 1) + 1)
    ends = list(np.flatnonzero(d == -1) + 1)
    if flags.size and flags[0]:
        starts = [0] + starts
    if flags.size and flags[-1]:
        ends = ends + [len(flags)]
    return list(zip(starts, ends))


def merge_runs(runs: list[tuple[int, int]], max_gap: int) -> list[tuple[int, int]]:
    out: list[list[int]] = []
    for s, e in runs:
        if out and s - out[-1][1] <= max_gap:
            out[-1][1] = e
        else:
            out.append([s, e])
    return [(s, e) for s, e in out]


def vertical_run_length(mask: np.ndarray) -> np.ndarray:
    h, w = mask.shape
    run = np.zeros(w, np.int32)
    best = np.zeros((h, w), np.int32)
    for y in range(h):
        run = np.where(mask[y], run + 1, 0)
        best[y] = run
    return best


# ---------------------------------------------------------------- 版面分析


@dataclass
class Line:
    y0: int
    y1: int
    x0: int = 0
    x1: int = 0
    kind: str = "text"  # text | figure
    cut: int = 0
    split: bool = False
    centered: bool = False


@dataclass
class Layout:
    width: int
    height: int
    lines: list[Line] = field(default_factory=list)
    page_x0: int = 0
    page_x1: int = 0
    cut: int = -1
    cut_depth: float = 1.0
    median_h: float = 0.0
    gap: float = 0.0
    dark: bool = False
    bg_rgb: tuple[int, int, int] = (255, 255, 255)
    notes: list[str] = field(default_factory=list)


def expand_figure(lowc: np.ndarray, y0: int, y1: int, x0: int, x1: int) -> tuple[int, int, int, int]:
    """把非文字块从“墨迹外框”扩张到视觉外框（浅色卡片/坐标轴/留白底）。"""
    h, w = lowc.shape
    max_py = max(12, int(0.30 * (y1 - y0)))
    max_px = max(12, int(0.30 * (x1 - x0)))
    step = 0
    while step < max_py and y0 > 0 and lowc[y0 - 1, x0:x1].mean() >= 0.5:
        y0 -= 1
        step += 1
    step = 0
    while step < max_py and y1 < h and lowc[y1, x0:x1].mean() >= 0.5:
        y1 += 1
        step += 1
    step = 0
    while step < max_px and x0 > 0 and lowc[y0:y1, x0 - 1].mean() >= 0.5:
        x0 -= 1
        step += 1
    step = 0
    while step < max_px and x1 < w and lowc[y0:y1, x1].mean() >= 0.5:
        x1 += 1
        step += 1
    return y0, y1, x0, x1


def line_gap(mask: np.ndarray, y0: int, y1: int, center: int, win: int, width: int) -> tuple[int, float]:
    """在一行文字的 [center-win, center+win] 内，找离中心最近、墨量足够低的列（字缝）。"""
    lo, hi = max(0, center - win), min(width, center + win)
    seg = mask[y0:y1, lo:hi].sum(axis=0).astype(np.float32)
    if seg.size == 0:
        return center, 1e9
    pos = seg[seg > 0]
    thr = max(1.0, 0.10 * float(np.median(pos))) if pos.size else 1.0
    idx = np.flatnonzero(seg <= thr)
    if idx.size == 0:
        return lo + int(np.argmin(seg)), float(seg.min())
    pick = idx[int(np.argmin(np.abs(idx - (center - lo))))]
    return lo + int(pick), float(seg[pick])


def analyze(rgb: np.ndarray) -> tuple[Layout, dict]:
    t0 = time.perf_counter()
    h, w = rgb.shape[:2]
    g = to_gray(rgb)
    bg, dark = estimate_background(g)
    ink = np.clip((bg - g) if not dark else (g - bg), 0, 255)

    t_bin = int(np.clip(otsu_threshold(ink), 12, 80))
    mask = ink > t_bin
    mask &= neighbor_count(mask) >= 1
    # 去掉大面积“纸面”噪声：不做形态学腐蚀，避免吃掉中文细笔画

    # 行投影用“绝对墨量”而不是“相对峰值”：图注、脚注的墨量只有正文的几分之一，
    # 用相对阈值会把整行小字丢掉——对阅读类应用来说，丢内容比多切一刀严重得多。
    row_ink = mask.sum(axis=1).astype(np.float32)
    prof_s = smooth1d(row_ink, 3)
    row_thr = max(3.0, 0.003 * w)
    raw = runs_of(prof_s >= row_thr)
    if not raw:
        lay = Layout(w, h, dark=dark, bg_rgb=bg_color(rgb))
        lay.notes.append("no_ink")
        return lay, {"mask": mask, "ink": ink, "bg": bg}

    heights = [e - s for s, e in raw]
    med_h = float(np.median(heights))
    merged = merge_runs(raw, max(2, int(0.45 * med_h)))
    merged = [(s, e) for s, e in merged if (e - s) >= 0.35 * med_h]

    heights = [e - s for s, e in merged]
    med_h = float(np.median(heights))
    vrun = vertical_run_length(mask)

    lines: list[Line] = []
    for s, e in merged:
        band = mask[s:e]
        density = float(band.mean())
        max_run = int(vrun[s:e].max()) if e > s else 0
        long_frac = float((vrun[s:e] > 2.4 * med_h).mean()) if e > s else 0.0
        bh = e - s
        is_fig = (
            bh > 2.8 * med_h
            or density > 0.62
            or (max_run > 2.6 * med_h and long_frac > 0.008)
        )
        ys = band.any(axis=0)
        x0, x1 = int(np.argmax(ys)), int(len(ys) - np.argmax(ys[::-1]))
        lines.append(Line(s, e, x0, x1, "figure" if is_fig else "text"))

    gaps = [b.y0 - a.y1 for a, b in zip(lines, lines[1:]) if b.y0 > a.y1]
    gap = float(np.median(gaps)) if gaps else 6.0

    # 照片中间的浅色行会把它切成两段，先把相邻的非文字带并回去
    merged2: list[Line] = []
    for l in lines:
        if (merged2 and l.kind == "figure" and merged2[-1].kind == "figure"
                and l.y0 - merged2[-1].y1 <= 4 * gap):
            merged2[-1].y1 = l.y1
            merged2[-1].x0 = min(merged2[-1].x0, l.x0)
            merged2[-1].x1 = max(merged2[-1].x1, l.x1)
        else:
            merged2.append(l)
    lines = merged2

    # 非文字块向外生长到视觉边界：卡片、坐标轴、浅色底都只有很低的反差，
    # 用“低反差墨迹”向右左上下扩张，遇到真正的空白（或正文）就停。
    lowc = (ink > 4) & (ink < 45)
    for l in lines:
        if l.kind == "figure":
            l.y0, l.y1, l.x0, l.x1 = expand_figure(lowc, l.y0, l.y1, l.x0, l.x1)

    text_lines = [l for l in lines if l.kind == "text"]
    rough_x0 = min(l.x0 for l in lines)
    rough_x1 = max(l.x1 for l in lines)
    rough_w = max(1, rough_x1 - rough_x0)
    long_lines = [l for l in text_lines if (l.x1 - l.x0) >= 0.70 * rough_w]
    # 版心用“长行”的最左/最右定义：页码、水印这类短行不该把版心拉宽
    if long_lines:
        page_x0 = min(l.x0 for l in long_lines)
        page_x1 = max(l.x1 for l in long_lines)
    else:
        page_x0, page_x1 = rough_x0, rough_x1

    lay = Layout(w, h, lines=lines, page_x0=page_x0, page_x1=page_x1,
                 median_h=med_h, gap=gap, dark=dark, bg_rgb=bg_color(rgb))

    if not text_lines:
        lay.notes.append("no_text")
        return lay, {"mask": mask, "ink": ink, "bg": bg}

    page_w = page_x1 - page_x0
    if len(long_lines) < 2:
        lay.notes.append("too_few_long_lines")
        return lay, {"mask": mask, "ink": ink, "bg": bg}

    # ---- 切分列：逐行找“离中线最近的字缝”，再取中位数作为全局切点。
    # 中文正文按字格对齐，全局垂直投影上几乎不存在连续空白，单靠一条全局投影线
    # 很容易落在某一列笔画上；逐行找缝反而稳定。
    center = int(0.5 * (page_x0 + page_x1))
    win0 = max(8, int(0.06 * w))
    first = [line_gap(mask, l.y0, l.y1, center, win0, w)[0] for l in long_lines]
    cut = int(np.median(first))

    win = max(5, int(0.025 * w))
    split_lines: list[Line] = []
    clean = 0
    for l in text_lines:
        lw = l.x1 - l.x0
        l.centered = (
            abs((l.x0 - page_x0) - (page_x1 - l.x1)) < 0.06 * page_w
            and (l.x0 - page_x0) > 0.04 * page_w
        )
        if lw < 0.62 * page_w:
            l.split = False
            continue
        xc, ink_at = line_gap(mask, l.y0, l.y1, cut, win, w)
        l.cut, l.split = xc, True
        split_lines.append(l)
        if ink_at <= 1.5:
            clean += 1

    rows = np.zeros(h, bool)
    for l in long_lines:
        rows[l.y0:l.y1] = True
    col_s = smooth1d(mask[rows].sum(axis=0).astype(np.float32), 3)
    base = float(np.median(col_s[page_x0:page_x1])) + 1e-6
    lay.cut = cut
    lay.cut_depth = clean / max(1, len(split_lines))
    if not split_lines or clean < 0.70 * len(split_lines):
        lay.notes.append(f"weak_cut(clean={clean}/{len(split_lines)})")
        return lay, {"mask": mask, "ink": ink, "bg": bg}

    lay.median_h = float(np.median([l.y1 - l.y0 for l in text_lines]))
    return lay, {"mask": mask, "ink": ink, "bg": bg}


def bg_color(rgb: np.ndarray) -> tuple[int, int, int]:
    border = np.concatenate([rgb[0, :], rgb[-1, :], rgb[:, 0], rgb[:, -1]], axis=0)
    med = np.median(border, axis=0)
    return tuple(int(v) for v in med)


# ---------------------------------------------------------------- 重排计划


@dataclass
class Item:
    kind: str          # text_half | text_full | figure
    sx0: int
    sy0: int
    sx1: int
    sy1: int
    dx0: int
    dy0: int
    dw: int
    dh: int
    src_line: int = -1


@dataclass
class ReflowPlan:
    out_w: int
    scale: float
    items: list[Item] = field(default_factory=list)
    height: int = 0
    notes: list[str] = field(default_factory=list)


def plan_reflow(lay: Layout, out_w: int, target_em: int = 44, max_scale: float = 3.0,
                inset: int | None = None) -> ReflowPlan:
    if inset is None:
        inset = max(10, int(round(0.018 * out_w)))
    content_w = max(1, out_w - 2 * inset)
    page_w = lay.page_x1 - lay.page_x0
    half_w = max(lay.cut - lay.page_x0, lay.page_x1 - lay.cut)
    if lay.cut <= 0 or half_w <= 0:
        return ReflowPlan(out_w, 1.0, notes=["no_cut"])

    # 逐行切点会左右浮动几像素，缩放必须按“最宽的那一半”算，否则宽的一侧会被二次压缩，
    # 同一段文字的字号就会忽大忽小。
    split_lines = [l for l in lay.lines if l.kind == "text" and l.split]
    slot = max([max(l.cut - lay.page_x0, lay.page_x1 - l.cut) for l in split_lines] + [half_w])
    s_fit = content_w / max(1, slot)
    # 行带高度包含抗锯齿，比真实字身大约 8%，换算字号时先缩回去
    s_em = target_em / max(6.0, 0.92 * lay.median_h)
    s = float(min(s_fit, s_em, max_scale))

    plan = ReflowPlan(out_w, s)
    # 真正的收益基准不是“原图像素”，而是“原图按屏宽显示”时的字号：
    #   gain = s * 源宽 / 屏宽。窄图本来就已经被放大显示，再切只会更小、更长。
    gain = s * lay.width / out_w
    if gain < 1.25:
        plan.notes.append(f"gain_too_small({gain:.2f})")
        return plan

    # 字号被“最大字身”限住时，内容列会比屏窄，此时整体居中，左右留白对称
    col_w = min(content_w, int(round(slot * s)))
    left = (out_w - col_w) // 2

    gap_out = int(np.clip(round(lay.gap * s), 6, 40))
    y = inset
    prev_text_bottom: int | None = None
    for idx, l in enumerate(lay.lines):
        if l.kind == "figure":
            fw, fh = l.x1 - l.x0, l.y1 - l.y0
            sf = float(np.clip(content_w / max(1, fw), 1.0, 2.2))
            if fw > content_w:  # 超宽图整体缩到栏宽，绝不裁切
                sf = content_w / fw
            dw, dh = int(fw * sf), int(fh * sf)
            dx = inset + (content_w - dw) // 2
            plan.items.append(Item("figure", l.x0, l.y0, l.x1, l.y1, dx, y, dw, dh, idx))
            y += dh + gap_out
            prev_text_bottom = None
            continue

        # 段间距：源图上行距明显变大，就在重排结果里也留出更多空间
        if prev_text_bottom is not None and l.y0 - prev_text_bottom > 1.35 * lay.gap:
            y += int(gap_out * 0.9)

        sy0 = max(0, l.y0 - 1)
        sy1 = min(lay.height, l.y1 + 1)
        if l.split:
            for sx0, sx1 in ((lay.page_x0, l.cut), (l.cut, lay.page_x1)):
                sc = s
                dw = int(round((sx1 - sx0) * sc))
                if dw > col_w:
                    sc = col_w / (sx1 - sx0)
                    dw = col_w
                dh = int(round((sy1 - sy0) * sc))
                plan.items.append(Item("text_half", sx0, sy0, sx1, sy1, left, y, dw, dh, idx))
                y += dh + gap_out
        else:
            pad = 2
            sx0 = max(0, l.x0 - pad)
            sx1 = min(lay.width, l.x1 + pad)
            sc = min(s, col_w / max(1, sx1 - sx0))
            dw = int(round((sx1 - sx0) * sc))
            dh = int(round((sy1 - sy0) * sc))
            dx = left + ((col_w - dw) // 2 if l.centered else 0)
            plan.items.append(Item("text_full", sx0, sy0, sx1, sy1, dx, y, dw, dh, idx))
            y += dh + gap_out
        prev_text_bottom = l.y1

    plan.height = max(0, y - gap_out + inset)
    return plan


def render_plan(rgb: np.ndarray, plan: ReflowPlan, lay: Layout) -> Image.Image:
    canvas = Image.new("RGB", (plan.out_w, max(1, plan.height)), lay.bg_rgb)
    src = Image.fromarray(rgb)
    for it in plan.items:
        if it.dw <= 0 or it.dh <= 0:
            continue
        crop = src.crop((it.sx0, it.sy0, max(it.sx0 + 1, it.sx1), max(it.sy0 + 1, it.sy1)))
        crop = crop.resize((it.dw, it.dh), Image.LANCZOS)
        canvas.paste(crop, (it.dx0, it.dy0))
    return canvas


# ---------------------------------------------------------------- 可视化


def overlay(rgb: np.ndarray, lay: Layout, path: Path) -> None:
    im = Image.fromarray(rgb).convert("RGBA")
    ov = Image.new("RGBA", im.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    for l in lay.lines:
        if l.kind == "figure":
            d.rectangle((l.x0 - 4, l.y0 - 3, l.x1 + 4, l.y1 + 3), fill=(255, 140, 0, 46),
                        outline=(240, 120, 0, 220), width=2)
        else:
            d.rectangle((lay.page_x0, l.y0, lay.page_x1, l.y1), fill=(220, 40, 40, 30))
            if l.split:
                d.line((l.cut, l.y0, l.cut, l.y1), fill=(0, 170, 90, 255), width=2)
    if lay.cut > 0:
        d.line((lay.cut, 0, lay.cut, im.size[1]), fill=(0, 160, 220, 200), width=3)
    im = Image.alpha_composite(im, ov).convert("RGB")
    d2 = ImageDraw.Draw(im)
    legend = [("text band", (220, 40, 40)), ("figure / non-text", (240, 120, 0)), ("global cut", (0, 160, 220))]
    x = 16
    for label, color in legend:
        d2.rectangle((x, 14, x + 18, 30), fill=color)
        d2.text((x + 24, 14), label, fill=(40, 40, 40))
        x += 24 + int(d2.textlength(label)) + 34
    im.save(path)


def compare_sheet(rgb: np.ndarray, after: Image.Image, plan: ReflowPlan, lay: Layout,
                  path: Path, rows: int = 12) -> None:
    """左：原图同一段内容；右：重排后。两边等宽，字号差异即为真实收益。"""
    items = [it for it in plan.items if it.kind in ("text_half", "text_full")]
    if not items:
        return
    pick = items[:rows]
    src_y0 = max(0, min(it.sy0 for it in pick) - 8)
    src_y1 = min(rgb.shape[0], max(it.sy1 for it in pick) + 8)
    out_y0 = max(0, min(it.dy0 for it in pick) - 8)
    out_y1 = min(after.height, max(it.dy0 + it.dh for it in pick) + 8)

    width, panel_w, pad, label_h = 1240, 560, 40, 54
    left = Image.fromarray(rgb).crop((0, src_y0, rgb.shape[1], src_y1))
    left = left.resize((panel_w, round(left.height * panel_w / left.width)), Image.LANCZOS)
    right = after.crop((0, out_y0, after.width, out_y1))
    right = right.resize((panel_w, round(right.height * panel_w / right.width)), Image.LANCZOS)

    panel_h = max(left.height, right.height)
    sheet_h = label_h + panel_h + 46
    sheet = Image.new("RGB", (width, sheet_h), (250, 250, 252))
    d = ImageDraw.Draw(sheet)
    font = ImageFont.truetype(FONT, 22)
    font_s = ImageFont.truetype(FONT, 18)

    d.text((pad, 16), "原图（按屏宽显示）", font=font, fill=(60, 60, 64))
    d.text((pad + panel_w + 40, 16), f"重排后（字号 ×{plan.scale:.1f}）", font=font,
           fill=(20, 90, 170))
    sheet.paste(left, (pad, label_h))
    sheet.paste(right, (pad + panel_w + 40, label_h))
    d.rectangle((pad, label_h, pad + panel_w, label_h + panel_h), outline=(214, 214, 220), width=1)
    d.rectangle((pad + panel_w + 40, label_h, pad + panel_w + 40 + panel_w, label_h + panel_h),
                outline=(214, 214, 220), width=1)
    d.text((pad, sheet_h - 32),
           f"源图 {rgb.shape[1]}×{rgb.shape[0]} · 切分列 x={lay.cut} · 输出 {plan.out_w}×{plan.height}",
           font=font_s, fill=(110, 110, 116))
    sheet.save(path)


# ---------------------------------------------------------------- 主流程


def run_case(name: str, path: Path, out_w: int, target_em: int) -> dict:
    rgb = np.asarray(Image.open(path).convert("RGB"))
    t0 = time.perf_counter()
    lay, _ = analyze(rgb)
    t1 = time.perf_counter()
    plan = plan_reflow(lay, out_w, target_em)
    after = render_plan(rgb, plan, lay) if plan.items else Image.fromarray(rgb).copy()
    t2 = time.perf_counter()

    after.save(OUT / f"after_{name}.png")
    overlay(rgb, lay, OUT / f"debug_{name}.png")
    if plan.items:
        compare_sheet(rgb, after, plan, lay, OUT / f"compare_{name}.png")

    return {
        "case": name,
        "source": f"{rgb.shape[1]}x{rgb.shape[0]}",
        "lines": len([l for l in lay.lines if l.kind == "text"]),
        "figures": len([l for l in lay.lines if l.kind == "figure"]),
        "median_line_h": round(lay.median_h, 1),
        "cut_x": lay.cut,
        "cut_clean_ratio": round(lay.cut_depth, 3),
        "scale": round(plan.scale, 3),
        "items": len(plan.items),
        "output": f"{plan.out_w}x{plan.height}",
        "notes": lay.notes + plan.notes,
        "analyze_ms": round((t1 - t0) * 1000, 1),
        "render_ms": round((t2 - t1) * 1000, 1),
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", type=Path, default=None)
    ap.add_argument("--out-width", type=int, default=1080)
    ap.add_argument("--target-em", type=int, default=44)
    ap.add_argument("--make-fixture", action="store_true")
    args = ap.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    src = OUT / "source.png"
    dark = OUT / "source_dark.png"
    photo = OUT / "source_photo.png"
    if args.make_fixture or not src.exists():
        make_fixture(src)
        make_fixture(dark, dark=True)
        make_photo_only(photo)

    if args.input:
        stats = [run_case(args.input.stem, args.input, args.out_width, args.target_em)]
    else:
        stats = [
            run_case("main", src, args.out_width, args.target_em),
            run_case("dark", dark, args.out_width, args.target_em),
            run_case("photo", photo, args.out_width, args.target_em),
        ]
    print(json.dumps(stats, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
