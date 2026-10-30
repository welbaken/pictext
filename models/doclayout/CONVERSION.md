# PP-DocLayout-S · Paddle → ONNX 转换与输入输出契约（M0 / V2-00）

> 本文记录 **实测** 结果，不是按文档抄的。每条数字都有命令输出或文件哈希对应。
> 转换日期（本机）：**2026-10-30**，Windows 11 + Python 3.10.11。
> 覆盖 `docs/07-v2-plan.md` §4.2 里 M0 必须回答的 9 个问题（见文末对照表）。

---

## 1. 来源与输入文件

| 项 | 值 |
|---|---|
| 来源 | <https://huggingface.co/PaddlePaddle/PP-DocLayout-S> |
| 仓库内路径 | `models/doclayout/` |
| 形态 | Paddle 3.0 新格式（PIR）推理模型，非 `.tar`、非 ONNX |
| 下载时间 | **未由本任务下载**：文件在本任务开始前已由他人放入仓库，仓库内没有下载日志。只能给出文件系统 mtime：`inference.json` 2026-10-30 16:42:01、`inference.pdiparams` 2026-10-30 16:43:17、`config.json` 2026-10-30 16:42:02（本机时区 +08:00）。若需权威下载时间，请向放入文件的人确认。 |
| 许可 | Apache-2.0（据 `MODEL_CARD.md:2` 的 `license: apache-2.0`） |

输入文件实测大小与 SHA-256（`Get-FileHash -Algorithm SHA256`）：

| 文件 | 字节数 | SHA-256 |
|---|---|---|
| `inference.json` | 339,876 | `AC09E931895D4C442E5379AB3B7E9B583BAF288E816AB7675CA519DA9EB2A9D7` |
| `inference.pdiparams` | 4,804,904 | `491C3382D84CA04D2033AFBEE0C105942ED82FEA392BB4A19170646ADEBE088A` |
| `config.json` | 4,715 | `45F5757DAF01694C4ADBA0EBA7FF2854CAF011F02C91BDBD2EF6EB06D7A0F488` |

---

## 2. 工具链与版本

转换环境是仓库内的隔离 venv：`tools/.venv-doclayout`（**不入库**，见 §9）。

| 组件 | 版本 | 说明 |
|---|---|---|
| Python | 3.10.11 | 系统 Python，venv 独立 |
| `paddle2onnx` | **2.1.0** | 转换器本体 |
| `paddlepaddle` | **3.1.1**（CPU） | **仅为转换 + 对拍而装**；paddle2onnx 2.1.0 无法脱离它运行（§3 失败记录），对拍要用 `paddle.inference`。产品路径（APK）不使用 paddle。 |
| `onnx` | 1.17.0 | paddle2onnx 2.1.0 要求 `onnx<=1.17.0` |
| `onnxruntime` | 1.23.2 | CPUExecutionProvider，实测推理用 |
| `numpy` | 2.2.6 | |
| `Pillow` | 12.3.0 | 预处理/画框 |
| opset | **13** | 见 §4 日志 `Use opset_version = 13` |
| 转化时自动装上 | `polygraphy 0.53.6`、`onnx_graphsurgeon` | paddle2onnx 内部做常量折叠用 |

> 备注：paddle2onnx 2.1.0 在 Windows 上**显式拒绝 `paddlepaddle==3.0.0`**
> （`paddle2onnx/__init__.py:38-44` 要求 `>=3.0.0.dev20250426` 且 `!=3.0.0`），所以选 3.1.1。

---

## 3. 失败记录（先失败，后成功；如实保留）

### 3.1 方式一：官方 CLI，不装 paddlepaddle → 直接 ImportError

```
$ python -m paddle2onnx --model_dir ... --opset_version 13
Traceback (most recent call last):
  File "...\paddle2onnx\__init__.py", line 25, in <module>
    import paddle
ModuleNotFoundError: No module named 'paddle'
...
ImportError: Failed to import paddle. Please ensure paddle is installed.
```

根因不是"少一个 import 检查"，而是**二进制依赖**：用 PE 导入表解析
`paddle2onnx_cpp2py_export.cp310-win_amd64.pyd` 得到：

