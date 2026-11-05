plugins {
    alias(libs.plugins.stride.android.feature)
}

// Today (MVP-014) with the next-workout card (MVP-017).
dependencies {
    implementation(project(":core:data"))
    testImplementation(project(":core:data-testing"))
}
