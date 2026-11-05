package org.stride.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.stride.app.navigation.StrideNavigationState
import org.stride.app.navigation.TopLevelDestination
import org.stride.app.navigation.rememberStrideNavigationState
import org.stride.core.designsystem.component.DemoBanner
import org.stride.core.navigation.EntryProviderInstaller
import org.stride.core.navigation.LocalNavigator

/**
 * The app shell (ANDF-009/010): an adaptive navigation suite (bottom bar on
 * phones, rail on larger windows) around a Navigation 3 [NavDisplay]. Screens
 * come from feature modules as Hilt-multibound [EntryProviderInstaller]s; the
 * shell never imports feature internals.
 */
@Composable
fun StrideApp(
    installers: Set<EntryProviderInstaller>,
    modifier: Modifier = Modifier,
    navigationState: StrideNavigationState = rememberStrideNavigationState(),
    demoMode: Boolean = false,
    onExitDemo: () -> Unit = {}
) {
    val provider = entryProvider<NavKey> { installers.forEach { install -> install() } }
    val decorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
        rememberViewModelStoreNavEntryDecorator<NavKey>()
    )
    // Decorate every tab's stack (not just the visible one) so switching tabs keeps
    // each tab's saved state and ViewModels alive.
    val entriesByTab = TopLevelDestination.entries.associateWith { destination ->
        rememberDecoratedNavEntries(navigationState.stacks.getValue(destination), decorators, provider)
    }

    // NavDisplay only handles back while a tab has something to pop; at the root of
    // any tab other than Today, back returns to Today before leaving the app.
    BackHandler(enabled = navigationState.currentStack.size <= 1 && navigationState.selected != TopLevelDestination.Today) {
        navigationState.goBack()
    }

    CompositionLocalProvider(LocalNavigator provides navigationState) {
        NavigationSuiteScaffold(
            modifier = modifier,
            navigationSuiteItems = {
                TopLevelDestination.entries.forEach { destination ->
                    val selected = destination == navigationState.selected
                    item(
                        selected = selected,
                        onClick = { navigationState.select(destination) },
                        icon = {
                            Icon(
                                imageVector = if (selected) destination.selectedIcon else destination.icon,
                                contentDescription = null
                            )
                        },
                        label = { Text(destination.label) },
                        modifier = Modifier.semantics { contentDescription = "${destination.label} tab" }
                    )
                }
            }
        ) {
            Column(Modifier.fillMaxSize()) {
                if (demoMode) DemoBanner(onExit = onExitDemo)
                NavDisplay(
                    entries = entriesByTab.getValue(navigationState.selected),
                    onBack = { navigationState.goBack() },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
