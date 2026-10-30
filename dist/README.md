# 安装包

| 文件 | 大小 | SHA-256 | 说明 |
|---|---|---|---|
| `pictext-0.2.2-release.apk` | 1.17 MB | `c3472d3ef2d4c85f3c8dfcb0ef19fc4cc0c589daeabe43ff3ee77847c8aa0a22` | **推荐安装这个**：R8 压缩 + 资源裁剪，已签名（APK Signature Scheme v2） |
| `pictext-0.2.2-debug.apk` | 9.93 MB | `75f0b13c10e4d372ff7a22d0cd74b3763351a96a55bcf9a5afcaac3a177d8ae5` | 调试包：带 `android:debuggable`，日志完整，用于排查 |

两个包的 `applicationId` 都是 `app.pictext`，签名不同，**不能互相覆盖安装**；
换包前先卸载：`adb uninstall app.pictext`。

## 本版变更（0.2.2，versionCode 4）

- **重排模式支持双指缩放**：×2/×4 同一条连续缩放轴——手势中按比例临时放大（即时反馈），
  松手后按新倍率重新排版（行跟着重切，不会横向溢出）；到达两端时底栏高亮自动切到 ×2 / ×4；
- **顶栏精简**：右上角的“原图”和“⋮”去掉了，只剩返回 + 标题；关于/版本信息移到首页；
- **原图模式不再被顶栏压住**：上下留出与阅读页一致的 56dp + 系统栏空带，并且顶栏也可以点正文收起；
- **稳定性**：瓦片/视口帧/图块位图在替换或滚出屏幕时立即 `recycle()`，修掉图块全屏里
  “把刚换上的位图回收掉”的隐患，渲染失败只记日志不冒泡——反复捏合 + 快速滚动不再崩。

## 上一版（0.2.1）修的问题

- 原图模式的拖动方向：手指往右/下拖，画面跟着往右/下走（之前是反向的镜像拖动）；
- ×4 改成真正的二次重排：把 ×2 的结果当新图重排一次——字号翻倍后放不下的行，按分析期算好的
  **候选字缝**再切 2/4 段，而不是把倍率乘上去、再把超宽的行压回列宽（旧版同页会出现两三种字号）。
  Twitter 样例 21 → 31 项、同页 BODY 字号偏差 0.499 → 0.010；
- 换档（原图 / ×2 / ×4）后按阅读比例恢复位置，不再跳到别的段落；
- 强制档位只放大不缩小（海报/大字截图不再输出 < 1× 的页面）。

## 安装

```bash
adb install -r pictext-0.2.2-release.apk
```

或用手机的文件管理器直接点开 APK（需允许“安装未知来源应用”）。
从 0.2.0 / 0.2.1 升级可以直接覆盖安装（versionCode 4，签名相同）。

## 签名

签名密钥**不在仓库里**，放在仓库外的 `D:\SRC\keys\pictext-release.jks`；
`app/build.gradle.kts` 从 `keystore.properties`（已被 `.gitignore` 忽略）读取路径与口令：

```properties
storeFile=D:/SRC/keys/pictext-release.jks
storePassword=…
keyAlias=pictext
keyPassword=…
```

`keystore.properties` 不存在时（例如别人 clone 下来），release 仍能构建，
只是产出的是未签名的 `app-release-unsigned.apk`，不会因为缺密钥把构建卡住。

## 从源码打包（一步出签名包）

```bash
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk（已签名）
```

需要单独签名时也可以手工做：

```bash
apksigner sign --ks D:/SRC/keys/pictext-release.jks --ks-key-alias pictext \
  --out dist/pictext-0.2.2-release.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --print-certs dist/pictext-0.2.2-release.apk
```

证书指纹（SHA-256）：`DB:46:AE:BA:44:5B:AD:77:D2:62:28:D1:37:9C:99:C4:D5:68:43:5F:8B:73:08:A7:4F:B6:FB:7B:E1:C8:D2:4D`，
有效期至 2054-03-16。**这把钥匙一旦换了，老用户必须卸载重装**，所以务必备份
`D:\SRC\keys\pictext-release.jks` 与 `keystore.properties`。

## 已知信息

- `applicationId` `app.pictext` · `versionName` 0.2.2 · `versionCode` 4
- `minSdk` 26（Android 8.0）· `targetSdk` 35 · `compileSdk` 35
- 权限：只有 androidx 自动生成的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，**没有 INTERNET / 存储权限**
- 包大小 1.17 MB（docs/05 的发布门槛是 < 8 MB）
- 校验：`apksigner verify --print-certs dist/pictext-0.2.2-release.apk` 输出 `Verifies`，
  v2 方案为 true（minSdk 26 > 24，不需要 v1）

## 许可证

本 APK 包含以 **Apache-2.0** 发布的项目代码；v2 起还会包含 PP-DocLayout-S（Apache-2.0）
与 ONNX Runtime（MIT）。完整文本与归属见仓库根目录的
[LICENSE](../LICENSE)、[NOTICE](../NOTICE)、[THIRD_PARTY_LICENSES.md](../THIRD_PARTY_LICENSES.md)。
