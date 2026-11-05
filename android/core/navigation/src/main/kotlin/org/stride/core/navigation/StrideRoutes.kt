package org.stride.core.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/*
 * Route keys (the "api" half of each feature). Features navigate to each other
 * through these keys only; screens live in feature modules and are contributed
 * to the shell as Hilt-multibound [EntryProviderInstaller]s (ANDF-009).
 * Keys are @Serializable so back stacks survive process death.
 */

/** Top-level destinations shown in the navigation bar / rail. */
@Serializable
data object TodayRoute : NavKey

@Serializable
data object ActivitiesRoute : NavKey

@Serializable
data object FitnessRoute : NavKey

@Serializable
data object PlanRoute : NavKey

@Serializable
data object SettingsRoute : NavKey

/** Detail and flow destinations. */
@Serializable
data class ActivityDetailRoute(val activityId: Long) : NavKey

@Serializable
data object ConnectGarminRoute : NavKey

@Serializable
data object OnboardingRoute : NavKey

@Serializable
data class CoachRoute(val activityId: Long? = null) : NavKey

@Serializable
data object LicencesRoute : NavKey

/** Settings → Edit profile (MVP-021). */
@Serializable
data object ProfileEditRoute : NavKey
