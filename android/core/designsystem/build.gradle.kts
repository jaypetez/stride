plugins {
    alias(libs.plugins.stride.android.library.compose)
    alias(libs.plugins.stride.android.roborazzi)
}

dependencies {
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.iconsExtended)
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
}
