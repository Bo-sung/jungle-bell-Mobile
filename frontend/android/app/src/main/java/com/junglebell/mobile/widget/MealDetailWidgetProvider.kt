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
 * Meal detail widget (4x2): today's lunch AND dinner with their full menu
 * text, unlike the compact 2x2 meal widget which shows one current meal with
 * a photo banner.
 */
class MealDetailWidgetProvider : AppWidgetProvider() {

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
            val ids = manager.getAppWidgetIds(
                ComponentName(context, MealDetailWidgetProvider::class.java),
            )
            if (ids.isEmpty()) return
            for (widgetId in ids) {
                render(context, manager, widgetId)
            }
        }

        fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.bell_meal_detail_widget)
            val now = Instant.now()
            views.setTextViewText(R.id.tvMealDetailWidgetDate, WidgetCommon.todayLabel(now))

            val meals = WidgetDataStore.load(context)?.meals
                ?.let { MealParser.todayMeals(it, now) }
                .orEmpty()

            if (meals.isEmpty()) {
                views.setViewVisibility(R.id.mealDetailBlock1, View.VISIBLE)
                views.setViewVisibility(R.id.tvDetailMeal1Label, View.GONE)
                views.setTextViewText(R.id.tvDetailMeal1Text, "오늘 휴무")
                views.setViewVisibility(R.id.mealDetailBlock2, View.GONE)
            } else {
                applyBlock(views, R.id.mealDetailBlock1, R.id.tvDetailMeal1Label, R.id.tvDetailMeal1Text, meals.getOrNull(0))
                applyBlock(views, R.id.mealDetailBlock2, R.id.tvDetailMeal2Label, R.id.tvDetailMeal2Text, meals.getOrNull(1))
            }

            views.setOnClickPendingIntent(R.id.mealDetailWidgetRoot, WidgetCommon.launchIntent(context))
            manager.updateAppWidget(widgetId, views)
        }

        private fun applyBlock(
            views: RemoteViews,
            blockId: Int,
            labelId: Int,
            textId: Int,
            meal: MealPost?,
        ) {
            if (meal == null) {
                views.setViewVisibility(blockId, View.GONE)
                return
            }
            views.setViewVisibility(blockId, View.VISIBLE)
            views.setViewVisibility(labelId, View.VISIBLE)
            views.setTextViewText(labelId, MealParser.periodLabel(meal.title))
            views.setTextViewText(textId, meal.text.lineSequence().filter { it.isNotBlank() }.joinToString(" "))
        }
    }
}
