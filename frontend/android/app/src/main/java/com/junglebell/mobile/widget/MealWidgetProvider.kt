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
 * Meals widget: today's lunch & dinner (KST) from the public meals API.
 * When the kitchen is closed (no post for today) it shows "오늘 휴무".
 */
class MealWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (widgetId in ids) {
            render(context, manager, widgetId)
        }
        if (WidgetDataStore.load(context)?.meals == null) {
            WidgetSyncWorker.enqueueOneTime(context)
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MealWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (widgetId in ids) {
                render(context, manager, widgetId)
            }
        }

        fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.bell_meal_widget)
            val now = Instant.now()
            views.setTextViewText(R.id.tvMealWidgetDate, WidgetCommon.todayLabel(now))

            val meals = WidgetDataStore.load(context)?.meals
                ?.let { MealParser.todayMeals(it, now) }
                .orEmpty()

            if (meals.isEmpty()) {
                // No post for today: the kitchen is closed.
                views.setTextViewText(R.id.tvMealLine1, "오늘 휴무")
                views.setViewVisibility(R.id.tvMealLine1, View.VISIBLE)
                views.setViewVisibility(R.id.tvMealLine2, View.GONE)
            } else {
                meals.take(2).forEachIndexed { index, meal ->
                    val textViewId = if (index == 0) R.id.tvMealLine1 else R.id.tvMealLine2
                    views.setTextViewText(textViewId, formatMealLine(meal))
                    views.setViewVisibility(textViewId, View.VISIBLE)
                }
                if (meals.size == 1) {
                    views.setViewVisibility(R.id.tvMealLine2, View.GONE)
                }
            }

            views.setOnClickPendingIntent(R.id.mealWidgetRoot, WidgetCommon.launchIntent(context))
            manager.updateAppWidget(widgetId, views)
        }

        private fun formatMealLine(meal: MealPost): String {
            val label = MealParser.periodLabel(meal.title)
            val body = meal.text.lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.take(22)
                .orEmpty()
            val line = if (body.isEmpty()) label else "$label $body"
            return if (line.length > 26) line.substring(0, 25) + "…" else line
        }
    }
}
