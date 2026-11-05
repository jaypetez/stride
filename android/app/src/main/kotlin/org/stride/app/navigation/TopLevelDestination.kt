package org.stride.app.navigation

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
import org.stride.core.navigation.ActivitiesRoute
import org.stride.core.navigation.FitnessRoute
import org.stride.core.navigation.PlanRoute
import org.stride.core.navigation.SettingsRoute
import org.stride.core.navigation.TodayRoute

/** The five destinations in the navigation bar (phones) or rail (tablets). */
enum class TopLevelDestination(val key: NavKey, val label: String, val selectedIcon: ImageVector, val icon: ImageVector) {
    Today(TodayRoute, "Today", Icons.Filled.WbSunny, Icons.Outlined.WbSunny),
    Activities(ActivitiesRoute, "Runs", Icons.Filled.DirectionsRun, Icons.Outlined.DirectionsRun),
    Fitness(FitnessRoute, "Fitness", Icons.Filled.Insights, Icons.Outlined.Insights),
    Plan(PlanRoute, "Plan", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    You(SettingsRoute, "You", Icons.Filled.Person, Icons.Outlined.Person)
    ;

    companion object {
        val keys: List<NavKey> = entries.map { it.key }

        fun of(key: NavKey): TopLevelDestination? = entries.firstOrNull { it.key == key }
    }
}
