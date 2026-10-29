# 安装包

| 文件 | 大小 | 说明 |
|---|---|---|
| `pictext-0.2.0-release.apk` | ≈1.25 MB | **推荐安装这个**：R8 压缩 + 资源裁剪，已签名（v2/v3） |
| `pictext-0.2.0-debug.apk` | ≈10.4 MB | 调试包：带 `android:debuggable`，日志完整，用于排查 |

两个包的 `applicationId` 都是 `app.pictext`，签名不同，**不能互相覆盖安装**；
换包前先卸载：`adb uninstall app.pictext`。

## 安装

```bash
adb install -r pictext-0.2.0-release.apk
```

或用手机的文件管理器直接点开 APK（需允许“安装未知来源应用”）。

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
  --out dist/pictext-0.2.0-release.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --print-certs dist/pictext-0.2.0-release.apk
```

证书指纹（SHA-256）：`DB:46:AE:BA:44:5B:AD:77:D2:62:28:D1:37:9C:99:C4:D5:68:43:5F:8B:73:08:A7:4F:B6:FB:7B:E1:C8:D2:4D`，
有效期至 2054-03-16。**这把钥匙一旦换了，老用户必须卸载重装**，所以务必备份
`D:\SRC\keys\pictext-release.jks` 与 `keystore.properties`。

## 已知信息

- `applicationId` `app.pictext` · `versionName` 0.2.0 · `versionCode` 2
- `minSdk` 26（Android 8.0）· `targetSdk` 35
- 权限：只有 androidx 自动生成的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，**没有 INTERNET / 存储权限**
- 包大小 1.2 MB（docs/05 的发布门槛是 < 8 MB）
