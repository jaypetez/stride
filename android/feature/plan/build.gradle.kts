plugins {
    alias(libs.plugins.stride.android.feature)
}

// Training plan tab (MVP-018 UI).
dependencies {
    implementation(project(":core:data"))
    testImplementation(project(":core:data-testing"))
}
