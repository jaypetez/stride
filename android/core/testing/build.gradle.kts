plugins {
    alias(libs.plugins.stride.android.library.compose)
    alias(libs.plugins.stride.android.hilt)
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(libs.junit4)
    api(libs.kotlin.test)
    api(libs.kotlinx.coroutines.test)
    api(libs.turbine)
    api(libs.robolectric)
    api(libs.androidx.test.core)
    api(libs.androidx.test.runner)
    api(libs.hilt.android.testing)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui.test)
    api(libs.androidx.compose.ui.test.accessibility)
    api(libs.roborazzi)
    api(libs.roborazzi.compose)
    api(libs.roborazzi.accessibility.check)
}
