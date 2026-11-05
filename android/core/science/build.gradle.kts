plugins {
    alias(libs.plugins.stride.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// Pure Kotlin/JVM: the on-device port of packages/core science (ADR 0010). No Android deps.
dependencies {
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.withType<Test>().configureEach {
    // Parity tests read the goldens exported by `pnpm science:golden` (MVP-009).
    systemProperty("stride.goldenDir", rootProject.file("../contracts/science-golden").absolutePath)
    inputs.files(fileTree(rootProject.file("../contracts/science-golden"))).withPropertyName("goldens")
}
