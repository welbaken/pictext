plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("app.pictext.cli.MainKt")
}

// 让 README 里的相对路径（prototype/out/source.png）从仓库根目录解析。
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
