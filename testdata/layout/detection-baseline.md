# v2 版式检测基线（M1 / V2-13）

> 生成时间：2026-10-30　　模型：`models/doclayout/PP-DocLayout-S.onnx`（opset 13，4.69MiB）
> 复现：`pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/score-v2.ps1`
> 原始数据：`testdata/report-v2.md` / `testdata/report-v2.csv`（每次跑都覆盖）

## 1. 一句话结论

**模型在真实截图上够用，但不够好到可以默认打开。** 37 张可解码的图上，v2 成功接管 31 张（84%），
6 张回退 v1，**0 张丢内容**（覆盖审计全过、`auditV2` 全绿）。
回退集中在三类：**模型只认出 1–2 个区域**（仪表盘/纯图页）、**切分后压缩行超过 10%**、以及
**竖排/极窄行导致的压缩**。这三类回退都是“宁可回退，不可切坏”，符合 docs/07 §4.9。

## 2. 数字

| 指标 | 值 |
|---|---|
| 输入图片 | 42（真实截图 24 + HTML 夹具 12 + 设备截图 1 + 其它 5） |
| 可解码 | 37 |
| v2 接管（`DOC_LAYOUT`） | **31（84%）** |
| 回退 v1（`PROJECTION`） | 6 |
| 无法解码 | 5（`.webp`，`ImageIO` 不支持；App 侧用 Android 解码器不受影响） |
| `auditV2` 通过 | 31/31 |
| 墨迹覆盖率（v2 页） | 0.9991 – 1.0000 |
| 顺序歧义（`ambiguous`） | 27/31 —— **这是“页级 OR”，保守标记，不代表顺序错**，见 §4 |
| 单页耗时（桌面 JVM，含 JVM 启动） | 1.4 – 5.8s；模型部分约 0.2–1.1s |

## 3. 6 个回退用例（每个都要能解释）

| 用例 | v2 诊断 | 原因 | 判断 |
|---|---|---|---|
| `07-dashboard@1920` | `layout_too_few_regions` | 仪表盘整页只认出 2 个区域（图表/卡片被当成大图块） | **正确回退**：v1 原图也比乱排好 |
| `device-1440x2560`（MuMu 启动器） | `layout_too_few_regions` | 图标网格 + 壁纸，识别为 2 个区域 | **正确回退**：海报类硬负样例 |
| `006v…sak4` | `layout_audit_failed` → `compressed_ratio(0.143)` | 14.3% 的行被压缩（>10%） | 边界失败，见 §5 第 1 条 |
| `Screenshot …164400` | `layout_audit_failed` → `compressed_ratio` | 同上 | 同上 |
| `12-tight-sensitive` | `layout_audit_failed` → `compressed_ratio(0.125)` | 12.5% 压缩（紧排粗体中文） | **预期**：这个夹具本来就是“切不动就只能压”的主用例 |
| `006v…sk30` | `layout_too_few_regions` | 只认出 3 个区域 | 正确回退 |

## 4. 已知问题（按影响排序）

1. **`ambiguous` 标记过响**：`ReadingOrder` 把“任一子带切不开”的歧义上卷成整页 `ambiguous=true`，
   所以 27/31 页都标了歧义。它只影响 `plan.json` 的诊断，不影响出图；但作为“顺序是否正确”的指标
   它没有区分度。**下一步**：用 `testdata/layout/ground-truth/*.json` 的 `data-order` 做顺序准确率
   对比（`FixtureOrderTest`），`ambiguous` 只保留给“页级完全切不开”。
2. **引用块的左边线会把 3 行粘成 1 条带**：`01-article-light` 的 quote 区（6px 左边线）在 v1 的装饰列
   检测里被当成正文（v1 阈值按页宽 0.5% 算）。已在 v2 侧放宽到 ROI 宽的 1.2%（`refineDecorMaxWidthRatio`），
   行数从 27 → 28；但没有专门的用例锁死，V2-13 之后要补一条“6px 引用线”的单测。
3. **聊天/社交卡片被拆成大量图块**：`05-chat` 28 个区域里 21 个是 FIGURE —— 气泡被当图块。
   内容不丢，但文字不放大。这是模型分布问题（训练集是文档），不是管线问题。
4. **`.webp` 无法解码**：CLI 侧 `ImageIO` 不支持；App 走 `BitmapFactory` 能解。批量脚本已标注
   `decode_failed`，不参与统计。
5. **`GAIN_TOO_SMALL` 页仍会进 v2 管线**：v2 排完发现 `gain < 1.25` 后回退原图（`fallback=GAIN_TOO_SMALL`），
   这对已足够大的截图是正确行为，但浪费了一次分析；M4 可以先估 `gain` 再决定是否跑模型。

## 5. 与 docs/07 指标对照（V2-13 出口条件）

| 指标 | 目标 | 实测 | 结论 |
|---|---|---|---|
| 全部样例有结果（含回退） | 100% | 37/37（webp 除外） | ✅ |
| 区域召回（HTML 夹具） | ≥0.85 | **未测**（缺 GT 对比脚本） | ⏳ |
| 区域准确率（HTML 夹具） | ≥0.80 | **未测** | ⏳ |
| 行覆盖（HTML 夹具） | ≥0.99 | 用覆盖审计近似：0.999+ | 🟡 近似 |
| 严格顺序（10 张夹具） | 10/10 | **未测** | ⏳ |
| 0 丢块 | 必须 | `auditV2` 31/31 通过，`region_dropped=0` | ✅ |
| 真实样例 TEXT 召回 | ≥70% | **未测**（需人工标注） | ⏳ |

> ⏳ 的三项都需要“GT 对比”这层工具：把 `ground-truth/*.json` 的 `box/order` 喂给
> `ReadingOrder` 与检测结果比对。这是下一步（V2-13 收尾）的第一件事，不需要模型文件即可跑。

## 6. 结论与建议

- **v2.0 可以按 D-V2-3 的“默认关（β）”发布**：84% 的页面能接管，剩下的安全回退 v1。
- **优先修 `compressed_ratio` 的边界失败**（2 个真实样例差 1–4 个百分点），它挡住的是“本来能排”的页面；
  可行方向：把 `s` 的目标字身从 44 稍微调低，或允许“压缩行”与“略小字号”二选一，
  但都必须在 `RefinePlannerV2Test` 里锁死“同页只有一个 `s`”。
- **不要把模型用在仪表盘/纯图页上**：`layout_too_few_regions` 已经把它们挡住了，不需要额外规则。
