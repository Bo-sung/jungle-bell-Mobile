package com.junglebell.mobile.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.junglebell.mobile.MainActivity
import java.time.Instant
import java.time.ZoneOffset

/** Shared helpers for the widget family. */
object WidgetCommon {

    val KST: ZoneOffset = ZoneOffset.ofHours(9)
    val DAY_OF_WEEK: CharArray = charArrayOf('일', '월', '화', '수', '목', '금', '토')

    const val ATTENDANCE_FRESHNESS_MS = 15 * 60_000L

    fun todayLabel(now: Instant = Instant.now()): String {
        val today = now.atZone(KST).toLocalDate()
        // DayOfWeek.value is 1(Mon)..7(Sun); the array starts at index 0 (Sun).
        val weekday = DAY_OF_WEEK[today.dayOfWeek.value % 7]
        return "${today.monthValue}/${today.dayOfMonth}($weekday)"
    }

    /** PendingIntent that opens the app when the widget is tapped. */
    fun launchIntent(context: Context): PendingIntent {
        val launch = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, 0, launch, flags)
    }

    /** "HH:MM" in KST, or null for invalid input. */
    fun timeLabel(iso: String?): String? {
        val instant = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val time = instant.atZone(KST).toLocalTime()
        return "${time.hour}:${String.format("%02d", time.minute)}"
    }
}
