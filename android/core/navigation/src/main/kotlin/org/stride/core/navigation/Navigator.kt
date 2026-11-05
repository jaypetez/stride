package org.stride.core.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey

/** A feature's contribution to the app's entry provider (bound `@IntoSet` with Hilt). */
typealias EntryProviderInstaller = EntryProviderScope<NavKey>.() -> Unit

/** Navigation actions available to screens. Implemented by the app shell. */
interface StrideNavigator {
    /** Pushes [key] onto the current tab's back stack (or switches tab for top-level keys). */
    fun navigate(key: NavKey)

    /** Pops the current back stack; returns false if already at its root. */
    fun goBack(): Boolean

    /** Clears every back stack and starts again at [key] (used after onboarding / wipe). */
    fun resetTo(key: NavKey)
}

/** No-op navigator for previews and screenshot tests. */
object NoOpNavigator : StrideNavigator {
    override fun navigate(key: NavKey) = Unit

    override fun goBack(): Boolean = false

    override fun resetTo(key: NavKey) = Unit
}

val LocalNavigator = staticCompositionLocalOf<StrideNavigator> { NoOpNavigator }
