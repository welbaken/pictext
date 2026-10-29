# 真实图片测试报告（自动生成）

> 本文件由 `scripts/test-samples.ps1` 生成，数字直接取自同一次运行的 `plan.json`；
> 结构值同样记录在 `manifest.json`（含输入/输出 SHA-256）。不要手改。

运行时间：2026-10-29T15:02:34 · 输出宽度 1080 · targetEm 44 · 入口 `:cli`（与 Android 共用 `:core`）

## 结果总览

| 样例 | 源尺寸 | 文字行 | 图块 | 切分列 | cleanRatio | scale | bodyScale | gain | 模式 | 输出 | 审计 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| twitter | 785×881 | 18 | 0 | 399 | 1.00 | 2.391 | 2.391 | 1.738 | REFLOW | 1080×1782 | OK |
| news_jp | 690×711 | 8 | 1 | 327 | 1.00 | 2.733 | 2.733 | 1.746 | REFLOW | 1080×1570 | OK |
| xerj | 1080×908 | 25 | 1 | 535 | 1.00 | 1.915 | 1.915 | 1.915 | REFLOW | 1080×1947 | OK |
| arknights | 1000×2100 | 3 | 1 | 350 | 0.00 | 1.000 | 1.000 | 0.000 | ORIGINAL / WEAK_CUT | 1000×2100（原图） | OK |

图块类型：twitter={} · news_jp={"PHOTO":1} · xerj={"PHOTO":1} · arknights={"PHOTO":1}

行角色：twitter={"UI":5,"BODY":13} · news_jp={"HEADING":2,"UI":1,"CAPTION":1,"BODY":4} · xerj={"BODY":16,"UI":8,"CAPTION":1} · arknights={"BODY":3}

## 逐样例

### twitter

- 模式：`REFLOW`（fallback=NONE），scale=2.391，gain=1.738
- 结构：文字行 18 · 图块 0 · 切分列 399 · cleanRatio 1.00 · 渲染项 21 · 丢弃 0
- 输出：1080×1782
- 审计：{"ok":true,"bodyScaleDeviation":0.001,"uiMaxScale":1.50,"headingHeightRatio":0.00,"itemsOutOfBounds":0,"nonMonotonic":0,"issues":[]}
- 耗时：analyze=120.0ms plan=6.9ms
- 产物：[after](out/twitter/after.png) · [debug](out/twitter/debug.png) · [plan.json](out/twitter/plan.json)

### news_jp

- 模式：`REFLOW`（fallback=NONE），scale=2.733，gain=1.746
- 结构：文字行 8 · 图块 1 · 切分列 327 · cleanRatio 1.00 · 渲染项 12 · 丢弃 0
- 输出：1080×1570
- 审计：{"ok":true,"bodyScaleDeviation":0.001,"uiMaxScale":1.50,"headingHeightRatio":1.31,"itemsOutOfBounds":0,"nonMonotonic":0,"issues":[]}
- 耗时：analyze=105.9ms plan=5.2ms
- 产物：[after](out/news_jp/after.png) · [debug](out/news_jp/debug.png) · [plan.json](out/news_jp/plan.json)

### xerj

- 模式：`REFLOW`（fallback=NONE），scale=1.915，gain=1.915
- 结构：文字行 25 · 图块 1 · 切分列 535 · cleanRatio 1.00 · 渲染项 34 · 丢弃 0
- 输出：1080×1947
- 审计：{"ok":true,"bodyScaleDeviation":0.002,"uiMaxScale":1.50,"headingHeightRatio":0.00,"itemsOutOfBounds":0,"nonMonotonic":0,"issues":[]}
- 耗时：analyze=136.9ms plan=6.5ms
- 产物：[after](out/xerj/after.png) · [debug](out/xerj/debug.png) · [plan.json](out/xerj/plan.json)

### arknights

- 模式：`ORIGINAL`（fallback=WEAK_CUT），scale=1.000，gain=0.000
- 结构：文字行 3 · 图块 1 · 切分列 350 · cleanRatio 0.00 · 渲染项 0 · 丢弃 0
- 输出：原图
- 审计：{"ok":true,"bodyScaleDeviation":0.000,"uiMaxScale":0.00,"headingHeightRatio":0.00,"itemsOutOfBounds":0,"nonMonotonic":0,"issues":[]}
- 耗时：analyze=143.3ms plan=10.2ms
- 硬样例：复杂美术背景，验收标准是安全回退、内容完整
- 产物：[after](out/arknights/after.png) · [debug](out/arknights/debug.png) · [plan.json](out/arknights/plan.json)


