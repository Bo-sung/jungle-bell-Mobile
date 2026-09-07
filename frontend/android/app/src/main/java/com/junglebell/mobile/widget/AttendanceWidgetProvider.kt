package com.junglebell.mobile.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.junglebell.mobile.R
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Attendance widget: mirrors the in-app "오늘 출석" card — the attendance
 * base date, morning/evening check cells (학습 시작 / 학습 종료 with
 * 완료/미완료), and the last sync time.
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
        private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")

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

            val cache = WidgetDataStore.load(context)
            val snapshot = cache?.attendance?.attendance
            val baseDate = snapshot?.attendanceDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            views.setTextViewText(R.id.tvAttendanceWidgetDate,
                baseDate?.let { it.format(DAY_FORMAT) } ?: WidgetCommon.todayLabel(now))

            val status = snapshot?.cohortStatus
            val hasData = snapshot != null && status != null &&
                status !in setOf("none", "unknown") && status != "ended"

            if (hasData && snapshot != null) {
                views.setViewVisibility(R.id.attendanceHintBox, View.GONE)
                views.setViewVisibility(R.id.attendanceDataBox, View.VISIBLE)
                applyCell(context, views, R.id.tvAttendanceCellMorning, "학습 시작", snapshot.morningChecked)
                applyCell(context, views, R.id.tvAttendanceCellEvening, "학습 종료", snapshot.eveningChecked)
                views.setTextViewText(R.id.tvAttendanceLine2, lastSyncLine(snapshot, now))
                views.setViewVisibility(R.id.tvAttendanceLine2, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.attendanceDataBox, View.GONE)
                views.setViewVisibility(R.id.attendanceHintBox, View.VISIBLE)
                val (hint, footer) = hintLines(cache, snapshot)
                views.setTextViewText(R.id.tvAttendanceHint, hint)
                if (footer.isEmpty()) {
                    views.setViewVisibility(R.id.tvAttendanceLine2, View.GONE)
                } else {
                    views.setTextViewText(R.id.tvAttendanceLine2, footer)
                    views.setViewVisibility(R.id.tvAttendanceLine2, View.VISIBLE)
                }
            }

            views.setOnClickPendingIntent(R.id.attendanceWidgetRoot, WidgetCommon.launchIntent(context))
            manager.updateAppWidget(widgetId, views)
        }

        /**
         * One check cell: "✓ 학습 시작" on the first line, a bold
         * "완료"/"미완료" below, tinted emerald (checked) or amber (pending)
         * like the in-app AttendanceCheck boxes.
         */
        private fun applyCell(
            context: Context,
            views: RemoteViews,
            viewId: Int,
            label: String,
            checked: Boolean,
        ) {
            val mark = if (checked) "✓" else "✗"
            val status = if (checked) "완료" else "미완료"
            val text = SpannableStringBuilder("$mark $label\n")
            val start = text.length
            text.append(status)
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(RelativeSizeSpan(1.15f), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            views.setTextViewText(viewId, text)
            views.setTextColor(
                viewId,
                ContextCompat.getColor(
                    context,
                    if (checked) R.color.bell_attendance_ok_text else R.color.bell_attendance_pending_text,
                ),
            )
            views.setInt(
                viewId,
                "setBackgroundResource",
                if (checked) R.drawable.bell_attendance_cell_ok else R.drawable.bell_attendance_cell_pending,
            )
        }

        private fun lastSyncLine(snapshot: AttendanceSnapshotInfo, now: Instant): String {
            val collectedAt = snapshot.collectedAt?.let {
                runCatching { Instant.parse(it) }.getOrNull()
            } ?: return ""
            val kst = collectedAt.atZone(WidgetCommon.KST)
            val time = kst.toLocalTime()
            val prefix = if (kst.toLocalDate() != now.atZone(WidgetCommon.KST).toLocalDate()) {
                kst.toLocalDate().let { "${it.monthValue}/${it.dayOfMonth} " }
            } else {
                ""
            }
            val base = "마지막 동기화 ${prefix}${time.hour}:${String.format("%02d", time.minute)}"
            val stale = now.toEpochMilli() - collectedAt.toEpochMilli() > WidgetCommon.ATTENDANCE_FRESHNESS_MS
            return if (stale) "$base · 오래됨" else base
        }

        private fun hintLines(
            cache: WidgetCache?,
            snapshot: AttendanceSnapshotInfo?,
        ): Pair<String, String> {
            return when {
                snapshot == null -> {
                    val hint = if (cache?.sessionPresent == true) "출석 정보 없음" else "앱에서 PC와 연결"
                    hint to ""
                }
                snapshot.cohortStatus == "upcoming" ->
                    "기수 시작 전" to (snapshot.cohortStartDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.format(DAY_FORMAT) ?: "")
                snapshot.cohortStatus == "ended" -> "기수 종료됨" to ""
                else -> "출석 정보 없음" to ""
            }
        }
    }
}
