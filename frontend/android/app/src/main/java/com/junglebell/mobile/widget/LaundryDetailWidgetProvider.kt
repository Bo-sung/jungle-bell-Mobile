package com.junglebell.mobile.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.junglebell.mobile.R
import java.time.Instant

/**
 * Detailed laundry widget (4x2 and larger): shows the washer/dryer status of
 * every machine, one line per machine. Data source is the same public API
 * cache as the compact laundry widget.
 */
class LaundryDetailWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (widgetId in ids) {
            render(context, manager, widgetId)
        }
        if (WidgetDataStore.load(context)?.laundry == null) {
            WidgetSyncWorker.enqueueOneTime(context)
        }
    }

    companion object {
        /** Fixed row slots; machines beyond the last slot are not rendered. */
        private val ROW_IDS = intArrayOf(
            R.id.laundryDetailRow1,
            R.id.laundryDetailRow2,
            R.id.laundryDetailRow3,
            R.id.laundryDetailRow4,
            R.id.laundryDetailRow5,
            R.id.laundryDetailRow6,
            R.id.laundryDetailRow7,
            R.id.laundryDetailRow8,
            R.id.laundryDetailRow9,
        )

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, LaundryDetailWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (widgetId in ids) {
                render(context, manager, widgetId)
            }
        }

        fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.bell_laundry_detail_widget)
            val now = Instant.now()
            views.setTextViewText(R.id.tvLaundryDetailDate, WidgetCommon.todayLabel(now))

            val snapshot = WidgetDataStore.load(context)?.laundry
            if (snapshot == null) {
                views.setTextViewText(R.id.tvLaundryDetailSummary, "첫 동기화 대기")
            } else {
                val (line1, line2) = LaundrySummary.formatLines(snapshot, now)
                views.setTextViewText(R.id.tvLaundryDetailSummary, "$line1 · $line2")
            }

            val machines = snapshot?.machines.orEmpty()
            for ((index, rowId) in ROW_IDS.withIndex()) {
                val machine = machines.getOrNull(index)
                if (machine == null) {
                    views.setViewVisibility(rowId, View.GONE)
                } else {
                    views.setViewVisibility(rowId, View.VISIBLE)
                    views.setTextViewText(rowId, LaundrySummary.formatMachineLine(machine, now))
                }
            }

            views.setOnClickPendingIntent(R.id.laundryDetailRoot, WidgetCommon.launchIntent(context))
            manager.updateAppWidget(widgetId, views)
        }
    }
}
