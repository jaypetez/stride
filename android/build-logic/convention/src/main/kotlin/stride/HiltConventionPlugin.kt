package stride

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Hilt via KSP. Android modules get the Hilt Gradle plugin; pure JVM modules
 * (stride.jvm.library) only get hilt-core + the compiler.
 */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("ksp"))
        dependencies {
            add("ksp", libs.lib("hilt-compiler"))
        }
        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            dependencies { add("implementation", libs.lib("hilt-core")) }
        }
        pluginManager.withPlugin("com.android.base") {
            pluginManager.apply(libs.pluginId("hilt"))
            dependencies {
                add("implementation", libs.lib("hilt-android"))
                add("testImplementation", libs.lib("hilt-android-testing"))
                add("kspTest", libs.lib("hilt-compiler"))
            }
        }
    }
}
