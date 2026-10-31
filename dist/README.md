# 安装包（0.5.1 · v1-only）

| 文件 | 大小 | SHA-256 | 说明 |
|---|---|---|---|
| `pictext-0.5.1-v1.apk` | 1,247,440 字节（1.19 MiB） | `76473faa4261b8cc04d36f9dc1b34bbfaef394b14d567dab081e9aee561e1cf2` | **推荐安装**：R8 + 资源裁剪，已签名（APK Signature Scheme v2） |
| `pictext-0.5.0-v1.apk` | 1,231,056 字节 | `18d976d2f1cf7f2d8524bbb2d26dc4b500ec0218a660498e0c67108d2f45eae5` | 上一版（长图修复前），留作回退对比 |
| `pictext-0.5.0-v1-longfix.apk` | 1,247,440 字节 | `bc64234b38ba3a08977a6d60854eaa23a31bef4429cc7cef0ec6751c9a4220db` | 0.5.0 + 长图试验包，仅存档 |

- `applicationId` `app.pictext` · `versionName` 0.5.1 · `versionCode` 7
- `minSdk` 26（Android 8.0）· `targetSdk` 35 · `compileSdk` 35
- 权限：只有 androidx 自动生成的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，**没有 INTERNET / 存储权限**
- 全部 ABI 通用（不含 ONNX Runtime / 模型，手机与模拟器都能装）

## 这一版是什么

- **只有 v1**：全页投影切行 + 左右对半重排，不 OCR、不联网、不上模型；
- APK 里**没有** ONNX Runtime、没有版面模型、没有「强化重排」入口——v2 的代码留在
  `:core` / `:cli` 做技术验证，不进入发行包（由 `scripts/verify-v1-only.ps1` 机检门禁）；
- 0.5.1 修了长图：分析图**按宽度定标**（不再按长边压成一条）、`gain` 换算回源图像素、
  超长图自动切条带分析。690×10305 这类长图不再被压成 107px 宽。

## 安装

```bash
adb install -r pictext-0.5.1-v1.apk
```

或手机文件管理器直接点开 APK（需允许"安装未知来源应用"）。
可直接覆盖 0.5.0-v1（code 6）、0.5.0-full / 2.0.0-beta1（code 5）——签名相同。

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
  --out dist/pictext-0.5.1-v1.apk app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --print-certs dist/pictext-0.5.1-v1.apk
```

## 出包前检查

```bash
powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -Release
```

会跑 `:core:test` + `:core:longPageMemoryTest` + `:app:assembleDebug`，再构建 release 并用
`scripts/verify-v1-only.ps1` 检查产物里没有 v2 逻辑（`classes.dex` 常量 + R8 mapping 类表）。

## 已知

- 仓库 HEAD 比这个包多三个提交（2026-10-31 晚：强制档语义、`PlanAuditor` 坐标空间、
  条带内存、渲染日志、语料化真实图回归、v2 剥离门禁）。**它们没有打进这个 APK**；
  重新出包时建议把 `versionCode` 提到 8、`versionName` 到 0.5.2。
- 正文是图片，TalkBack 读不出文字内容（方案本身的限制，见 `docs/01` §12）。

## 许可证

本 APK 包含以 **Apache-2.0** 发布的项目代码。0.5 的包**不包含** PP-DocLayout-S 与
ONNX Runtime（仓库里仍有这两样，只给 `:cli` 的技术验证用）。完整文本与归属见仓库根目录的
[LICENSE](../LICENSE)、[NOTICE](../NOTICE)、[THIRD_PARTY_LICENSES.md](../THIRD_PARTY_LICENSES.md)。
