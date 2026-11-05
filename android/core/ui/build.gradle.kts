plugins {
    alias(libs.plugins.stride.android.library.compose)
}

dependencies {
    api(project(":core:designsystem"))
    api(project(":core:model"))
    api(libs.androidx.compose.material3.adaptive.navigationSuite)
}
