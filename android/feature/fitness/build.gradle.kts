plugins {
    alias(libs.plugins.stride.android.feature)
}

// Fitness: PMC, ACWR, zone distribution, VDOT and race predictions (MVP-016).
dependencies {
    implementation(project(":core:data"))
    testImplementation(project(":core:data-testing"))
}
