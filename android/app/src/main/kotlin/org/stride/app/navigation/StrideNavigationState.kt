package org.stride.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import org.stride.core.navigation.StrideNavigator

/**
 * One back stack per top-level destination (ANDF-009). Each stack is a saveable
 * [NavBackStack], so stacks survive configuration change and process death, and
 * switching tabs never discards another tab's stack.
 */
@Stable
class StrideNavigationState internal constructor(
    val stacks: Map<TopLevelDestination, NavBackStack<NavKey>>,
    private val selectedIndex: () -> Int,
    private val onSelect: (Int) -> Unit
) : StrideNavigator {
    val selected: TopLevelDestination get() = TopLevelDestination.entries[selectedIndex()]

    val currentStack: NavBackStack<NavKey> get() = stacks.getValue(selected)

    fun select(destination: TopLevelDestination) = onSelect(destination.ordinal)

    override fun navigate(key: NavKey) {
        val topLevel = TopLevelDestination.of(key)
        if (topLevel != null) {
            select(topLevel)
        } else if (currentStack.lastOrNull() != key) {
            currentStack.add(key)
        }
    }

    override fun goBack(): Boolean {
        val stack = currentStack
        return when {
            stack.size > 1 -> {
                stack.removeAt(stack.lastIndex)
                true
            }

            selected != TopLevelDestination.Today -> {
                select(TopLevelDestination.Today)
                true
            }

            else -> false
        }
    }

    override fun resetTo(key: NavKey) {
        stacks.forEach { (destination, stack) ->
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
            if (stack.firstOrNull() != destination.key) {
                stack.clear()
                stack.add(destination.key)
            }
        }
        navigate(key)
    }
}

@Composable
fun rememberStrideNavigationState(): StrideNavigationState {
    val stacks = TopLevelDestination.entries.associateWith { rememberNavBackStack(it.key) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    return remember(stacks) {
        StrideNavigationState(stacks = stacks, selectedIndex = { selected }, onSelect = { selected = it })
    }
}
