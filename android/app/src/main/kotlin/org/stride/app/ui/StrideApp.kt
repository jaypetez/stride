package org.stride.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.stride.app.R
import org.stride.app.navigation.StrideNavigationState
import org.stride.app.navigation.TopLevelDestination
import org.stride.app.navigation.rememberStrideNavigationState
import org.stride.core.designsystem.component.DemoBanner
import org.stride.core.navigation.EntryProviderInstaller
import org.stride.core.navigation.LocalNavigator
import org.stride.core.navigation.OnboardingRoute
import org.stride.core.navigation.StrideNavigator

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
    onboardingComplete: Boolean = true,
    demoMode: Boolean = false,
    onExitDemo: () -> Unit = {}
) {
    val provider = entryProvider<NavKey> { installers.forEach { install -> install() } }
    // Where the shell should open once onboarding hands over (e.g. ConnectGarminRoute).
    var handoff by remember { mutableStateOf<NavKey?>(null) }

    if (!onboardingComplete) {
        // First launch, or after "Delete all data": full screen, no navigation bar.
        LaunchedEffect(Unit) { navigationState.resetTo(TopLevelDestination.Today.key) }
        OnboardingHost(provider = provider, onHandoff = { handoff = it }, modifier = modifier)
        return
    }
    LaunchedEffect(handoff) {
        handoff?.let {
            navigationState.navigate(it)
            handoff = null
        }
    }
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

    // navigationSuiteItems is not composable: resolve the tab labels first (AUD-034).
    val labels = TopLevelDestination.entries.associateWith { stringResource(it.labelRes) }
    val tabDescriptions = TopLevelDestination.entries.associateWith { stringResource(R.string.nav_tab, labels.getValue(it)) }
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
                        label = { Text(labels.getValue(destination)) },
                        modifier = Modifier.semantics { contentDescription = tabDescriptions.getValue(destination) }
                    )
                }
            }
        ) {
            Column(Modifier.fillMaxSize()) {
                // Edge-to-edge: the banner owns the status-bar inset while it is shown, and the
                // screens below must not pad for it a second time.
                if (demoMode) {
                    DemoBanner(onExit = onExitDemo, modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars))
                }
                NavDisplay(
                    entries = entriesByTab.getValue(navigationState.selected),
                    onBack = { navigationState.goBack() },
                    modifier = Modifier
                        .weight(1f)
                        .then(if (demoMode) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)
                )
            }
        }
    }
}

/**
 * The onboarding flow on its own back stack (OnboardingRoute, and ConnectGarminRoute
 * if a screen pushes it). `resetTo(key)` from onboarding means "done": the shell
 * opens on `key` once the onboarding flag is persisted.
 */
@Composable
private fun OnboardingHost(provider: (NavKey) -> NavEntry<NavKey>, onHandoff: (NavKey) -> Unit, modifier: Modifier = Modifier) {
    val stack = rememberNavBackStack(OnboardingRoute)
    val navigator = remember(stack) { OnboardingNavigator(stack, onHandoff) }
    val entries = rememberDecoratedNavEntries(
        stack,
        listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        provider
    )
    CompositionLocalProvider(LocalNavigator provides navigator) {
        NavDisplay(entries = entries, onBack = { navigator.goBack() }, modifier = modifier.fillMaxSize())
    }
}

private class OnboardingNavigator(private val stack: NavBackStack<NavKey>, private val onHandoff: (NavKey) -> Unit) : StrideNavigator {
    override fun navigate(key: NavKey) {
        if (stack.lastOrNull() != key) stack.add(key)
    }

    override fun goBack(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    override fun resetTo(key: NavKey) = onHandoff(key)
}
