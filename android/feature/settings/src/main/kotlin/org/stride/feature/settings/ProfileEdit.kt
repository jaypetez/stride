package org.stride.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.stride.core.common.ApplicationScope
import org.stride.core.common.StrideClock
import org.stride.core.data.metrics.MetricsRecomputer
import org.stride.core.datastore.UserPreferences
import org.stride.core.designsystem.component.ErrorState
import org.stride.core.designsystem.component.LoadingState
import org.stride.core.designsystem.component.StrideButton
import org.stride.core.designsystem.component.StrideTopAppBar
import org.stride.core.model.AthleteProfile
import org.stride.core.navigation.LocalNavigator
import org.stride.core.ui.R as CoreUiR
import org.stride.core.ui.UiText
import org.stride.core.ui.formInsets
import org.stride.core.ui.profile.ProfileField
import org.stride.core.ui.profile.ProfileForm
import org.stride.core.ui.profile.ProfileFormState
import org.stride.core.ui.profile.validate

data class ProfileEditUiState(
    val loading: Boolean = true,
    val form: ProfileFormState = ProfileFormState(),
    val errors: Map<ProfileField, UiText> = emptyMap(),
    val saving: Boolean = false,
    /** The profile could not be read; the screen shows an error with "Try again" (AUD-032). */
    val loadFailed: Boolean = false
)

@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    private val prefs: UserPreferences,
    private val recomputer: MetricsRecomputer,
    private val clock: StrideClock,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {
    private val state = MutableStateFlow(ProfileEditUiState())
    val uiState: StateFlow<ProfileEditUiState> = state.asStateFlow()
    private var base = AthleteProfile()

    private val savedChannel = Channel<Unit>(Channel.CONFLATED)
    val saved: Flow<Unit> = savedChannel.receiveAsFlow()

    init {
        load()
    }

    /** Loads the stored profile; a read error becomes the error state (AUD-032). */
    fun load() {
        state.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            runCatching { prefs.current().profile }
                .onSuccess { profile ->
                    base = profile
                    state.update { it.copy(loading = false, form = ProfileFormState.from(profile)) }
                }
                .onFailure { state.update { it.copy(loading = false, loadFailed = true) } }
        }
    }

    fun onChange(form: ProfileFormState) = state.update {
        it.copy(form = form, errors = if (it.errors.isEmpty()) emptyMap() else form.validate(clock.today(), base).errors)
    }

    fun save() {
        val current = state.value
        if (current.saving || current.loading) return
        val validation = current.form.validate(clock.today(), base)
        val profile = validation.profile
        if (profile == null) {
            state.update { it.copy(errors = validation.errors) }
            return
        }
        state.update { it.copy(saving = true) }
        viewModelScope.launch {
            prefs.updateProfile(profile)
            // Anchors (max/resting/threshold HR) feed every metric: rebuild them all,
            // in the app scope so leaving the screen doesn't cancel it.
            appScope.launch { recomputer.recompute(changedActivityIds = null, fromDate = null) }
            savedChannel.send(Unit)
        }
    }
}

@Composable
fun ProfileEditFlow(modifier: Modifier = Modifier, viewModel: ProfileEditViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(viewModel, navigator) { viewModel.saved.collect { navigator.goBack() } }
    ProfileEditScreen(
        state = state,
        onChange = viewModel::onChange,
        onSave = viewModel::save,
        onBack = { navigator.goBack() },
        onRetry = viewModel::load,
        modifier = modifier
    )
}

@Composable
fun ProfileEditScreen(
    state: ProfileEditUiState,
    onChange: (ProfileFormState) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {}
) {
    Scaffold(modifier = modifier, topBar = {
        StrideTopAppBar(title = stringResource(R.string.settings_edit_profile), onBack = onBack)
    }) { padding ->
        if (state.loadFailed) {
            ErrorState(
                title = stringResource(CoreUiR.string.load_error_title),
                message = stringResource(CoreUiR.string.load_error_message),
                onRetry = onRetry,
                modifier = Modifier.padding(padding)
            )
        } else if (state.loading) {
            LoadingState(Modifier.padding(padding).fillMaxSize())
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .formInsets()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ProfileForm(state = state.form, onChange = onChange, errors = state.errors)
                Text(
                    stringResource(R.string.settings_profile_save_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StrideButton(
                    text = stringResource(R.string.settings_save),
                    onClick = onSave,
                    loading = state.saving,
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