```
IMPORTS: libpaddle.pyd, common.dll, python310.dll, KERNEL32.dll, MSVCP140.dll, ...
```

`libpaddle.pyd` / `common.dll` 是 paddlepaddle 的运行时。⇒ **paddle2onnx 2.1.0 转换 Paddle 3.0 PIR 格式必须装 paddlepaddle，没有纯 Python 旁路。**

### 3.2 方式二：paddle2onnx 1.3.1（pyd 不依赖 libpaddle）+ 直接调原生 `export()`

1.3.1 的 pyd 导入表里没有 `libpaddle.pyd`，可以在无 paddle 环境加载；
但其解析器不认识 PIR JSON：

```
[Paddle2ONNX] Start to parse PaddlePaddle model...
[Paddle2ONNX] Parameters file path: D:\SRC\pictext\models\doclayout\inference.pdiparams
[Paddle2ONNX] Failed to parse paddlepaddle model from read content.
[Paddle2ONNX] Failed to load program of PaddlePaddle model.
[Paddle2ONNX] Paddle model parsing failed.
[Paddle2ONNX] Paddle model convert failed.
```

产出的 onnx 为 **0 字节**，已删除，未入库。

### 3.3 方式三：装 `paddlepaddle==3.1.1` 后用官方 CLI → **成功**

见 §4。

---

## 4. 实际跑通的完整命令（原样可复现）

```powershell
# 1) 建隔离 venv（仓库内，不入库）
python -m venv D:\SRC\pictext\tools\.venv-doclayout

# 2) 装工具链（清华源优先）
D:\SRC\pictext\tools\.venv-doclayout\Scripts\python.exe -m pip install `
    paddle2onnx onnx onnxruntime numpy Pillow -i https://pypi.tuna.tsinghua.edu.cn/simple

# 3) 仅为转换安装 CPU 版 paddlepaddle（产品路径不需要）
D:\SRC\pictext\tools\.venv-doclayout\Scripts\python.exe -m pip install `
    "paddlepaddle==3.1.1" -i https://pypi.tuna.tsinghua.edu.cn/simple

# 4) 转换（在仓库根目录执行；这是真正跑通的那条）
tools\.venv-doclayout\Scripts\paddle2onnx.exe `
    --model_dir models/doclayout `
    --model_filename inference.json `
    --params_filename inference.pdiparams `
    --save_file models/doclayout/PP-DocLayout-S.onnx `
    --opset_version 13 `
    --enable_onnx_checker True
```

> 注意：paddle2onnx 2.1.0 的 wheel **没有 `__main__.py`**，`python -m paddle2onnx` 跑不起来，
> 必须用 venv 里的 `paddle2onnx.exe`（等价于 `paddle2onnx.command:main`）。

成功日志（原样，耗时 26.4s）：

```
[Paddle2ONNX] Start parsing the Paddle model file...
[Paddle2ONNX] Use opset_version = 13 for ONNX export.
[WARNING][Paddle2ONNX] [OP: pd_op.multiclass_nms3] Due to the operator multiclass_nms3,
  the exported ONNX model will only supports inference with input batch_size == 1.
[Paddle2ONNX] PaddlePaddle model is exported as ONNX format now.
2026-10-30 17:14:22 [INFO] Try to perform constant folding on the ONNX model with Polygraphy.
[I]     Total Nodes | Original:  1156, After Folding:   568 |   588 Nodes Folded
2026-10-30 17:14:45 [INFO] ONNX model saved in models/doclayout/PP-DocLayout-S.onnx.
```

### 4.1 产物

| 项 | 值 |
|---|---|
| 文件 | `models/doclayout/PP-DocLayout-S.onnx` |
| 大小 | **4,914,725 字节**（4.69 MiB） |
| SHA-256 | `3CFCAD683909820EAB27CACEAB7A4F9EC368FC3E055853B609D9FD1C55B162E9` |
| opset | 13（`opset_import: [("", 13)]`） |
| IR | 7 |
| 节点数 | 568（常量折叠后） |

---

## 5. 输入 / 输出签名（onnxruntime 1.23.2 CPU 实测）

onnxruntime `InferenceSession.get_inputs() / get_outputs()` 与 ONNX 图定义一致：

