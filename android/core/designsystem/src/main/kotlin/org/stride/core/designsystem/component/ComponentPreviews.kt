package org.stride.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.stride.core.designsystem.theme.StrideTheme

/**
 * Showcases used by both IDE previews and Roborazzi screenshot tests, so every
 * component is covered in light and dark (ANDF-002).
 */
object ComponentShowcase {
    @Composable
    fun Buttons() = Showcase {
        StrideButton(text = "Connect Garmin", onClick = {}, leadingIcon = Icons.Outlined.Link)
        StrideButton(text = "Connecting", onClick = {}, loading = true)
        StrideOutlinedButton(text = "Try demo", onClick = {})
        StrideTextButton(text = "Not now", onClick = {})
    }

    @Composable
    fun Cards() = Showcase {
        StrideCard(title = "Last run", subtitle = "Sun 1 Nov · Easy run") {
            Text("10.2 km · 54:12 · 5:19 /km", style = MaterialTheme.typography.bodyLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(label = "Fitness", value = "48.2", caption = "CTL", modifier = Modifier.weight(1f))
            StatTile(label = "Form", value = "+6", unit = "TSB", accent = StrideTheme.colors.fresh, modifier = Modifier.weight(1f))
        }
    }

    @Composable
    fun States() = Showcase {
        EmptyState(title = "No runs yet", message = "Connect Garmin or try the demo to see your training.", actionLabel = "Try demo")
        ErrorState(message = "Garmin could not be reached. Check your connection.")
        LoadingState(label = "Syncing activities…")
    }

    @Composable
    fun Chrome() = Showcase {
        StrideTopAppBar(title = "Activity", onBack = {})
        SectionHeader(title = "This week")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(text = "Fresh", color = StrideTheme.colors.fresh)
            StatusPill(text = "Productive", color = StrideTheme.colors.productive)
            StatusPill(text = "Overreaching", color = StrideTheme.colors.overreaching)
        }
        StrideAvatar(name = "Alex Runner")
        DemoBanner(onExit = {})
    }

    @Composable
    private fun Showcase(content: @Composable () -> Unit) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

@ThemePreviews
@Composable
private fun ButtonsPreview() = StrideTheme { ComponentShowcase.Buttons() }

@ThemePreviews
@Composable
private fun CardsPreview() = StrideTheme { ComponentShowcase.Cards() }

@ThemePreviews
@Composable
private fun StatesPreview() = StrideTheme { ComponentShowcase.States() }

@ThemePreviews
@Composable
private fun ChromePreview() = StrideTheme { ComponentShowcase.Chrome() }
