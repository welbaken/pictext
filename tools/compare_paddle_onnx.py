#!/usr/bin/env python
"""官方 Paddle 推理 vs ONNX 推理 对拍（docs/07 V2-00 的 "≤2px" 验收）。

只用于对拍，不随 APK。需要装了 paddlepaddle 的环境（`tools/.venv-doclayout`），
ONNX 侧只需要 onnxruntime。

它做的事：
  1. 对每张图用**同一套**预处理（`doclayout_reference.preprocess`）；
  2. 分别跑 `paddle.inference`（inference.json + pdiparams）与 ONNX（onnxruntime CPU）；
  3. **同一套**后处理（score 阈值 + 类内 NMS，`doclayout_reference.postprocess`）；
  4. 写出 `<name>_paddle.json`（官方基准）与 `paddle_vs_onnx.json`（差异汇总）。

用法：
  python tools/compare_paddle_onnx.py \
      --paddle-dir models/doclayout \
      --onnx models/doclayout/PP-DocLayout-S.onnx \
      --out-dir testdata/out/ref \
      testdata/layout/screens/01-article-light@1080.png \
      testdata/layout/screens/03-news-columns@1080.png

退出码：0 全部对齐（≤2px 且类别数一致）；1 有超差；2 环境/文件问题。
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import doclayout_reference as ref  # noqa: E402

INPUT_SIZE = 480


def paddle_session(paddle_dir: str):
    import paddle.inference as pi

    cfg = pi.Config(os.path.join(paddle_dir, "inference.json"),
                    os.path.join(paddle_dir, "inference.pdiparams"))
    cfg.disable_gpu()
    return pi.create_predictor(cfg)


def paddle_raw(pred, x: np.ndarray) -> np.ndarray:
    pred.get_input_handle("image").copy_from_cpu(x)
    pred.get_input_handle("scale_factor").copy_from_cpu(
        np.array([[1.0, 1.0]], dtype=np.float32))
    pred.run()
    for name in pred.get_output_names():
        o = pred.get_output_handle(name).copy_to_cpu()
        if o.ndim == 2 and o.shape[1] == 6:
            return o.astype(np.float32)
    raise RuntimeError("paddle 输出里没有 [N,6] 张量")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="PP-DocLayout-S 官方 Paddle vs ONNX 对拍")
    ap.add_argument("--paddle-dir", default="models/doclayout", help="含 inference.json/pdiparams 的目录")
    ap.add_argument("--onnx", default="models/doclayout/PP-DocLayout-S.onnx")
    ap.add_argument("--out-dir", default="testdata/out/ref")
    ap.add_argument("--score-threshold", type=float, default=0.3)
    ap.add_argument("--nms-iou", type=float, default=0.5)
    ap.add_argument("images", nargs="+")
    args = ap.parse_args(argv)

    try:
        import onnxruntime as ort
    except ImportError:
        print("[error] 需要 onnxruntime", file=sys.stderr)
        return 2
    for p in (args.onnx, os.path.join(args.paddle_dir, "inference.json")):
        if not os.path.isfile(p):
            print(f"[error] 文件不存在: {p}", file=sys.stderr)
            return 2

    labels, _ = ref.load_labels(args.onnx, None)
    pred = paddle_session(args.paddle_dir)
    so = ort.SessionOptions()
    so.log_severity_level = 3
    sess = ort.InferenceSession(args.onnx, so, providers=["CPUExecutionProvider"])

    os.makedirs(args.out_dir, exist_ok=True)
    per_image: dict[str, dict] = {}
    failed = False
    for path in args.images:
        name = os.path.splitext(os.path.basename(path))[0].split("@")[0]
        img = Image.open(path)
        x = ref.preprocess(img)
        sf = np.array([[1.0, 1.0]], dtype=np.float32)

        pd_raw = paddle_raw(pred, x)
        onx_raw = sess.run(None, {"image": x, "scale_factor": sf})[0].astype(np.float32)

        pd_boxes = ref.postprocess(pd_raw, labels, args.score_threshold, args.nms_iou)
        onx_boxes = ref.postprocess(onx_raw, labels, args.score_threshold, args.nms_iou)

        with open(os.path.join(args.out_dir, name + "_paddle.json"), "w", encoding="utf-8") as f:
            json.dump({
                "source": "paddle.inference on %s" % os.path.join(args.paddle_dir, "inference.json"),
                "image": os.path.abspath(path),
                "image_size": list(img.size),
                "input_size": [INPUT_SIZE, INPUT_SIZE],
                "coordinate_space": f"{INPUT_SIZE}x{INPUT_SIZE} (pixels, not normalized)",
                "scale_factor": [1.0, 1.0],
                "score_threshold": args.score_threshold,
                "nms_iou": args.nms_iou,
                "raw_boxes": int(pd_raw.shape[0]),
                "count": len(pd_boxes),
                "boxes": pd_boxes,
            }, f, ensure_ascii=False, indent=2)

        n = min(len(pd_boxes), len(onx_boxes))
        dmax, cls_mismatch = 0.0, 0
        for a, b in zip(pd_boxes[:n], onx_boxes[:n]):
            cls_mismatch += int(a["label_id"] != b["label_id"])
            dmax = max(dmax, abs(a["x1"] - b["x1"]), abs(a["y1"] - b["y1"]),
                       abs(a["x2"] - b["x2"]), abs(a["y2"] - b["y2"]))
        raw_n = min(len(pd_raw), len(onx_raw))
        raw_max = float(np.abs(pd_raw[:raw_n] - onx_raw[:raw_n]).max()) if raw_n else None
        ok = (len(pd_boxes) == len(onx_boxes)) and cls_mismatch == 0 and dmax <= 2.0
        failed |= not ok
        per_image[name] = {
            "paddle_boxes": len(pd_boxes),
            "onnx_boxes": len(onx_boxes),
            "same_count": len(pd_boxes) == len(onx_boxes),
            "class_mismatch": cls_mismatch,
            "max_coord_diff_px": round(dmax, 6),
            "max_abs_diff_raw_tensor": None if raw_max is None else float(f"{raw_max:.3e}"),
            "raw_rows": int(raw_n),
            "pass": ok,
        }
        print(f"{name}: {per_image[name]}")

    with open(os.path.join(args.out_dir, "paddle_vs_onnx.json"), "w", encoding="utf-8") as f:
        json.dump({
            "criterion": "docs/07 V2-00: paddle vs onnx box error <= 2px",
            "paddle_version": __import__("paddle").__version__,
            "onnxruntime_version": ort.__version__,
            "per_image": per_image,
            "pass": not failed,
        }, f, ensure_ascii=False, indent=2)

    print(f"\n{'PASS' if not failed else 'FAIL'}: 详见 {os.path.join(args.out_dir, 'paddle_vs_onnx.json')}")
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
