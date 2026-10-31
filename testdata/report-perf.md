# V2-40 端到端性能基准（CLI 桌面）

> **历史报告（0.5.1 之前）**：当时 v2 曾接入 App（顶栏「强化重排」）。0.5 起只发 v1，该入口已删除；本文数字只用于回看对比，不代表当前产品行为。当前口径见 docs/00 / docs/03 §14.2。


- 生成时间：2026-10-31 10:08
- 模型：models/doclayout/PP-DocLayout-S.onnx
- 素材：006v119zgy1iik2r1f4z0j30j67y9npf.jpg（690x10305）
- 口径：最佳 1 次（-Runs N 取最快）；内存是 JVM 堆 20ms 峰值，不含 ORT native

| 尺寸 | tile | 总耗时 | decode | detect | refine | coverage | order | 峰值内存 |
|---|---|---|---|---|---|---|---|---|
| 1080x2400 | 3 | 751ms | 112ms | 81ms | 166ms | 50ms | 4ms | 84MB |
| 1920x1080 | 2 | 713ms | 77ms | 47ms | 227ms | 41ms | 3ms | 78MB |
| 1080x20000 | 20 | 2057ms | 248ms | 479ms | 699ms | 129ms | 9ms | 509MB |

## 目标与口径

- 目标（中端真机）：1080x2400 <=350ms、1080x20000 <=1.5s、1920x1080 <=400ms、峰值 <=180MB。
- CLI 不能当验收：CLI 是桌面 JDK 21，和 Android ART / BitmapRegionDecoder 的实现不同。
  Android 真机/模拟器的数字见 docs/07 13.4 / 13.5，峰值内存用 Debug.getMemoryInfo 的 totalPss。
- 模拟器换算：MuMu x86_64 大约比骁龙 7 系慢 3 倍（docs/07 13.5）。
  真机估算 = 模拟器实测 / 3；这不是验收，只用来判断离目标还差多少。

