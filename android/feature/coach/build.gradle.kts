plugins {
    alias(libs.plugins.stride.android.feature)
    alias(libs.plugins.kotlin.serialization)
}

// Optional bring-your-own-key AI coach over computed facts only (MVP-019, ADR 0010).
dependencies {
    implementation(project(":core:data"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.okhttp.mockwebserver)
}
