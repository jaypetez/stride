package stride

import io.github.takahirom.roborazzi.RoborazziExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Roborazzi screenshot tests on Robolectric native graphics (ANDF-008).
 * Baselines live in `src/test/screenshots/`; `recordRoborazziDebug` records,
 * `verifyRoborazziDebug` verifies. Qualifiers are pinned in [RoborazziDefaults].
 */
class RoborazziConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("roborazzi"))
        extensions.configure<RoborazziExtension> {
            outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
        }
        dependencies {
            add("testImplementation", libs.lib("roborazzi"))
            add("testImplementation", libs.lib("roborazzi-compose"))
            add("testImplementation", libs.lib("roborazzi-junit-rule"))
            add("testImplementation", project(":core:testing"))
        }
    }
}
