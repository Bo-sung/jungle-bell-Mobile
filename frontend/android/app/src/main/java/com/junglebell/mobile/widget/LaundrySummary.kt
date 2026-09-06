package com.junglebell.mobile.widget

import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * Aggregates the public laundry snapshot into a compact widget line.
 *
 * Availability semantics mirror frontend/src/domain/laundry/status.ts
 * (laundryAvailabilityState / laundryRemainingMinutes).
 */
object LaundrySummary {

    private val COUNTDOWN_PROJECTION_STATUSES = setOf(
        "OBSERVED",
        "ESTIMATED_RUNNING",
        "AWAITING_COMPLETION_CONFIRMATION",
    )

    fun isAvailable(appliance: LaundryAppliance?): Boolean {
        if (appliance == null) return false
        if (appliance.errorCode != null || appliance.operationalStatus == "ERROR" ||
            appliance.projection?.status == "ERROR"
        ) {
            return false
        }
        val projectionStatus = appliance.projection?.status
        if (projectionStatus != null) {
            return projectionStatus == "CONFIRMED_COMPLETED" ||
                (projectionStatus == "IDLE" && appliance.operationalStatus != "SCHEDULED")
        }
        return appliance.operationalStatus == "IDLE" || appliance.operationalStatus == "COMPLETED"
    }

    fun remainingMinutes(appliance: LaundryAppliance?, nowMs: Long): Int? {
        val fallback = appliance?.projection?.remainingMinutes ?: return null
        val countsDown = appliance?.operationalStatus == "RUNNING" &&
            appliance.projection?.status in COUNTDOWN_PROJECTION_STATUSES
        if (!countsDown) return max(0, fallback)
        val finishMs = appliance.estimatedFinishAt
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: return fallback
        return max(0, ((finishMs - nowMs + 59_999) / 60_000).toInt())
    }

    fun format(snapshot: PublicLaundrySnapshot, now: Instant): String {
        val (line1, line2) = formatLines(snapshot, now)
        return if (line2.isEmpty()) line1 else "$line1 · $line2"
    }

    /**
     * Two-line widget format:
     * line1 = "세탁기 2/9 · 건조기 2/9"
     * line2 = "7분 남음" / "모두 사용 가능"
     */
    fun formatLines(snapshot: PublicLaundrySnapshot, now: Instant): Pair<String, String> {
        var washerAvailable = 0
        var washerTotal = 0
        var dryerAvailable = 0
        var dryerTotal = 0
        var minRemaining: Int? = null

        fun track(minOfValue: Int?) {
            if (minOfValue != null) {
                minRemaining = if (minRemaining == null) minOfValue else min(minRemaining!!, minOfValue)
            }
        }

        val nowMs = now.toEpochMilli()
        for (machine in snapshot.machines) {
            machine.washer?.let {
                washerTotal++
                if (isAvailable(it)) washerAvailable++ else track(remainingMinutes(it, nowMs))
            }
            machine.dryer?.let {
                dryerTotal++
                if (isAvailable(it)) dryerAvailable++ else track(remainingMinutes(it, nowMs))
            }
        }

        val line1 = "세탁기 ${washerAvailable}/${washerTotal} · 건조기 ${dryerAvailable}/${dryerTotal}"
        val line2 = minRemaining
            ?.takeIf { it > 0 && it < 360 }
            ?.let { "${formatRemaining(it)} 남음" }
            ?: "모두 사용 가능"
        return line1 to line2
    }

    private fun formatRemaining(minutes: Int): String {
        if (minutes >= 60) {
            val h = minutes / 60
            val m = minutes % 60
            return if (m == 0) "${h}시간" else "${h}시간 ${m}분"
        }
        return "${minutes}분"
    }

    /**
     * Per-machine detail line for the detailed laundry widget:
     * "워시타워 1  세탁 ○ · 건조 32분"
     * ○ = available, ✕ = error, N분 = minutes remaining, 마무리 = finishing up.
     */
    fun formatMachineLine(machine: LaundryMachineItem, now: Instant): String {
        val nowMs = now.toEpochMilli()
        val name = machine.id.replace("_", " ").ifEmpty { "기계" }
        return "$name  세탁 ${applianceLabel(machine.washer, nowMs)} · 건조 ${applianceLabel(machine.dryer, nowMs)}"
    }

    private fun applianceLabel(appliance: LaundryAppliance?, nowMs: Long): String {
        if (appliance == null) return "-"
        if (appliance.errorCode != null || appliance.operationalStatus == "ERROR" ||
            appliance.projection?.status == "ERROR"
        ) {
            return "✕"
        }
        if (isAvailable(appliance)) return "○"
        val minutes = remainingMinutes(appliance, nowMs)
        return when {
            minutes == null -> "작동중"
            minutes <= 0 -> "마무리"
            else -> "${minutes}분"
        }
    }
}
