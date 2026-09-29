package tachiyomi.presentation.widget.entries.anime

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import tachiyomi.presentation.widget.util.WidgetPresence

class AnimeUpdatesGridCoverScreenGlanceReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget
        get() = AnimeUpdatesGridCoverScreenGlanceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetPresence.notifyChanged()
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetPresence.notifyChanged()
    }
}
