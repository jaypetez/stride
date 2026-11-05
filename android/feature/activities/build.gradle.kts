plugins {
    alias(libs.plugins.stride.android.feature)
}

// Runs list and activity detail (MVP-015).
dependencies {
    implementation(project(":core:data"))
    testImplementation(project(":core:data-testing"))
}
