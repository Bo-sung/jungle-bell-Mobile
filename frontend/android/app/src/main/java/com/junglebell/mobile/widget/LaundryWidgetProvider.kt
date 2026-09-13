package com.junglebell.mobile.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.junglebell.mobile.R
import java.time.Instant

/**
 * Laundry widget: available washer/dryer counts and the shortest remaining
 * time, from the public laundry API.
 */
class LaundryWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (widgetId in ids) {
            render(context, manager, widgetId)
        }
        if (WidgetDataStore.load(context)?.laundry == null) {
            WidgetSyncWorker.enqueueOneTime(context)
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, LaundryWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (widgetId in ids) {
                render(context, manager, widgetId)
            }
        }

        fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.bell_laundry_widget)
            val now = Instant.now()
            views.setTextViewText(R.id.tvLaundryWidgetDate, WidgetCommon.todayLabel(now))

            val cache = WidgetDataStore.load(context)
            val snapshot = cache?.laundry
            if (snapshot == null) {
                views.setTextViewText(R.id.tvLaundryLine1, "첫 동기화 대기")
                views.setTextViewText(R.id.tvLaundryLine2, "")
            } else {
                val (line1, line2) = LaundrySummary.formatLines(snapshot, now)
                views.setTextViewText(R.id.tvLaundryLine1, line1)
                // 직접 연결한 워시타워 소스가 끊긴 경우 이전 데이터임을 명시한다.
                views.setTextViewText(
                    R.id.tvLaundryLine2,
                    if (cache.laundrySourceError) "⚠ 소스끊김 · $line2" else line2,
                )
            }

            views.setOnClickPendingIntent(R.id.laundryWidgetRoot, WidgetCommon.launchIntent(context))
            manager.updateAppWidget(widgetId, views)
        }
    }
}
