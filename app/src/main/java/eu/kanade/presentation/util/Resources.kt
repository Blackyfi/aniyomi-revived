package eu.kanade.presentation.util

import android.content.res.Resources
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

/**
 * Create a BitmapPainter from a drawable resource.
 * Use this only if [androidx.compose.ui.res.painterResource] doesn't work.
 *
 * @param id the resource identifier
 *
 * @return the bitmap associated with the resource
 */
@Composable
fun rememberResourceBitmapPainter(@DrawableRes id: Int): BitmapPainter {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(id, configuration.densityDpi, configuration.uiMode) {
        val key = BitmapKey(id, configuration.densityDpi, configuration.uiMode)
        BitmapPainter(
            bitmapCache.getOrPut(key) {
                val drawable = ContextCompat.getDrawable(context, id)
                    ?: throw Resources.NotFoundException()
                drawable.toBitmap().asImageBitmap()
            },
        )
    }
}

// This backs every cover in a grid, so rasterizing per item meant a new bitmap on the main thread for
// each one scrolled into view. The few resources used here have one bitmap per configuration.
private val bitmapCache = ConcurrentHashMap<BitmapKey, ImageBitmap>()

private data class BitmapKey(val id: Int, val densityDpi: Int, val uiMode: Int)