```
== inputs ==
  image          [1, 3, 480, 480]   tensor(float)
  scale_factor   [1, 2]             tensor(float)
== outputs ==
  fetch_name_0   [N, 6]             tensor(float)    # N 为动态维，符号名 TopK_555_o0__d0
  fetch_name_1   [1]                tensor(int32)
```

### 5.1 输入

| 名字 | 形状 | dtype | 语义 |
|---|---|---|---|
| `image` | **固定** `[1, 3, 480, 480]`，NCHW | float32 | RGB、`/255`、mean `[0.485,0.456,0.406]`、std `[0.229,0.224,0.225]`。形状是**写死的**：喂 320×320 或 batch=2 都会被 ORT 拒绝（见 §7 实测报错）。 |
| `scale_factor` | `[1, 2]` | float32 | 坐标回映射因子。**实测语义：输出坐标 = 480 空间坐标 ÷ scale_factor**（§5.5）。产品侧固定喂 `[1.0, 1.0]`，让输出留在 480 空间。 |

### 5.2 输出

| 名字 | 形状 | dtype | 语义 |
|---|---|---|---|
| `fetch_name_0` | `[N, 6]`，N ≤ 100 且随输入变化 | float32 | **已做类内 NMS 的检测框**，列顺序 `class_id, score, x1, y1, x2, y2`（`class_id` 是 float 存的 0–22 整数） |
| `fetch_name_1` | `[1]` | int32 | 有效框数量 N（Paddle `multiclass_nms3` 的 `NmsRoisNum`），实测恒等于 `fetch_name_0.shape[0]` |

关键点（都与 `docs/07` §4.2 的猜测不同，见 §8）：

* **输出不是 raw head，是 NMS 后的 `[N,6]`**。图内 `multiclass_nms3` 参数（从 `inference.json` 的 PIR 原文读出）：
  `score_threshold=0.025`、`nms_top_k=1000`、`keep_top_k=100`、`nms_threshold=0.6`、`normalized=true`、`nms_eta=1.0`、`background_label=-1`。
  NMS 前的 raw 头形状为 `[-1, 4789, 4]` + 分类头。
* `4789 = 60² + 30² + 15² + 8²`（stride 8/16/32/64 在 480×480 上的网格）⇒ 进一步证明**模型只支持 480×480**。
* **`N` 不是恒定 100**：白图输入时 `N=7`；本仓库两张截图分别 `N=100`（正好顶到 `keep_top_k` 上限）。
  所以客户端**不能**假设"总有 100 行、用 -1 或 0 填充"，要么读 `fetch_name_1`，要么用 `fetch_name_0` 的实际第 0 维。
* 图内阈值只有 **0.025**，所以低分垃圾框会被保留到 `N` 行里：
  白图的 7 个框 score 只有 **0.0297–0.0525**，真实截图的 100 行里也有一堆 0.03 分的框。
  ⇒ **客户端必须自己按 score 阈值（`config.json` 的 0.3）过滤**，否则会在空白区画出大量假框。

### 5.3 列语义的证据

真实截图（`testdata/layout/screens/01-article-light@1080.png`，stretch 到 480×480）`scale_factor=[1,1]` 的前 5 行：

```
[0.0, 0.7539,  24.78, 48.40, 247.23, 72.72]   # class 0 = paragraph_title, 标题区
[0.0, 0.7309,  30.68, 179.77, 165.22, 187.25]
[0.0, 0.5754,  30.61, 368.97, 172.07, 376.60]
[0.0, 0.1964, 407.08, 295.63, 444.43, 301.23]
[0.0, 0.1688,  18.01, 62.27,  91.80, 73.02]
```

第 0 列全是 0（`paragraph_title`，与 `label_list[0]` 吻合），第 1 列是 0–1 分数，
2–5 列是 `x1< x2`、`y1 < y2` 的像素坐标。白图那次的第 0 列出现 `2,7,15,17`（`text/figure_title/algorithm/footer`
一类），也符合"第 0 列是 class_id"。

### 5.4 坐标是否归一化：**0–480 像素，不是 0–1**

