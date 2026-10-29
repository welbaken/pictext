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
    testLogging {
        events("passed", "skipped", "failed")
    }
}
