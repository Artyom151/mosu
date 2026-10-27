package com.mosu.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The stage a download is in. [Connecting] and [Importing] have no measurable progress.
 */
enum class DownloadPhase { Connecting, Downloading, Importing }

/**
 * @param progress Download progress in the 0..1 range, only used in [DownloadPhase.Downloading].
 */
data class DownloadUiState(
    val title: String,
    val message: String = "",
    val phase: DownloadPhase = DownloadPhase.Connecting,
    val progress: Float = 0f,
    val speed: String = "",
    val cancelLabel: String = "Cancel",
    val cancellable: Boolean = true,
)

/**
 * Material 3 Expressive download card: a morphing [LoadingIndicator] while there is no measurable progress and a
 * wavy progress indicator while data is being transferred.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadCard(state: DownloadUiState, onCancel: () -> Unit, modifier: Modifier = Modifier) {

    Surface(
        modifier = modifier.widthIn(min = 280.dp, max = 420.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            when (state.phase) {

                DownloadPhase.Downloading -> Box(contentAlignment = Alignment.Center) {
                    CircularWavyProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.size(96.dp),
                    )
                    Text(
                        text = "${(state.progress.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                else -> LoadingIndicator(modifier = Modifier.size(96.dp))
            }

            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (state.phase == DownloadPhase.Downloading) {
                LinearWavyProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val subtitle = listOf(state.message, state.speed).filter { it.isNotEmpty() }.joinToString(" • ")

            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.cancellable) {
                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(state.cancelLabel)
                }
            } else {
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/**
 * A small standalone [LoadingIndicator] to replace the legacy circular progress indicators.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MosuLoadingIndicator(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(modifier = Modifier.size(40.dp))
    }
}
