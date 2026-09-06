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
 * Wash-tower grid widget: mirrors the in-app "워시타워 상태표" (wash-tower-grid.tsx).
 *
 * A 9-column grid with dryer/washer rows. Cell semantics (same as the web app):
 *  - available: "✓" on the zone color (men=blue, common=violet, women=rose)
 *  - running:   "HH:MM" remaining on a muted cell
 *  - error:     "!" on an orange cell
 *
 * Zone is derived from the tower number exactly like the frontend does
 * (dashboard-campus-contract.ts machineZone), so only the public API is needed.
 */
class WashTowerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (widgetId in ids) {
            render(context, manager, widgetId)
        }
        if (WidgetDataStore.load(context)?.laundry == null) {
            WidgetSyncWorker.enqueueOneTime(context)
        }
    }

    companion object {
        private const val COLUMNS = 9

        private val HEAD_IDS = intArrayOf(
            R.id.wtHead1, R.id.wtHead2, R.id.wtHead3, R.id.wtHead4, R.id.wtHead5,
            R.id.wtHead6, R.id.wtHead7, R.id.wtHead8, R.id.wtHead9,
        )
        private val DRY_IDS = intArrayOf(
            R.id.wtDry1, R.id.wtDry2, R.id.wtDry3, R.id.wtDry4, R.id.wtDry5,
            R.id.wtDry6, R.id.wtDry7, R.id.wtDry8, R.id.wtDry9,
        )
        private val WAS_IDS = intArrayOf(
            R.id.wtWas1, R.id.wtWas2, R.id.wtWas3, R.id.wtWas4, R.id.wtWas5,
            R.id.wtWas6, R.id.wtWas7, R.id.wtWas8, R.id.wtWas9,
        )

        private const val ZONE_MEN = 0
        private const val ZONE_COMMON = 1
        private const val ZONE_WOMEN = 2
        private const val ZONE_OTHER = 3

        private val TOWER_NUMBER_REGEX = Regex("(?:워시타워[_\\s-]*)?(\\d+)$")

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WashTowerWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (widgetId in ids) {
                render(context, manager, widgetId)
            }
        }

        fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.bell_wash_tower_widget)
            val now = Instant.now()
            views.setTextViewText(R.id.tvWashTowerDate, WidgetCommon.todayLabel(now))

            val machines = sortTowers(WidgetDataStore.load(context)?.laundry?.machines.orEmpty())
            for (i in 0 until COLUMNS) {
                val machine = machines.getOrNull(i)
                if (machine == null) {
                    views.setViewVisibility(HEAD_IDS[i], View.GONE)
                    views.setViewVisibility(DRY_IDS[i], View.GONE)
                    views.setViewVisibility(WAS_IDS[i], View.GONE)
                    continue
                }
                val number = towerNumber(machine.id)
                val zone = machineZone(number)
                views.setTextViewText(HEAD_IDS[i], number?.toString() ?: machine.id)
                views.setInt(HEAD_IDS[i], "setTextColor", context.getColor(zoneHeadColor(zone)))
                renderCell(views, DRY_IDS[i], machine.dryer, zone, context, now.toEpochMilli())
                renderCell(views, WAS_IDS[i], machine.washer, zone, context, now.toEpochMilli())
            }

            views.setOnClickPendingIntent(R.id.washTowerRoot, WidgetCommon.launchIntent(context))
            manager.updateAppWidget(widgetId, views)
        }

        private fun renderCell(
            views: RemoteViews,
            cellId: Int,
            appliance: LaundryAppliance?,
            zone: Int,
            context: Context,
            nowMs: Long,
        ) {
            val (text, backgroundRes, textColorRes) = when {
                appliance == null -> Triple("--", R.drawable.bell_cell_busy, R.color.bell_cell_busy_text)
                isApplianceError(appliance) -> Triple("!", R.drawable.bell_cell_error, R.color.bell_cell_error_text)
                LaundrySummary.isAvailable(appliance) -> {
                    val drawable = when (zone) {
                        ZONE_MEN -> R.drawable.bell_cell_men
                        ZONE_COMMON -> R.drawable.bell_cell_common
                        ZONE_WOMEN -> R.drawable.bell_cell_women
                        else -> R.drawable.bell_cell_busy
                    }
                    val color = when (zone) {
                        ZONE_MEN -> R.color.bell_cell_men_text
                        ZONE_COMMON -> R.color.bell_cell_common_text
                        ZONE_WOMEN -> R.color.bell_cell_women_text
                        else -> R.color.bell_cell_busy_text
                    }
                    Triple("✓", drawable, color)
                }
                else -> Triple(overviewText(appliance, nowMs), R.drawable.bell_cell_busy, R.color.bell_cell_busy_text)
            }
            views.setTextViewText(cellId, text)
            views.setInt(cellId, "setBackgroundResource", backgroundRes)
            views.setInt(cellId, "setTextColor", context.getColor(textColorRes))
        }

        /** "HH:MM" remaining, exactly like the web app's laundryOverviewText. */
        private fun overviewText(appliance: LaundryAppliance, nowMs: Long): String {
            val minutes = LaundrySummary.remainingMinutes(appliance, nowMs) ?: return "--:--"
            return "%02d:%02d".format(minutes / 60, minutes % 60)
        }

        private fun isApplianceError(appliance: LaundryAppliance): Boolean =
            appliance.errorCode != null ||
                appliance.operationalStatus == "ERROR" ||
                appliance.projection?.status == "ERROR"

        /** Mirrors washTowerNumber(): trailing digits, optional 워시타워 prefix. */
        private fun towerNumber(id: String): Int? =
            TOWER_NUMBER_REGEX.find(id.trim())?.groupValues?.get(1)?.toIntOrNull()

        /** Mirrors machineZone(): 1-5 men, 6-7 common, 8-9 women, else other. */
        private fun machineZone(number: Int?): Int = when {
            number != null && number in 1..5 -> ZONE_MEN
            number != null && number in 6..7 -> ZONE_COMMON
            number != null && number in 8..9 -> ZONE_WOMEN
            else -> ZONE_OTHER
        }

        private fun zoneHeadColor(zone: Int): Int = when (zone) {
            ZONE_MEN -> R.color.bell_cell_men_text
            ZONE_COMMON -> R.color.bell_cell_common_text
            ZONE_WOMEN -> R.color.bell_cell_women_text
            else -> R.color.bell_cell_other_text
        }

        /** Mirrors sortWashTowers(): by tower number (missing last), then id. */
        private fun sortTowers(machines: List<LaundryMachineItem>): List<LaundryMachineItem> =
            machines.sortedWith(
                compareBy<LaundryMachineItem> { towerNumber(it.id) ?: Int.MAX_VALUE }.thenBy { it.id },
            )
    }
}