* 真实截图的框 `x1` 范围 **17.3–445.3**、`y1` 范围 **14.5–456.4**，数值远超 1，不可能归一化。
* 白图输入也出现 `x2=558.19`、`y2=488.85` —— **超出 480 的框没有被裁剪**，
  说明输出就是网络输入尺度下的原始像素坐标，允许越界，客户端需要自己 clamp 到 `[0,480]`。

### 5.5 `scale_factor` 语义（同一张图，只改这个输入）

| `scale_factor` | 第 1 个框 x1 | 全部框 x 范围 | 结论 |
|---|---|---|---|
| `[1.0, 1.0]` | 24.7781 | 17.33 – 445.27 | 480 空间 |
| `[2.25, 2.25]` | 11.0125 | 7.70 – 197.90 | 坐标 ÷2.25 |
| `[0.4444, 0.4444]` | 55.7563 | 39.00 – 1001.95 | 坐标 ÷0.4444 ≈ ×2.25 → 1080 空间 |

即 `out_box = box_480 / scale_factor`。`0.4444 ≈ 480/1080` 时输出直接落回原图 1080 空间，
所以这个输入在官方部署里很可能就是 `net_size / origin_size` 的比例；**但目前没有官方文档/源码逐行确认**（见 §8 不确定项）。
M0 的结论：**固定喂 `[1, 1]`，坐标全程按 480 空间处理**，映射回原图由客户端自己按 `W/480, H/480` 做。

---

## 6. 预处理（实测生效，与 `config.json` 逐条对应）

`config.json:11-38` 的 `Preprocess` 三段：

1. `Resize`：`target_size [480,480]`、`keep_ratio false`、`interp 2`（BILINEAR）⇒ **直接拉伸，不保持比例、不 padding**；
2. `NormalizeImage`：`is_scale true`（先 `/255`）、mean `[0.485,0.456,0.406]`、std `[0.229,0.224,0.225]`；
3. `Permute`：HWC → CHW。

参考脚本 `tools/doclayout_reference.py` 按此实现（`preprocess()`），实测输出合理（见 §6.1）。

### 6.1 两张真实截图的结果（`tools/doclayout_reference.py`，score≥0.3 + 类内 NMS iou 0.5）

| 图片 | 原图尺寸 | 图内框数 | 过滤后 | 首跑 | 5 次中位数 |
|---|---|---|---|---|---|
| `01-article-light@1080.png` | 1080×1793 | 100 | **25** | 33.5 ms | 25.5 ms |
| `03-news-columns@1080.png` | 1080×1834 | 100 | **31** | 25.1 ms | 20.8 ms |

`01-article-light` 类别分布：`text 12, paragraph_title 3, header 3, header_image 2, footer 1, number 1, image 1, figure_title 1, chart 1`；
`03-news-columns`：`text 21, paragraph_title 4, header 2, doc_title 1, footer 1, image 1, table 1`。
前 5 个框（480 空间）：

`01-article-light`：
```
# 2 text             0.884 [17.7, 80.2, 256.9, 119.0]
# 2 text             0.849 [30.3, 194.0, 441.6, 211.2]
# 2 text             0.844 [30.7, 321.8, 447.7, 338.8]
# 2 text             0.820 [17.3, 127.6, 257.8, 155.2]
# 2 text             0.763 [30.3, 408.1, 442.6, 426.1]
```
`03-news-columns`：
```
# 2 text             0.870 [15.6, 206.5, 259.4, 250.8]
# 2 text             0.863 [14.3, 259.1, 258.7, 294.1]
# 2 text             0.845 [14.4, 315.1, 260.5, 340.4]
# 2 text             0.839 [14.9, 348.1, 260.7, 373.9]
# 2 text             0.826 [14.7, 415.2, 261.6, 441.3]
```
产物：`testdata/out/ref/*.json` + `*-overlay.png`（`testdata/out/` 已在 `.gitignore` 内）。
人工看 overlay：标题、正文段落、两张配图、图注、页眉页脚、页码位置**都对得上**，
可确认"模型真的在工作、坐标没系统性偏移"。

### 6.2 单次推理耗时

onnxruntime CPU、单线程默认设置、480×480 输入：**中位数 20.8 ms / 10 次**（§5 探针），
参考脚本里 5 次中位数 20.8–25.5 ms，首跑 25–33 ms（含 session 预热）。
机器未记录型号；**这个数字不能直接当作手机端预算**，只能作为"ONNX 图本身没问题"的证据。

