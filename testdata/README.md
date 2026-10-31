# testdata 说明

本目录分两类数据：**可随仓库分发的自制夹具**，和**不随仓库分发的真实截图**。

## 1. 随仓库分发：`layout/`

这是 V2 的 HTML 版面夹具，全部由本项目自己编写/渲染：

```text
layout/
├─ fixtures.json                 # 夹具清单
├─ html/                         # HTML + base.css（原创）
├─ screens/                      # 12 张整页 PNG（Edge headless 渲染）
├─ ground-truth/                 # DOM 真值：区域框、逐行矩形、order、sensitive
└─ manifest.json                 # 尺寸/哈希/浏览器版本
```

- 用途：区域检测、阅读顺序、N 段切分、强制切/敏感行的回归；不依赖真实截图；
- 许可证：随项目按 **Apache-2.0** 分发；
- 重渲染（需要本地 Edge/Chrome + Node ≥22）：

  ```bash
  node scripts/render-layout-fixtures.mjs
  node scripts/render-layout-fixtures.mjs --check
  ```

- 详细说明见 [docs/07-v2-plan.md 的 §4.11](../docs/07-v2-plan.md#411-html-测试夹具与-dom-真值本地生成)。

## 2. 不随仓库分发：`private/`

真实截图（Twitter/微博/知乎/新闻/Arknights 等）**不进入公开仓库**，原因：

- 版权属于原平台/作者，不能按 Apache-2.0 再许可；
- 可能包含个人信息（头像、昵称、聊天内容）；
- 仓库体积与隐私都不适合公开。

本地复现时，把样例放到：

```text
testdata/private/
├─ samples/          # 任意真实截图（jpg/png）。回归会跑目录里的每一张
├─ samples2/         # 可选：批量评分用的另一批样例 + notes.csv
├─ samples_mark/     # 带手写标记的样例（人工判读用）
└─ samples_ui/       # 其他本地 UI 截图
```

脚本与测试会**优先**读 `testdata/private/...`，找不到再退回 `testdata/samples...`：

```bash
./gradlew :core:test
powershell -ExecutionPolicy Bypass -File scripts/test-samples.ps1
powershell -ExecutionPolicy Bypass -File scripts/score-samples.ps1
```

**跳过规则（0.5.1 起）**：目录不存在 → 整类测试跳过（CI 没样例也不该红）；
目录存在 → 里面每一张都必须通过不变量检查，**单个文件缺失或不合规是失败，不再静默跳过**。
唯一按文件名依赖的是长图回归 `006v119zgy1iik2r1f4z0j30j67y9npf.jpg`（690×10305），
它的断言是"必须回退原图"，样例被删掉时该用例会失败并提示补文件。
`ImageIO` 读不了的格式（当前 JDK 没有 webp 插件）只打印并计入跳过——真机走 `BitmapFactory`，
格式支持面更宽。

## 3. 历史报告

`report.md`、`scoreboard.md` 与 `report-*.md` 是早期用真实样例生成的报告，保留作基线记录。
它们不含图片，链接指向本地的 `out/`、`out2/` 目录；重跑脚本会重新生成。

> **注意**：`report-ab.md` / `report-v2.md` / `report-blind.md` / `report-detection.md`
> 产生于 v2 曾接入 App 的时期，文中的"强化重排"入口、`SettingsRepository.smartLayout`
> 等**在 0.5.1 已不存在**（0.5 只发 v1）。它们只用于回看当时的对比数据，不代表当前产品行为。

## 4. 新增样例的规矩

- 真实截图只放 `private/`，不要 `git add`；
- 自制夹具放 `layout/html/`，加 `data-region` 标注，重新渲染并提交 PNG + 真值；
- 不用来源不明的图片、字体、模型；如确需引入，在 PR 里写清来源与许可证。
