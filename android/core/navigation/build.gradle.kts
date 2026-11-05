plugins {
    alias(libs.plugins.stride.android.library.compose)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.androidx.navigation3.runtime)
    api(libs.kotlinx.serialization.json)
}
