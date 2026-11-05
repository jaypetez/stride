package stride

import androidx.room.gradle.RoomExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Room with KSP and exported schemas committed under `schemas/` (ANDF-004). */
class RoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("room"))
        pluginManager.apply(libs.pluginId("ksp"))
        extensions.configure<RoomExtension> {
            schemaDirectory("$projectDir/schemas")
        }
        dependencies {
            add("api", libs.lib("androidx-room-runtime"))
            add("api", libs.lib("androidx-room-ktx"))
            add("ksp", libs.lib("androidx-room-compiler"))
            add("testImplementation", libs.lib("androidx-room-testing"))
        }
    }
}
