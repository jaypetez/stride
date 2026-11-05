package org.stride.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.stride.core.data.demo.DemoController
import org.stride.core.datastore.UserPreferences
import org.stride.core.datastore.UserSettings

/** Settings the shell needs before it can draw anything: [Loading] until DataStore's first value. */
sealed interface MainUiState {
    data object Loading : MainUiState

    data class Ready(val settings: UserSettings) : MainUiState
}

@HiltViewModel
class MainActivityViewModel @Inject constructor(prefs: UserPreferences, private val demo: DemoController) : ViewModel() {
    val uiState: StateFlow<MainUiState> = prefs.settings
        .map<UserSettings, MainUiState> { MainUiState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MainUiState.Loading)

    /** The system splash screen stays up while this is true (AUD-037). */
    val keepSplashOnScreen: Boolean get() = uiState.value == MainUiState.Loading

    fun exitDemo() {
        viewModelScope.launch { demo.exit() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
