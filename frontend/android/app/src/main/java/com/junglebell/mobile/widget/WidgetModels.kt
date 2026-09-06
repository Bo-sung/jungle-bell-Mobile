package com.junglebell.mobile.widget

/**
 * Minimal models for the public API subset used by the home screen widget.
 *
 * Source of truth: server PublicDataController + PublicDataModels.
 * The widget only consumes public endpoints, so no authentication is involved.
 */

data class PublicLaundrySnapshot(
    val asOf: String? = null,
    val machines: List<LaundryMachineItem> = emptyList(),
)

data class LaundryMachineItem(
    val id: String = "",
    val washer: LaundryAppliance? = null,
    val dryer: LaundryAppliance? = null,
)

data class LaundryAppliance(
    val machineId: String = "",
    val appliance: String? = null,
    val operationalStatus: String? = null,
    val remainingMinutes: Int? = null,
    val totalMinutes: Int? = null,
    val estimatedFinishAt: String? = null,
    val projection: LaundryProjection? = null,
    val errorCode: String? = null,
)

data class LaundryProjection(
    val status: String? = null,
    val remainingMinutes: Int? = null,
    val estimated: Boolean? = null,
)

data class PublicMealsSnapshot(
    val asOf: String? = null,
    val data: MealsData? = null,
)

data class MealsData(
    val dailyMenus: List<MealPost> = emptyList(),
    val recentMenus: List<MealPost> = emptyList(),
)

data class MealPost(
    val id: String = "",
    val kind: String? = null,
    val title: String? = null,
    val text: String = "",
    val publishedAt: String? = null,
    val firstSeenAt: String? = null,
)

/**
 * Personal attendance snapshot. Served by GET /api/me/attendance with the
 * mobile session cookie (the same one the in-app WebView uses after pairing).
 */
data class MobileAttendanceEnvelope(
    val attendance: AttendanceSnapshotInfo? = null,
    val freshness: String? = null,
)

data class AttendanceSnapshotInfo(
    val attendanceDate: String? = null,
    val cohortId: String? = null,
    val cohortStatus: String? = null,
    val cohortStartDate: String? = null,
    val cohortEndDate: String? = null,
    val morningChecked: Boolean = false,
    val eveningChecked: Boolean = false,
    val collectedAt: String? = null,
)

/** Cached snapshot bundle persisted between sync runs. */
data class WidgetCache(
    val updatedAt: Long = 0,
    val laundry: PublicLaundrySnapshot? = null,
    val meals: PublicMealsSnapshot? = null,
    val attendance: MobileAttendanceEnvelope? = null,
    val sessionPresent: Boolean = false,
)
