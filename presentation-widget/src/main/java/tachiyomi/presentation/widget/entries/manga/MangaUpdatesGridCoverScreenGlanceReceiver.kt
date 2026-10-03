package tachiyomi.presentation.widget.entries.manga

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import tachiyomi.presentation.widget.util.WidgetPresence

class MangaUpdatesGridCoverScreenGlanceReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget
        get() = MangaUpdatesGridCoverScreenGlanceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetPresence.notifyChanged()
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetPresence.notifyChanged()
    }
}
