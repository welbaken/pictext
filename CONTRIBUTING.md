# 贡献指南

感谢你愿意改进图读 PicText。这个项目是一个**完全离线**的 Android 长图阅读工具：
不联网、不上传图片、不申请网络权限。提交代码前，请先确认你的改动不会破坏这三条。

## 许可证

本项目以 **Apache-2.0** 发布。提交贡献即表示你同意：

- 你的贡献按 **Apache-2.0** 授权（inbound = outbound）；
- 你有权提交这些代码/文档；
- 如果贡献里包含第三方代码、图片、字体或模型，你会同时在 PR 里说明来源与许可证。

我们使用 **DCO（Developer Certificate of Origin）**，不要求签署 CLA。
每次提交请带 `Signed-off-by`：

```bash
git commit -s -m "fix: ..."
```

`-s` 会追加：

```text
Signed-off-by: Your Name <you@example.com>
```

## 开发前

```bash
./gradlew :core:test                 # 单元 + 黄金 + （有样例时）真实图回归
./gradlew :core:longPageMemoryTest   # 长图内存：1080×20000 @ -Xmx96m
./gradlew :app:assembleDebug
powershell -ExecutionPolicy Bypass -File scripts/verify.ps1          # 上面三样一键
powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -Release # 追加发行版门禁
```

- **发行版只发 v1**：App 不得依赖 v2（`core/layout/*`、`ReflowPlannerV2`、ORT、模型）。
  `scripts/verify-v1-only.ps1` 会检查 release 的 `classes.dex` 与 R8 `mapping.txt`，
  新增任何"顺手接一下 v2"的入口都会被它拦下。
- `:app` 目前**没有** `androidTest`/`test` 源集；改渲染、解码、手势时必须写清真机手测路径。
- 真实截图不随仓库分发（版权/隐私原因），放在 `testdata/private/` 下即可被测试与脚本自动发现；
  **目录存在时每一张都必须通过不变量检查**，缺文件不再是静默跳过。
  HTML 版面夹具在 `testdata/layout/`，用 `node scripts/render-layout-fixtures.mjs` 重渲染。
  详见 [testdata/README.md](testdata/README.md)。

## 文档

改行为时同步对应的规范文档，别让文档变成"愿望"：

| 文档 | 性质 | 什么时候改 |
|---|---|---|
| [00 概览](docs/00-overview.md) | 定位与成功标准 | 改指标 / 改方案边界 |
| [01 产品](docs/01-product.md) | **当前 UI 与文案的唯一口径** | 改交互、改文案、改状态机 |
| [02 算法](docs/02-algorithm.md) | **规范**：参数、流水线、回退 | 改分析/规划/参数 |
| [03 架构](docs/03-architecture.md) | **规范**：模块、接口、内存、发布口径 | 改结构、改接口、改依赖 |
| [05 测试](docs/05-testing.md) | **规范**：测试矩阵与发布门槛 | 加/删测试、改门槛 |
| 04 / 06 / 07 | 历史计划与复盘 | 一般不回改，只追加结论 |

## PR 约定

- 一个 PR 只做一件事；算法改动请附 before/after 截图或 `plan.json` diff；
- 不新增未在 PR 里说明的依赖；`:core` 不允许 import `android.*` 或 native 库；
- 新增逻辑要有单测；UI 改动要写清手测路径；
- 不提交 `testdata/private/`、`testdata/out*/`、`keystore.properties`、`*.jks`。

## 提交信息

建议用 Conventional Commits 前缀：`feat:`、`fix:`、`docs:`、`test:`、`chore:`。
不强制，但请让第一行能独立读懂。

## 安全问题

如果发现会导致图片/隐私泄露、路径穿越、任意文件读写的问题，请不要公开开 issue；
先在仓库的 Security 页面私信维护者，或在 issue 里只描述影响、不贴利用代码。

---

## Developer Certificate of Origin 1.1

```text
Developer Certificate of Origin
Version 1.1

Copyright (C) 2004, 2006 The Linux Foundation and its contributors.
1 Letterman Drive
Suite D4700
San Francisco, CA, 94129

Everyone is permitted to copy and distribute verbatim copies of this
license document, but changing it is not allowed.

Developer's Certificate of Origin 1.1

By making a contribution to this project, I certify that:

(a) The contribution was created in whole or in part by me and I
    have the right to submit it under the open source license
    indicated in the file; or

(b) The contribution is based upon previous work that, to the best
    of my knowledge, is covered under an appropriate open source
    license and I have the right under that license to submit that
    work with modifications, whether created in whole or in part
    by me, under the same open source license (unless I am
    permitted to submit under a different license), as indicated
    in the file; or

(c) The contribution was provided directly to me by some other
    person who certified (a), (b) or (c) and I have not modified
    it.

(d) I understand and agree that this project and the contribution
    are public and that a record of the contribution (including all
    personal information I submit with it, including my sign-off) is
    maintained indefinitely and may be redistributed consistent with
    this project or the open source license(s) involved.
```
