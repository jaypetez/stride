plugins {
    alias(libs.plugins.stride.android.feature)
}

// The "You" tab: profile, units, sync, demo, export, delete, about and licences (MVP-021).
dependencies {
    implementation(project(":core:data"))
    implementation(libs.androidx.activity.compose)
    testImplementation(project(":core:data-testing"))
}
