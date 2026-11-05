package org.stride.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import org.stride.app.R
import org.stride.core.navigation.ActivitiesRoute
import org.stride.core.navigation.FitnessRoute
import org.stride.core.navigation.PlanRoute
import org.stride.core.navigation.SettingsRoute
import org.stride.core.navigation.TodayRoute

/** The five destinations in the navigation bar (phones) or rail (tablets). */
enum class TopLevelDestination(val key: NavKey, @StringRes val labelRes: Int, val selectedIcon: ImageVector, val icon: ImageVector) {
    Today(TodayRoute, R.string.nav_today, Icons.Filled.WbSunny, Icons.Outlined.WbSunny),
    Activities(ActivitiesRoute, R.string.nav_runs, Icons.Filled.DirectionsRun, Icons.Outlined.DirectionsRun),
    Fitness(FitnessRoute, R.string.nav_fitness, Icons.Filled.Insights, Icons.Outlined.Insights),
    Plan(PlanRoute, R.string.nav_plan, Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    You(SettingsRoute, R.string.nav_you, Icons.Filled.Person, Icons.Outlined.Person)
    ;

    companion object {
        val keys: List<NavKey> = entries.map { it.key }

        fun of(key: NavKey): TopLevelDestination? = entries.firstOrNull { it.key == key }
    }
}
