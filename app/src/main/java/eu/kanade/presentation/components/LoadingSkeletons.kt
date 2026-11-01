package eu.kanade.presentation.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.entries.components.CoverPlaceholderColor
import eu.kanade.presentation.entries.components.ItemCover
import eu.kanade.presentation.library.components.CommonEntryItemDefaults
import kotlinx.coroutines.delay
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus

/** Skips the skeleton entirely for loads short enough to look instant. */
private const val SHOW_DELAY_MILLIS = 150L
private const val PULSE_MILLIS = 800
private const val PULSE_MIN_ALPHA = 0.5f

/** Enough to fill a tablet. They're lazy, so only the ones on screen are composed. */
private const val PLACEHOLDER_ITEMS = 60

/**
 * Stands in for an entry grid or list until it has loaded, in the layout that's about to appear.
 */
@Composable
fun EntryCollectionSkeleton(
    displayMode: LibraryDisplayMode,
    columns: GridCells,
    contentPadding: PaddingValues,
) {
    SkeletonContainer {
        when (displayMode) {
            LibraryDisplayMode.List -> EntryListSkeleton(contentPadding)
            LibraryDisplayMode.ComfortableGrid -> EntryGridSkeleton(columns, contentPadding, showTitle = true)
            LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
                EntryGridSkeleton(columns, contentPadding, showTitle = false)
            }
        }
    }
}

/**
 * Stands in for the entry details screen until the entry has loaded.
 */
@Composable
fun EntryDetailsSkeleton() {
    SkeletonContainer {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 64.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PlaceholderCover(Modifier.width(100.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlaceholderLine(fraction = 0.9f, height = 20.dp)
                    PlaceholderLine(fraction = 0.6f)
                    PlaceholderLine(fraction = 0.4f)
                }
            }
            PlaceholderLine(fraction = 1f, height = 48.dp)
            PlaceholderLine(fraction = 1f)
            PlaceholderLine(fraction = 1f)
            PlaceholderLine(fraction = 0.7f)
            repeat(DETAILS_ITEM_ROWS) {
                PlaceholderLine(fraction = 1f, height = 48.dp)
            }
        }
    }
}

private const val DETAILS_ITEM_ROWS = 6

/**
 * Pulses in a graphics layer, which redraws without recomposing what's inside.
 */
@Composable
private fun SkeletonContainer(content: @Composable () -> Unit) {
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SHOW_DELAY_MILLIS)
        isVisible = true
    }
    if (!isVisible) return

    val description = stringResource(MR.strings.loading)
    val pulse = rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 1f,
        targetValue = PULSE_MIN_ALPHA,
        animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = description }
            .graphicsLayer { alpha = pulse.value },
    ) {
        content()
    }
}

@Composable
private fun EntryGridSkeleton(
    columns: GridCells,
    contentPadding: PaddingValues,
    showTitle: Boolean,
) {
    LazyVerticalGrid(
        columns = columns,
        contentPadding = contentPadding + PaddingValues(8.dp),
        userScrollEnabled = false,
        verticalArrangement = Arrangement.spacedBy(CommonEntryItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonEntryItemDefaults.GridHorizontalSpacer),
    ) {
        items(count = PLACEHOLDER_ITEMS, contentType = { "skeleton" }) {
            Column(
                modifier = Modifier.padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PlaceholderCover(Modifier.fillMaxWidth())
                if (showTitle) {
                    PlaceholderLine(fraction = 0.9f)
                    PlaceholderLine(fraction = 0.5f)
                }
            }
        }
    }
}

@Composable
private fun EntryListSkeleton(contentPadding: PaddingValues) {
    LazyColumn(
        contentPadding = contentPadding,
        userScrollEnabled = false,
    ) {
        items(count = PLACEHOLDER_ITEMS, contentType = { "skeleton" }) {
            Row(
                modifier = Modifier
                    .height(76.dp)
                    .padding(horizontal = 16.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PlaceholderCover(Modifier.height(70.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 24.dp),
                ) {
                    PlaceholderLine(fraction = 0.7f)
                }
            }
        }
    }
}

@Composable
private fun PlaceholderCover(modifier: Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(ItemCover.Book.ratio)
            .background(CoverPlaceholderColor, MaterialTheme.shapes.extraSmall),
    )
}

@Composable
private fun PlaceholderLine(fraction: Float, height: Dp = 12.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth(fraction)
            .height(height)
            .background(CoverPlaceholderColor, MaterialTheme.shapes.extraSmall),
    )
}
