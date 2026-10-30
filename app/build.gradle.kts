import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 签名配置从仓库外的 `keystore.properties` 读取（本文件与 keystore 都不入库）：
 *
 * ```
 * storeFile=D:/SRC/keys/pictext-release.jks
 * storePassword=…
 * keyAlias=pictext
 * keyPassword=…
 * ```
 *
 * 文件不存在时（比如别人 clone 下来）release 仍能构建，只是不签名——
 * 不会因为缺密钥把整个构建卡住。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null &&
    File(keystoreProps.getProperty("storeFile")).isFile

android {
    namespace = "app.pictext"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.pictext"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "2.0.0-beta1"
        // v2 只随 arm64 发：ORT 的 .so 单 ABI 就 4–6MB（docs/07 §2.6 / 决策 D-V2-1）。
        ndk { abiFilters += "arm64-v8a" }
    }

    androidResources {
        // 模型是 4.9MB 的 ONNX；压缩进 APK 后每次启动都要解压一次，直接不压缩。
        noCompress += "onnx"
        // `models/` 里还有转换用的来源文件（inference.json/.pdiparams/.yml 等，5.2MB），
        // 运行时用不到，打包时按文件名忽略掉——这样仓库里只有一份模型，APK 里也只有运行时需要的。
        ignoreAssetsPatterns += listOf(
            "*.pdiparams", "*.json", "*.yml", "*.md", "*.txt", "SHA256SUMS",
        )
    }

    /**
     * 模型资产统一放仓库根的 `models/`，不在 `app/src/main/assets` 里再存一份
     * （docs/07 §10.2）。asset 路径因此是 `doclayout/PP-DocLayout-S.onnx`。
     */
    sourceSets["main"].assets.srcDir(rootProject.file("models"))

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = File(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 模拟器（本机的 MuMu 是 x86_64）需要这条；release 只留 arm64-v8a。
            ndk { abiFilters += "x86_64" }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(project(":core"))
    // v2 版面模型：ORT Android（arm64-v8a + debug x86_64）。`:core` 不依赖它。
    implementation(libs.onnxruntime.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
