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
        // 0.5 是 v1-only 包：不再随包带 ONNX Runtime + 4.9MB 模型。
        // 0.5.1（code 7）修长图：分析图宽度优先 + gain 换算回源图 + 超长图条带分析。
        // 0.5.2（code 8）修交互与内存：强制档语义、失败可重试、审计坐标空间、
        // 条带内存（峰值 ~124MB → 54MB）、渲染失败留痕、位图晚一帧回收、v1.3 带内竖切。
        // versionCode 6 从上一版 2.0.0-beta1/0.5.0-full（code 5）升级；0.5.1 +1、0.5.2 +1。
        versionCode = 8
        versionName = "0.5.2"
    }

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
