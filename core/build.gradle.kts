plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    systemProperty("pictext.root", project.rootDir.absolutePath)
    // 长图内存回归必须跑在受限堆上，单独成任务（见下面的 longPageMemoryTest）。
    exclude("**/LongPageMemoryTest.class")
    testLogging {
        events("passed", "skipped", "failed")
    }
}

/**
 * 长图内存回归：1080×20000 在 112MB 堆下分析。
 *
 * 单独成任务是因为它必须跑在受限堆上：条带并发数、条带拷贝策略或竖直游程一旦退回旧实现，
 * 峰值会从约 55MB 涨到约 124MB，这个用例会直接 OOM 失败（详见 LongPageMemoryTest 的注释）。
 */
tasks.register<Test>("longPageMemoryTest") {
    description = "1080×20000 长图在 112MB 堆下的内存回归"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnit()
    maxHeapSize = "96m"
    systemProperty("pictext.root", project.rootDir.absolutePath)
    include("**/LongPageMemoryTest.class")
    testLogging {
        events("passed", "failed")
    }
}

tasks.named("check") {
    dependsOn("longPageMemoryTest")
}
