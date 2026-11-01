# 安装包（0.5.2 · v1-only）

> 线上发行版：<https://gitee.com/noneoneone/pictext/releases/tag/v0.5.2>
> 直链：<https://gitee.com/noneoneone/pictext/releases/download/v0.5.2/pictext-0.5.2-v1.apk>

| 文件 | 大小 | SHA-256 | 说明 |
|---|---|---|---|
| `pictext-0.5.2-v1.apk` | 1,237,696 字节（1.18 MiB） | `661a1373a9775c28f9f49773f3627c33408e9e76c8e31a82a93a31e96db734ef` | **推荐安装**：R8 + 资源裁剪，已签名（APK Signature Scheme v2） |
| `pictext-0.5.1-v1.apk` | 1,247,440 字节 | `76473faa4261b8cc04d36f9dc1b34bbfaef394b14d567dab081e9aee561e1cf2` | 上一版（只有长图修复），留作回退对比 |
| `pictext-0.5.0-v1.apk` | 1,231,056 字节 | `18d976d2f1cf7f2d8524bbb2d26dc4b500ec0218a660498e0c67108d2f45eae5` | 更早的 v1-only 包 |
| `pictext-0.5.0-v1-longfix.apk` | 1,247,440 字节 | `bc64234b38ba3a08977a6d60854eaa23a31bef4429cc7cef0ec6751c9a4220db` | 0.5.0 + 长图试验包，仅存档 |

- `applicationId` `app.pictext` · `versionName` 0.5.2 · `versionCode` 8
- `minSdk` 26（Android 8.0）· `targetSdk` 35 · `compileSdk` 35
- 权限：只有 androidx 自动生成的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，**没有 INTERNET / 存储权限**
- 全部 ABI 通用（不含 ONNX Runtime / 模型，手机与模拟器都能装）
- 对应仓库提交：`main` 上的 v0.5.2 版本提交（见 `git log` 的 `chore(release)` 一条）

## 这一版是什么

- **只有 v1**：全页投影切行 + 左右对半重排，不 OCR、不联网、不上模型；
- APK 里**没有** ONNX Runtime、没有版面模型、没有「强化重排」入口——v2 的代码留在
  `:core` / `:cli` 做技术验证，不进入发行包（由 `scripts/verify-v1-only.ps1` 机检门禁）；
- **0.5.2 相对 0.5.1 的变化**：
  - 交互：底栏三档的"强制重排"只由用户操作触发——首屏自动选档不再把第 2 页起、以及
    旋转/缩放后的重排全都变成强制放大；全局失败页的"重试"真的能重试了；
  - 准确度：`PlanAuditor` 的倍率检查补上分析缩放 `k`（2383 宽截图曾每页误报 33%）；
    v1.3 带内竖切（图 | 文混排拆左右两区）、v1.4 切点加权评分；
  - 内存：长图条带分析改为"取一条、分析一条"、并发 4 → 2，1080×20000 峰值
    约 124MB → 54MB（新增 `:core:longPageMemoryTest` 96MB 回归护栏）；
  - 稳定性：渲染/解码失败不再静默（logcat 带 src/dest），显示位图改为晚一帧回收，
    修掉 `Canvas: trying to use a recycled bitmap` 的极端时序；
- 0.5.1 的长图修复继续有效：分析图**按宽度定标**（不再按长边压成一条）、`gain` 换算回源图像素。

## 安装

```bash
adb install -r pictext-0.5.2-v1.apk
```

或手机文件管理器直接点开 APK（需允许"安装未知来源应用"）。
可直接覆盖 0.5.1（code 7）、0.5.0-v1（code 6）、0.5.0-full / 2.0.0-beta1（code 5）——签名相同。

## 签名（测试签名，不是商店签名）

```text
keystore  dist/pictext-delivery.jks
alias     pictext
密码      pictext-delivery-2026
```

- `keystore.properties`（仓库根，已被 `.gitignore` 忽略）当前指向这把钥匙；
- **以后发升级包必须继续用同一个 keystore**，否则用户无法覆盖安装；
- 正式上架请换自己的 keystore，并同步修改 `keystore.properties`。

## 从源码打包

```bash
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk（已签名）
```

需要手工签名时：

```bash
apksigner sign --ks dist/pictext-delivery.jks --ks-key-alias pictext \
  --out dist/pictext-0.5.2-v1.apk app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --print-certs dist/pictext-0.5.2-v1.apk
```

## 出包前检查

```bash
powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -Release
```

会跑 `:core:test` + `:core:longPageMemoryTest` + `:app:assembleDebug`，再构建 release 并用
`scripts/verify-v1-only.ps1` 检查产物里没有 v2 逻辑（`classes.dex` 常量 + R8 mapping 类表）。
本次出包结果：`PASS`，`classes.dex` 0 个 v2 常量、`mapping.txt` 0 个 v2 业务类，
残留只有 `LayoutStrategy` / `CutKind` 两个枚举（纯数据类型）。

## 已知

- 正文是图片，TalkBack 读不出文字内容（方案本身的限制，见 `docs/01` §12）；
- `:app` 还没有自动化测试（仪器测试为 0），渲染/手势改动只能靠真机手测；
- release 也会打 `Log.d` 诊断日志（没有 R8 剔除规则），不联网、不上报。

## 许可证

本 APK 包含以 **Apache-2.0** 发布的项目代码。0.5 的包**不包含** PP-DocLayout-S 与
ONNX Runtime（仓库里仍有这两样，只给 `:cli` 的技术验证用）。完整文本与归属见仓库根目录的
[LICENSE](../LICENSE)、[NOTICE](../NOTICE)、[THIRD_PARTY_LICENSES.md](../THIRD_PARTY_LICENSES.md)。
