plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    // 桌面 ORT：只用于 CLI 调参/批量评分，`:core` 不依赖它（docs/07 §2.9g）。
    // 用 implementation 而不是 runtimeOnly：适配器要直接引用 ai.onnxruntime.* 的类型。
    implementation(libs.onnxruntime.desktop)
}

application {
    mainClass.set("app.pictext.cli.MainKt")
}

// 让 README 里的相对路径（prototype/out/source.png）从仓库根目录解析。
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
