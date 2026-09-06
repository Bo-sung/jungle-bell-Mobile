package com.junglebell.mobile.widget

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Selects today's meals from the public meals snapshot (KST).
 *
 * Mirrors frontend/src/domain/meals/today.ts (selectTodayMeals / mealServiceDate):
 * the service date is parsed from the Korean date in the post title
 * (e.g. "9월 15일 중식") relative to the publication timestamp.
 */
object MealParser {

    private val KST = ZoneOffset.ofHours(9)
    private val TITLE_DATE = Regex("(?:(\\d{4})년\\s*)?(\\d{1,2})월\\s*(\\d{1,2})일")

    fun todayMeals(snapshot: PublicMealsSnapshot, now: Instant): List<MealPost> {
        val today = now.atZone(KST).toLocalDate()
        val unique = LinkedHashMap<String, MealPost>()
        (snapshot.data?.dailyMenus.orEmpty() + snapshot.data?.recentMenus.orEmpty())
            .forEach { unique.putIfAbsent(it.id, it) }
        return unique.values
            .filter { serviceDate(it, now) == today }
            .sortedBy { mealOrder(it.title) }
    }

    fun periodLabel(title: String?): String = when {
        title?.contains("조식") == true -> "조식"
        title?.contains("중식") == true -> "중식"
        title?.contains("석식") == true -> "석식"
        else -> "식단"
    }

    private fun mealOrder(title: String?): Int = when {
        title?.contains("조식") == true -> 0
        title?.contains("중식") == true -> 1
        title?.contains("석식") == true -> 2
        else -> 3
    }

    fun serviceDate(meal: MealPost, now: Instant): LocalDate? {
        val anchor = (meal.publishedAt ?: meal.firstSeenAt)
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: now
        val anchorDate = anchor.atZone(KST).toLocalDate()

        val match = meal.title?.let { TITLE_DATE.find(it) }
        if (match != null) {
            val month = match.groupValues[2].toInt()
            val day = match.groupValues[3].toInt()
            val years = if (match.groupValues[1].isNotEmpty()) {
                listOf(match.groupValues[1].toInt())
            } else {
                listOf(anchorDate.year, anchorDate.year - 1, anchorDate.year + 1)
            }
            return years
                .mapNotNull { year -> runCatching { LocalDate.of(year, month, day) }.getOrNull() }
                .minByOrNull { abs(it.toEpochDay() - anchorDate.toEpochDay()) }
        }
        return anchorDate
    }
}
