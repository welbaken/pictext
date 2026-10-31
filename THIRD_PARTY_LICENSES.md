# 第三方许可证与归属

本项目自身以 **Apache-2.0** 发布（见 [LICENSE](LICENSE)）。下面是随项目分发或运行时会用到的第三方组件。
本文件不是法律意见；正式分发前请按实际依赖清单复核。

> **0.5 起的发行口径**：Android APK 是 v1-only，**不含** ONNX Runtime 与 PP-DocLayout-S；
> 这两样仍随**仓库**分发、只给 `:cli` 的技术验证用（`scripts/verify-v1-only.ps1` 对产物做机检）。

## 运行时依赖

| 组件 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| Kotlin / kotlinx.coroutines | 2.0.21 / 1.9.0 | Apache-2.0 | 语言与协程 |
| AndroidX Core / Activity / Lifecycle / DataStore | 见 `gradle/libs.versions.toml` | Apache-2.0 | Android 基础库 |
| Jetpack Compose (BOM 2024.10.01) / Material 3 | 见 `libs.versions.toml` | Apache-2.0 | UI |
| ONNX Runtime（v2 可选） | 见 `libs.versions.toml` | MIT | 端侧推理 |
| PP-DocLayout-S（v2 可选） | 官方推理模型 | Apache-2.0 | 版面区域检测 |

## 构建期工具

| 组件 | 许可证 | 用途 |
|---|---|---|
| Paddle2ONNX | Apache-2.0 | 把官方 Paddle 推理模型转成 ONNX |
| Gradle / AGP | Apache-2.0 | 构建 |

## PP-DocLayout-S

- 来源：<https://huggingface.co/PaddlePaddle/PP-DocLayout-S>
- 文档：<https://www.paddleocr.ai/main/version3.x/module_usage/layout_detection.html>
- 许可证：Apache-2.0（按模型卡信息；发布前请把模型卡上的 LICENSE 原文一并放入
  `models/doclayout/LICENSE`）
- 归属：Copyright PaddlePaddle Authors
- 说明：官方产物是 Paddle inference `.tar`；仓库中的 ONNX 由 Paddle2ONNX 转换，
  仅改变格式，权重仍受上游许可证约束。转换工具/版本/opset 记录在
  `models/doclayout/contract.json`。

## ONNX Runtime

MIT License

Copyright (c) Microsoft Corporation. All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## 字体

当前仓库**不打包字体**，测试与渲染使用系统字体。

如果后续把 Noto Sans SC 子集放进 APK 或测试夹具，必须按 **OFL-1.1** 分发，
并保留字体的 `OFL.txt`；不能改成 Apache-2.0。

## Android / Google 服务

如果后续接入 Google Play Services、ML Kit 等，按其各自条款处理；当前项目没有接入。

## 完整依赖清单

上面的表只列直接依赖。发版前建议用 Gradle 的依赖报告生成完整清单：

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

或接入 `com.github.jk1.dependency-license-report` 之类的插件生成
`THIRD_PARTY_LICENSES` 的完整版本。
