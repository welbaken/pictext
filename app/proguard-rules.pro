# 图读没有反射、没有网络库，保持默认规则即可。
# Compose 与 AndroidX 自带 consumer rules。

# ONNX Runtime：JNI 侧按类名/方法名反射回调 Java，混淆会直接把推理打断。
# ORT 的 AAR 自带一份 consumer rules，这里再显式保留一份，代价只有几十 KB。
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
