plugins {
    alias(libs.plugins.stride.android.library)
    alias(libs.plugins.stride.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Repositories (Room as the source of truth), Garmin sync on WorkManager, the
// recompute pipeline over core:science, and demo mode (MVP-008/012/020).
dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(project(":core:database"))
    api(project(":core:datastore"))
    api(project(":core:garmin"))
    api(project(":core:science"))
    implementation(libs.androidx.work.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
    testImplementation(libs.androidx.work.testing)
}
