plugins {
    alias(libs.plugins.stride.jvm.library)
    alias(libs.plugins.stride.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Garmin Personal API client + FIT decoding (MVP-003, MVP-007). Pure JVM: the
// phone talks to Garmin directly, there is no Stride backend (ADR 0010).
dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.garmin.fit)

    testImplementation(libs.okhttp.mockwebserver)
}

tasks.withType<Test>().configureEach {
    // `-Pstride.writeFitFixtures=true` regenerates src/test/resources/fit/*.fit (FitParserTest).
    providers.gradleProperty("stride.writeFitFixtures").orNull?.let { systemProperty("stride.writeFitFixtures", it) }
}
