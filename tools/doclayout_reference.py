#!/usr/bin/env python
"""PP-DocLayout-S reference runner —— 只用于对拍，不随 APK 分发。

把一张图片按 M0 实测的模型契约做预处理，跑 ONNX（onnxruntime CPU），做后处理，
输出 boxes.json（坐标固定在 **480×480 输入空间**）。可选画框生成 overlay 调试图。

实测契约（见 models/doclayout/CONVERSION.md）：
  inputs : image        [1,3,480,480] float32   RGB, /255, mean/std, NCHW
           scale_factor [1,2]         float32   坐标回映射因子；喂 [1,1] 时输出保持 480 空间
                                               （实测 out = box_480 / scale_factor）
  outputs: fetch_name_0 [N,6] float32  已做类内 NMS，列 = class_id, score, x1, y1, x2, y2
           fetch_name_1 [1]   int32     有效框数 N（= fetch_name_0 的第 0 维，未填充）
  坐标   : 0–480 像素（非 0–1 归一化）；stretch 到 480×480，不保持比例、不 padding
  NMS    : 图内已含 multiclass_nms3（iou 0.6 / score 0.025 / keep_top_k 100）
           因此图内阈值很低，**必须**在客户端再按 score 阈值过滤（config.json: 0.3）

用法：
  python tools/doclayout_reference.py \
      --model models/doclayout/PP-DocLayout-S.onnx \
      --image testdata/layout/screens/01-article-light@1080.png \
      --out testdata/out/ref/01-article-light.json \
      --overlay testdata/out/ref/01-article-light-overlay.png

退出码：0 成功；2 输入/模型问题（文件不存在、签名不符）。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time

import numpy as np
from PIL import Image, ImageDraw, ImageFont

INPUT_SIZE = 480
MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)

# 与 models/doclayout/config.json 的 label_list 一致；运行时优先从 --labels 或
# <model_dir>/config.json 读取，读不到才用这份兜底。
FALLBACK_LABELS = [
    "paragraph_title",
    "image",
    "text",
    "number",
    "abstract",
    "content",
    "figure_title",
    "formula",
    "table",
    "table_title",
    "reference",
    "doc_title",
    "footnote",
    "header",
    "algorithm",
    "footer",
    "seal",
    "chart_title",
    "chart",
    "formula_number",
    "header_image",
    "footer_image",
    "aside_text",
]

# 固定调色板：按 class_id 取模，保证同一类别在不同图上颜色一致。
PALETTE = [
    (230, 25, 75), (60, 180, 75), (255, 225, 25), (0, 130, 200), (245, 130, 48),
    (145, 30, 180), (70, 240, 240), (240, 50, 230), (210, 245, 60), (250, 190, 190),
    (0, 128, 128), (170, 110, 40), (128, 0, 0), (128, 128, 0), (0, 0, 128),
    (170, 255, 195), (128, 128, 128), (255, 250, 200), (0, 200, 0), (120, 120, 255),
    (255, 150, 150), (100, 100, 100), (200, 0, 255),
]


def load_labels(model_path: str, labels_path: str | None) -> tuple[list[str], str]:
    """返回 (labels, 来源说明)。"""
    candidates = []
    if labels_path:
        candidates.append(labels_path)
    else:
        model_dir = os.path.dirname(os.path.abspath(model_path))
        candidates.append(os.path.join(model_dir, "config.json"))
    for p in candidates:
        if not os.path.isfile(p):
            continue
        try:
            with open(p, "r", encoding="utf-8") as f:
                cfg = json.load(f)
            labels = cfg.get("label_list")
            if isinstance(labels, list) and labels:
                return [str(x) for x in labels], p
        except Exception as exc:  # noqa: BLE001 - 兜底比崩掉好，但要报出来
            print(f"[warn] 读取 labels 失败 {p}: {exc}", file=sys.stderr)
    return FALLBACK_LABELS, "builtin-fallback"


def preprocess(image: Image.Image) -> np.ndarray:
    """stretch 到 480×480 → /255 → mean/std → HWC→CHW → [1,3,480,480] float32。"""
    rgb = image.convert("RGB").resize((INPUT_SIZE, INPUT_SIZE), Image.BILINEAR)
    arr = np.asarray(rgb, dtype=np.float32) / 255.0
    arr = (arr - MEAN) / STD
    chw = np.transpose(arr, (2, 0, 1))
    return np.ascontiguousarray(chw[None], dtype=np.float32)


def nms_classwise(boxes: np.ndarray, scores: np.ndarray, iou_thres: float) -> list[int]:
    """标准贪心 NMS（单类别，xyxy）。返回保留的下标（按 score 降序）。"""
    order = scores.argsort()[::-1]
    x1, y1, x2, y2 = boxes[:, 0], boxes[:, 1], boxes[:, 2], boxes[:, 3]
    areas = np.maximum(0.0, x2 - x1) * np.maximum(0.0, y2 - y1)
    keep: list[int] = []
    while order.size > 0:
        i = int(order[0])
        keep.append(i)
        if order.size == 1:
            break
        rest = order[1:]
        xx1 = np.maximum(x1[i], x1[rest])
        yy1 = np.maximum(y1[i], y1[rest])
        xx2 = np.minimum(x2[i], x2[rest])
        yy2 = np.minimum(y2[i], y2[rest])
        inter = np.maximum(0.0, xx2 - xx1) * np.maximum(0.0, yy2 - yy1)
        union = areas[i] + areas[rest] - inter
        iou = np.where(union > 0, inter / np.maximum(union, 1e-9), 0.0)
        order = rest[iou <= iou_thres]
    return keep


def postprocess(raw: np.ndarray, labels: list[str], score_thres: float, iou_thres: float
                ) -> list[dict]:
    """raw: [N,6] (class_id, score, x1, y1, x2, y2)，图内已 NMS，这里做阈值 + 类内 NMS。"""
    if raw.size == 0:
        return []
    boxes = raw[:, 2:6].astype(np.float32)
    scores = raw[:, 1].astype(np.float32)
    cls = raw[:, 0].astype(np.int32)

    # 图内 NMS 的 score_threshold 只有 0.025，低分垃圾框很多，必须在这里砍掉。
    keep = scores >= score_thres
    boxes, scores, cls = boxes[keep], scores[keep], cls[keep]
    if boxes.shape[0] == 0:
        return []

    # 图内已是 per-class NMS（iou 0.6）；这里按 config.json 的 0.5 再过一遍，语义等价于
    # "阈值 + 类别内 NMS"，对已 NMS 的结果只在 iou∈(0.5,0.6] 的框上有差别。
    kept: list[int] = []
    for c in np.unique(cls):
        idx = np.where(cls == c)[0]
        local = nms_classwise(boxes[idx], scores[idx], iou_thres)
        kept.extend(int(idx[j]) for j in local)

    out = []
    for i in sorted(kept, key=lambda k: -float(scores[k])):
        cid = int(cls[i])
        x1, y1, x2, y2 = [float(v) for v in boxes[i]]
        x1, x2 = sorted((x1, x2))
        y1, y2 = sorted((y1, y2))
        out.append({
            "label_id": cid,
            "label": labels[cid] if 0 <= cid < len(labels) else f"class_{cid}",
            "score": round(float(scores[i]), 6),
            "x1": round(x1, 2),
            "y1": round(y1, 2),
            "x2": round(x2, 2),
            "y2": round(y2, 2),
        })
    return out


def draw_overlay(image: Image.Image, boxes: list[dict], model_input: int) -> Image.Image:
    """把 480 空间的框映射回原图并画出来（stretch 逆变换：x*W/480, y*H/480）。"""
    canvas = image.convert("RGB").copy()
    draw = ImageDraw.Draw(canvas)
    w, h = canvas.size
    sx, sy = w / float(model_input), h / float(model_input)
    try:
        font = ImageFont.truetype("arial.ttf", 14)
    except Exception:  # noqa: BLE001
        font = ImageFont.load_default()
    for b in boxes:
        color = PALETTE[b["label_id"] % len(PALETTE)]
        xy = [b["x1"] * sx, b["y1"] * sy, b["x2"] * sx, b["y2"] * sy]
        draw.rectangle(xy, outline=color, width=3)
        text = f'{b["label"]} {b["score"]:.2f}'
        tb = draw.textbbox((0, 0), text, font=font)
        tw, th = tb[2] - tb[0], tb[3] - tb[1]
        tx, ty = xy[0], max(0.0, xy[1] - th - 4)
        draw.rectangle([tx, ty, tx + tw + 4, ty + th + 4], fill=color)
        draw.text((tx + 2, ty + 1), text, fill=(255, 255, 255), font=font)
    return canvas


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="PP-DocLayout-S ONNX 参考实现（对拍用，不进 APK）",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    ap.add_argument("--model", required=True, help="ONNX 模型路径")
    ap.add_argument("--image", required=True, help="输入图片")
    ap.add_argument("--out", required=True, help="boxes.json 输出路径")
    ap.add_argument("--overlay", default=None, help="可选：把框画到原图的调试图路径")
    ap.add_argument("--labels", default=None, help="label 列表来源（config.json），默认取模型同目录的 config.json")
    ap.add_argument("--score-threshold", type=float, default=0.3,
                    help="分数阈值（对应 config.json NMS.score_threshold）")
    ap.add_argument("--nms-iou", type=float, default=0.5,
                    help="类内 NMS IoU（对应 config.json NMS.nms_threshold）")
    ap.add_argument("--repeat", type=int, default=5, help="计时重复次数，取中位数")
    ap.add_argument("--scale-factor", type=float, nargs=2, default=(1.0, 1.0),
                    choices=None, metavar=("SY", "SX"),
                    help="喂给 scale_factor 输入的值；保持 [1,1] 时输出为 480 空间，改动会等比缩放输出坐标")
    args = ap.parse_args(argv)

    for p in (args.model, args.image):
        if not os.path.isfile(p):
            print(f"[error] 文件不存在: {p}", file=sys.stderr)
            return 2

    try:
        import onnxruntime as ort
    except ImportError:
        print("[error] 需要 onnxruntime（pip install onnxruntime）", file=sys.stderr)
        return 2

    labels, labels_src = load_labels(args.model, args.labels)

    so = ort.SessionOptions()
    so.log_severity_level = 3
    sess = ort.InferenceSession(args.model, so, providers=["CPUExecutionProvider"])
    in_names = [i.name for i in sess.get_inputs()]
    for required in ("image", "scale_factor"):
        if required not in in_names:
            print(f"[error] 模型输入里没有 {required}，实际: {in_names}", file=sys.stderr)
            return 2
    out_names = [o.name for o in sess.get_outputs()]

    image = Image.open(args.image)
    src_w, src_h = image.size
    x = preprocess(image)
    sf = np.array([list(args.scale_factor)], dtype=np.float32)

    feeds = {"image": x, "scale_factor": sf}
    t0 = time.perf_counter()
    outputs = sess.run(None, feeds)
    t1 = time.perf_counter()

    result = dict(zip(out_names, outputs))
    raw = np.asarray(result["fetch_name_0"], dtype=np.float32)
    count = int(np.asarray(result["fetch_name_1"]).reshape(-1)[0]) if "fetch_name_1" in result else raw.shape[0]
    if raw.size == 0:
        raw = np.zeros((0, 6), dtype=np.float32)
    if raw.shape[0] > count:  # 防御：老导出可能返回 keep_top_k 填充行
        raw = raw[:count]

    boxes = postprocess(raw, labels, args.score_threshold, args.nms_iou)

    times = []
    if args.repeat > 0:
        for _ in range(args.repeat):
            s = time.perf_counter()
            sess.run(None, feeds)
            times.append((time.perf_counter() - s) * 1000.0)

    doc = {
        "image": os.path.abspath(args.image),
        "image_size": [src_w, src_h],
        "model": os.path.abspath(args.model),
        "input_size": [INPUT_SIZE, INPUT_SIZE],
        "coordinate_space": f"{INPUT_SIZE}x{INPUT_SIZE} (pixels, not normalized)",
        "labels_source": labels_src,
        "score_threshold": args.score_threshold,
        "nms_iou": args.nms_iou,
        "scale_factor": list(args.scale_factor),
        "raw_boxes": int(raw.shape[0]),
        "count": len(boxes),
        "timing_ms": {
            "first_run": round((t1 - t0) * 1000.0, 2),
            "median": round(float(np.median(times)), 2) if times else None,
        },
        "boxes": boxes,
    }

    out_dir = os.path.dirname(os.path.abspath(args.out))
    os.makedirs(out_dir, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)

    if args.overlay:
        ov_dir = os.path.dirname(os.path.abspath(args.overlay))
        os.makedirs(ov_dir, exist_ok=True)
        draw_overlay(image, boxes, INPUT_SIZE).save(args.overlay)

    print(f"image      : {args.image} ({src_w}x{src_h})")
    print(f"model      : {args.model}")
    print(f"labels     : {len(labels)} 类（{labels_src}）")
    print(f"raw/count  : {raw.shape[0]} -> {len(boxes)} boxes "
          f"(score>={args.score_threshold}, iou={args.nms_iou})")
    print(f"timing     : first {doc['timing_ms']['first_run']} ms, "
          f"median {doc['timing_ms']['median']} ms over {len(times)} runs (onnxruntime CPU)")
    print(f"wrote      : {os.path.abspath(args.out)}")
    for b in boxes[:5]:
        print(f"  #{b['label_id']:>2} {b['label']:<16} {b['score']:.3f} "
              f"[{b['x1']:.1f}, {b['y1']:.1f}, {b['x2']:.1f}, {b['y2']:.1f}]")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