---

## 7. 形状约束实测（ORT 报错原文）

```
batch=2      -> InvalidArgument: Got invalid dimensions for input: image index: 0 Got: 2 Expected: 1
320x320 输入 -> InvalidArgument: Got invalid dimensions for input: image index: 2 Got: 320 Expected: 480
                                                              index: 3 Got: 320 Expected: 480
```

⇒ 输入**不能**动态尺寸，也不能 batch>1（后者与 §4 的 `multiclass_nms3` 警告一致）。
所以 docs/07 里的 "tile 可以更大" 在这个导出上**不成立**：要更大视野只能放大输入分辨率再重导出，或对多 tile 分别推理。

---

## 8. 与 `docs/07-v2-plan.md` §4.2 契约模板的差异 & 已知不确定项

### 8.1 差异清单（文档模板 vs 实测）

| §4.2 模板项 | 模板值 | **实测值** | 影响 |
|---|---|---|---|
| `input` | 单个输入 `"name": 待填` | **两个输入**：`image [1,3,480,480]`、`scale_factor [1,2]` | 客户端/ONNX runtime 必须喂两个 tensor，否则报 missing input |
| `output.shape` | `[1, 100, 6]` | **`[N, 6]`，N 动态（≤100），另加 `fetch_name_1 [1] int32`** | 不能按固定 100 行解析；要读实际维度或 count |
| `output.normalized` | `false` | **false（确认）**，0–480 像素，且**允许越界**（白图出现 558/489） | 需自己 clamp |
| `nms` | `{ "iou": 0.6, "score": 0.4 }` | 图内：iou **0.6** / score **0.025** / keep_top_k 100；`config.json` 的 NMS：iou **0.5** / score **0.3** | 图内阈值形同虚设，**0.3/0.5 必须由客户端做**；两处参数不一致，别只信一个 |
| `preprocess.padValue` | `114` | **不存在 padding**（`keep_ratio:false`，纯 stretch） | 该字段可删；照抄会让实现者误以为要 letterbox |
| `artifact` | `PP-DocLayout-S_infer.tar` | 仓库里是 **PIR 三件套**（`inference.json` + `inference.pdiparams` + `config.json`），没有 tar | `tarSha256` 项不适用，改成两个文件的 SHA-256（§1） |
| `labels` | 示例顺序 | **23 类，顺序见 `config.json:39-63`**，第 0 类 `paragraph_title`（**不是** `doc_title`）；无 `vertical_text` | 按 §4.3 的表硬编码会整体错位 |
| `input.shape` 可变性 | 模板暗示固定 480 | 固定 480（4789 锚点数与 480×480 严格对应） | "动态 img_size → tile 更大" 的路走不通 |

### 8.2 已知不确定项（**未验证**，不要当结论用）

1. **`scale_factor` 的官方语义**：实测"输出 ÷ scale_factor"是硬事实，但官方部署到底喂的是
   `net/origin` 还是 `origin/net`、是 `[h,w]` 还是 `[w,h]`，没有源码逐行核对。
   M0 规避方式：固定喂 `[1,1]`，不使用它的回映射能力。
2. **坐标越界是否应裁剪**：官方 Paddle 后处理大概率会 clip 到图像边界，这里没做。
3. **图内 NMS 的 `normalized=true` 与 0–480 像素坐标并存**这件事没有深究；实测 NMS 行为正常
   （同类高度重叠框被压掉），但 IoU 的计算尺度以 paddle 实现为准。
4. ~~与官方 Paddle 推理的对拍~~ → **已完成**，见 §9.1：raw 张量最大绝对误差 `2.3e-4`，
   后处理后框坐标最大差 `0.01 px`（阈值 ≤2px），类别零错配。剩余不确定性只在"手机端 float
   运算顺序不同"这一层，量级远小于 1px。
5. 推理耗时只在开发机 CPU 上测过，**没有 Android/arm64 数字**。

---

## 9. 状态声明（明确写清"做了什么 / 没做什么"）

