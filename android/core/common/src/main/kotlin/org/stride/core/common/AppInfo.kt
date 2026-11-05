package org.stride.core.common

/** Build facts the app module knows (BuildConfig) exposed to feature modules (Settings → About). */
interface AppInfo {
    val versionName: String
}
