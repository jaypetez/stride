plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.kover)
}

// Aggregated coverage (ANDF-007): `./gradlew -p android koverVerify` / `koverHtmlReport`.
dependencies {
    // Only real modules (not the `:core` / `:feature` folder projects).
    subprojects.filter { it.childProjects.isEmpty() }.forEach { kover(it) }
}

kover {
    reports {
        filters {
            excludes {
                // Generated code and DI glue are not meaningful coverage.
                classes(
                    "*_Factory",
                    "*_Factory\$*",
                    "*_MembersInjector",
                    "*Hilt_*",
                    "hilt_aggregated_deps.*",
                    "dagger.hilt.internal.aggregatedroot.codegen.*",
                    "*_HiltModules*",
                    "*_Impl",
                    "*_Impl\$*",
                    "*.BuildConfig",
                    "*ComposableSingletons*",
                    "*.databinding.*",
                )
                annotatedBy("androidx.compose.ui.tooling.preview.Preview", "dagger.Module")
            }
        }
        verify {
            rule("Minimum line coverage") {
                // Start at 60% (ANDF-007). Raise it as the app matures; never lower it silently.
                minBound(60)
            }
        }
    }
}

tasks.named("check").configure {
    dependsOn("koverVerify")
}
