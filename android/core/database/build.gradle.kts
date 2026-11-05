plugins {
    alias(libs.plugins.stride.android.library)
    alias(libs.plugins.stride.android.hilt)
    alias(libs.plugins.stride.android.room)
}

// Room is the on-device source of truth (ANDF-004, MVP-006). Schemas are
// exported to schemas/ and committed; MigrationTest reads them as test assets.
android {
    sourceSets.getByName("test").assets.srcDir("$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
}