| 项 | 状态 |
|---|---|
| ONNX 转换 | ✅ 成功（§4），产物已入库 `models/doclayout/PP-DocLayout-S.onnx` |
| ONNX 文件可加载 | ✅ onnxruntime 1.23.2 CPU 加载并推理成功 |
| 输入/输出签名 | ✅ 实测（§5），并做过输出语义/坐标空间/scale_factor 三组对照实验 |
| 参考脚本 | ✅ `tools/doclayout_reference.py`（只用于对拍，不进 APK） |
| 两张真实图跑通 | ✅ 25 / 31 个区域（§6.1） |
| **官方 Paddle 推理对拍** | ✅ 完成，**PASS**：两张图框数一致、类别零错配、坐标最大差 0.01 px（≤2px），见 §9.1 |
| 手机端耗时 | ⬜ 未测 |

> **关于"转换失败"的声明**：本文档描述的转换在本机**真实成功**，产物哈希见 §4.1，可复现命令见 §4。
> 未成功的两种方式已在 §3 原样记录（含 0 字节产物，已删除）。本文档**没有**任何伪造或占位的
> 签名/数字；凡未实测的项都标了 ⬜ 或"未验证"。

### 9.1 对拍结果（官方 Paddle vs ONNX）

同一张图、同一套预处理（`doclayout_reference.preprocess`）、同一套后处理（score 0.3 / 类内 NMS 0.5）：

| 图片 | Paddle 框数 | ONNX 框数 | 类别错配 | 框坐标最大差 | raw 张量最大绝对误差 |
|---|---|---|---|---|---|
| `01-article-light@1080.png` | 25 | 25 | 0 | **0.00 px** | 6.1e-05 |
| `03-news-columns@1080.png` | 31 | 31 | 0 | **0.01 px** | 2.3e-04 |
| 结论 | 一致 | 一致 | 0 | **远优于 ≤2px 要求** | 浮点噪声级 |

产物：`testdata/out/ref/*_paddle.json`（官方基准）、`testdata/out/ref/paddle_vs_onnx.json`（汇总，`"pass": true`）。
复现命令（用仓库内脚本，不是一次性临时脚本）：

```powershell
tools\.venv-doclayout\Scripts\python.exe tools\compare_paddle_onnx.py `
  --paddle-dir models/doclayout `
  --onnx models/doclayout/PP-DocLayout-S.onnx `
  --out-dir testdata/out/ref `
  testdata\layout\screens\01-article-light@1080.png `
  testdata\layout\screens\03-news-columns@1080.png
```

官方推理侧的实现细节（供复核）：`paddle.inference.Config(inference.json, inference.pdiparams)`
+ `disable_gpu()`，`get_input_handle("image")` / `("scale_factor")` 各喂一个 tensor，
输出同名 `fetch_name_0 [N,6]` / `fetch_name_1 [1]`，与 ONNX 完全一致。

---

## 10. 复现与忽略规则

```powershell
# 建环境 + 转换：见 §4（共 4 条命令）
# 参考推理：
tools\.venv-doclayout\Scripts\python.exe tools\doclayout_reference.py `
  --model models\doclayout\PP-DocLayout-S.onnx `
  --image testdata\layout\screens\01-article-light@1080.png `
  --out testdata\out\ref\01-article-light.json `
  --overlay testdata\out\ref\01-article-light-overlay.png

# 官方 Paddle vs ONNX 对拍（§9.1）：
tools\.venv-doclayout\Scripts\python.exe tools\compare_paddle_onnx.py `
  --paddle-dir models/doclayout --onnx models/doclayout/PP-DocLayout-S.onnx `
  --out-dir testdata/out/ref testdata\layout\screens\01-article-light@1080.png
```

`tools/doclayout_reference.py` 支持的参数：`--model --image --out --overlay --labels`
`--score-threshold --nms-iou --repeat --scale-factor`（默认值即本文件的契约：0.3 / 0.5 / `[1,1]`）。

`.gitignore` **需要**新增一条（本任务按纪律没有改它）：

```gitignore
tools/.venv-doclayout/
```

`testdata/out/` 已在忽略列表内；`models/doclayout/PP-DocLayout-S.onnx`（4.69 MiB）按 docs/07
V2-00 是**要入库**的产物，未加忽略。
