package org.stride.feature.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.stride.core.designsystem.component.StrideTopAppBar
import org.stride.core.navigation.LocalNavigator

/** One open-source component Stride ships with. */
data class OpenSourceLicence(val name: String, val licence: String, val url: String)

/** Third-party software in the app (MVP-021). Static: update it when dependencies change. */
val LICENCES = listOf(
    OpenSourceLicence(
        "Garmin FIT SDK",
        "Flexible and Interoperable Data Transfer (FIT) Protocol License",
        "https://developer.garmin.com/fit/download/"
    ),
    OpenSourceLicence("AndroidX and Jetpack Compose", "Apache License 2.0", "https://developer.android.com/jetpack/androidx"),
    OpenSourceLicence("Room, WorkManager and DataStore (AndroidX)", "Apache License 2.0", "https://developer.android.com/jetpack/androidx"),
    OpenSourceLicence("Navigation 3 and Lifecycle (AndroidX)", "Apache License 2.0", "https://developer.android.com/jetpack/androidx"),
    OpenSourceLicence("Material Components and Material Icons", "Apache License 2.0", "https://m3.material.io/"),
    OpenSourceLicence("Kotlin standard library", "Apache License 2.0", "https://github.com/JetBrains/kotlin"),
    OpenSourceLicence("kotlinx.coroutines", "Apache License 2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    OpenSourceLicence("kotlinx.serialization", "Apache License 2.0", "https://github.com/Kotlin/kotlinx.serialization"),
    OpenSourceLicence("Hilt and Dagger", "Apache License 2.0", "https://dagger.dev/hilt/"),
    OpenSourceLicence("OkHttp and Okio", "Apache License 2.0", "https://square.github.io/okhttp/"),
    OpenSourceLicence("Tink", "Apache License 2.0", "https://developers.google.com/tink"),
    OpenSourceLicence("Vico charts", "Apache License 2.0", "https://github.com/patrykandpatrick/vico")
)

@Composable
fun LicencesFlow(modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    LicencesScreen(onBack = { navigator.goBack() }, modifier = modifier)
}

@Composable
fun LicencesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier, topBar = { StrideTopAppBar(title = "Open-source licences", onBack = onBack) }) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(LICENCES, key = { it.name }) { item ->
                ListItem(
                    headlineContent = { Text(item.name) },
                    supportingContent = { Text("${item.licence}\n${item.url}") }
                )
            }
        }
    }
}
