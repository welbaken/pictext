# PP-DocLayout-S · 模型资产

版面区域检测模型。**只负责“这是什么区域、大致在哪”**；阅读顺序、行切点、字号一律由
`core/` 的投影链路计算（docs/07 §0、§12）。

> **0.5 起不随 APK 分发**：App 只跑 v1，release 里没有 ORT、没有模型、没有「强化重排」入口。
> 这个目录给 `:cli` 的技术验证用（`--layout` / `--detections`），并由
> `scripts/verify-v1-only.ps1` 保证它不会混进发行包。

## 来源

| 项 | 值 |
|---|---|
| 模型 | PP-DocLayout-S（PaddleOCR 3.x 版面检测） |
| 仓库 | <https://huggingface.co/PaddlePaddle/PP-DocLayout-S> |
| 文档 | <https://www.paddleocr.ai/main/version3.x/module_usage/layout_detection.html> |
| 下载日期 | 2026-10-30 |
| 归档形式 | Paddle Inference 3.0：`inference.json` + `inference.pdiparams`（**不是** ONNX） |
| 许可 | **Apache-2.0**（`MODEL_CARD.md` 的 `license:` 字段；`LICENSE.txt` 为原文） |
| 体积 | 约 4.8MB（权重 4,804,904 bytes） |
| mAP(0.5) | 70.9（官方 500 张**文档**图评估集，不含手机截图） |

## 文件

```text
models/doclayout/
├─ contract.json        # 契约：预处理/后处理/标签/哈希，代码只认这个
├─ inference.json       # 官方 program（Paddle 3.0）
├─ inference.pdiparams  # 官方权重
├─ inference.yml        # 官方预处理/后处理配置（契约的事实来源）
├─ config.json          # 官方模型配置（label_list 的事实来源）
├─ MODEL_CARD.md        # 官方模型卡（含许可证与指标）
├─ LICENSE.txt          # Apache-2.0 原文（来自 PaddleOCR 仓库）
├─ SHA256SUMS           # 所有资产的 SHA-256
├─ CONVERSION.md        # Paddle → ONNX 的转换与实测记录（含失败记录与差异清单）
└─ PP-DocLayout-S.onnx  # 转换产物（opset 13，4.69MiB，已与官方 Paddle 对拍 ≤0.01px）
```

> ONNX 已产出并验证：`status=verified`、opset 13、`sha256=3cfcad68…162e9`、
> 与官方 Paddle 3.1.1 输出对拍 25/25、31/31 框、最大坐标差 **0.01px**。
> 转换必须装 `paddlepaddle`（paddle2onnx 2.1.0 的 native 扩展链接了 `libpaddle.pyd`），
> 这是**转换期**依赖，产品路径完全不依赖 paddle。

## 契约要点（已从 `inference.yml` / `config.json` / ONNX 实测三方核对）

- 输入：`image [1,3,480,480]`（NCHW，float32）+ `scale_factor [1,2]`（产品恒喂 `[1.0, 1.0]`）；
- 预处理：**直接拉伸**到 480×480（`keep_ratio: false`）→ `/255` → `(v - mean)/std` → HWC→CHW，
  `mean=[0.485,0.456,0.406]`、`std=[0.229,0.224,0.225]`；
- 输出：`fetch_name_0 [N,6]`（`class_id, score, x1, y1, x2, y2`，**N 动态**，已含图内 NMS）
  + `fetch_name_1 [1]`（= N）；坐标是 **0–480 像素、非归一化、不裁剪**；
- 后处理：图内 NMS 阈值只有 0.025/0.6，**0.4 阈值 + 0.5 类内 NMS 必须客户端做**；
- 标签：23 类，顺序即 id（`label_list[0] = paragraph_title`），见 `contract.json` 的 `labels`。

> 480×480 是**官方**的 `target_size`，不是社区 ONNX 口径；`img_size` 固定（锚点 4789 = 60²+30²+15²+8²），
> “换更大 tile”这条路不存在。

## 使用约束

1. `:core` 只依赖 `ModelContract` / `LayoutDetector` 接口，**不加载 native 库**；
   ORT 适配器只存在于 `:cli`（桌面）。**不要**再往 `:app` 里加 ORT 依赖——0.5 的发行口径是 v1-only。
2. 模型只提供 ROI：渲染位置必须来自细化后的 `TextLine.band`，**禁止**用模型框直接裁像素
   （480 输入到 1080 页面的量化误差有 18–144px，会切字、切图）。
3. 漏检不丢内容：任何改动都要保住覆盖审计（`CoverageAuditor`）与残差补区。
4. 模型文件统一放 `models/`；CLI 直接读这里，没有 `assets` 打包环节。
   若将来产品侧重新接入，用 `assets.srcDir` 引用本目录，不要在 `app/src/main/assets` 里再放一份。

## 复现

```bash
# 只对拍 ONNX 推理（不进 APK）
python tools/doclayout_reference.py --model models/doclayout/PP-DocLayout-S.onnx \
    --image testdata/layout/screens/01-article-light@1080.png --out testdata/out/ref
```

转换步骤与实测输入输出签名见 `CONVERSION.md`。
