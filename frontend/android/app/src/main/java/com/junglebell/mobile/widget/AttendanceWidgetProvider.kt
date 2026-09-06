package com.junglebell.mobile.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.junglebell.mobile.R
import java.time.Instant
import java.time.LocalDate

/**
 * Attendance widget: morning/evening check state for the paired account.
 *
 * Data comes from GET /api/me/attendance using the mobile session cookie that
 * the in-app WebView stores after pairing. Without a session the widget shows
 * a connect hint; the public widgets are not affected.
 */
class AttendanceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (widgetId in ids) {
            render(context, manager, widgetId)
        }
        if (WidgetDataStore.load(context) == null) {
            WidgetSyncWorker.enqueueOneTime(context)
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AttendanceWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (widgetId in ids) {
                render(context, manager, widgetId)
            }
        }

        fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.bell_attendance_widget)
            val now = Instant.now()
            views.setTextViewText(R.id.tvAttendanceWidgetDate, WidgetCommon.todayLabel(now))

            val cache = WidgetDataStore.load(context)
            val snapshot = cache?.attendance?.attendance

            val (line1, line2) = when {
                snapshot == null -> {
                    val text = if (cache?.sessionPresent == true) "출석 정보 없음" else "앱에서 PC와 연결"
                    text to ""
                }
                else -> when (snapshot.cohortStatus) {
                    "upcoming" -> "기수 시작 전" to (snapshot.cohortStartDate ?: "")
                    "ended" -> "기수 종료됨" to ""
                    "none", "unknown" -> "출석 정보 없음" to ""
                    else -> checkStateLines(snapshot, now)
                }
            }
            views.setTextViewText(R.id.tvAttendanceLine1, line1)
            views.setViewVisibility(R.id.tvAttendanceLine1, View.VISIBLE)
            if (line2.isEmpty()) {
                views.setViewVisibility(R.id.tvAttendanceLine2, View.GONE)
            } else {
                views.setTextViewText(R.id.tvAttendanceLine2, line2)
                views.setViewVisibility(R.id.tvAttendanceLine2, View.VISIBLE)
            }

            views.setOnClickPendingIntent(R.id.attendanceWidgetRoot, WidgetCommon.launchIntent(context))
            manager.updateAppWidget(widgetId, views)
        }

        private fun checkStateLines(snapshot: AttendanceSnapshotInfo, now: Instant): Pair<String, String> {
            val mark = { checked: Boolean -> if (checked) "✓" else "–" }
            val line1 = "아침 ${mark(snapshot.morningChecked)} · 저녁 ${mark(snapshot.eveningChecked)}"

            val collectedAt = snapshot.collectedAt?.let {
                runCatching { Instant.parse(it) }.getOrNull()
            }
            val stale = collectedAt != null &&
                now.toEpochMilli() - collectedAt.toEpochMilli() > WidgetCommon.ATTENDANCE_FRESHNESS_MS

            val timeText = collectedAt?.let {
                val kst = it.atZone(WidgetCommon.KST)
                val datePrefix = if (kst.toLocalDate() == now.atZone(WidgetCommon.KST).toLocalDate()) {
                    ""
                } else {
                    val d: LocalDate = kst.toLocalDate()
                    "${d.monthValue}/${d.dayOfMonth} "
                }
                val time = kst.toLocalTime()
                "${datePrefix}${time.hour}:${String.format("%02d", time.minute)} 기준"
            }.orEmpty()
            val line2 = if (timeText.isEmpty()) "" else if (stale) "$timeText · 오래됨" else timeText
            return line1 to line2
        }
    }
}
