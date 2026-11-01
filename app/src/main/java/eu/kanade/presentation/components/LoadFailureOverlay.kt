package eu.kanade.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

data class LoadFailureAction(val label: String, val onClick: () -> Unit)

/**
 * Says why content couldn't load over the screen it was going to be on, for screens that show media
 * and so are always dark. The first action is the prominent one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoadFailureOverlay(
    message: String,
    actions: List<LoadFailureAction>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            // Keeps taps from reaching the player or reader gestures underneath.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.Center,
            ) {
                actions.forEachIndexed { index, action ->
                    if (index == 0) {
                        Button(onClick = action.onClick) { Text(action.label) }
                    } else {
                        TextButton(
                            onClick = action.onClick,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        ) { Text(action.label) }
                    }
                }
            }
        }
    }
}
