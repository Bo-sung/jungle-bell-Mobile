package com.junglebell.mobile.widget

import java.security.MessageDigest
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fetches the wash tower's own monitoring endpoint (the URL a user registers
 * once via QR / manual input) and normalizes it into:
 *  - [snapshot]: the compact model the home screen widgets render, and
 *  - [serverStyleJson]: a full `/api/public/laundry`-shaped response the
 *    in-app web page can consume (schema-compliant with the server contract),
 * so both display paths work without the Jungle Bell server.
 *
 * Mapping mirrors server/worker LaundryNormalizer + PublicDataService
 * projection (display subset only — no events, risks or capacity).
 */
object RawLaundrySource {

    private val KNOWN_STATES = setOf(
        "POWER_OFF", "INITIAL", "RESERVED", "DETECTING", "DISPENSING", "SOAKING",
        "WASHING", "RINSING", "SPINNING", "RUNNING", "DRYING", "COOLING",
        "REFRESHING", "WRINKLE_CARE", "PAUSE", "END", "ERROR",
    )

    /**
     * Tries the URL as-is, then as an /api/status child (the monitoring site
     * serves an HTML page at its root and the machine JSON at /api/status).
     */
    fun resolveAndFetch(inputUrl: String, client: OkHttpClient): ResolvedSource? {
        val trimmed = inputUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        fetchRoot(trimmed, client)?.let { return build("$trimmed/", it) }
        val child = "$trimmed/api/status"
        return fetchRoot(child, client)?.let { build(child, it) }
    }

    private fun fetchRoot(url: String, client: OkHttpClient): Pair<String, JSONObject>? = runCatching {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        val body = response.body?.string()
        response.close()
        body ?: return null
        val root = JSONObject(body)
        if (!isMachineMap(root)) return null
        body to root
    }.getOrNull()

    private fun build(endpoint: String, parsed: Pair<String, JSONObject>): ResolvedSource {
        val (body, root) = parsed
        val now = Instant.now()
        return ResolvedSource(
            endpoint = endpoint,
            snapshot = normalize(root, now),
            serverStyleJson = serverStyleJson(root, now),
            contentSha = sha256(body),
        )
    }

    /** The payload must be an object of machineId -> {washer|dryer:{runState…}}. */
    private fun isMachineMap(root: JSONObject): Boolean {
        val keys = root.keys()
        if (!keys.hasNext()) return false
        for (machineId in keys) {
            val tower = root.optJSONObject(machineId) ?: return false
            val hasAppliance = tower.optJSONObject("washer") != null ||
                tower.optJSONObject("dryer") != null
            if (!hasAppliance) return false
        }
        return true
    }

    private fun sortedMachineIds(root: JSONObject): List<String> =
        root.keys().asSequence().toList()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })

    private fun normalize(root: JSONObject, now: Instant): PublicLaundrySnapshot {
        val machines = sortedMachineIds(root).map { machineId ->
            val tower = root.getJSONObject(machineId)
            LaundryMachineItem(
                id = machineId,
                washer = tower.optJSONObject("washer")?.let { appliance(machineId, "washer", it, now) },
                dryer = tower.optJSONObject("dryer")?.let { appliance(machineId, "dryer", it, now) },
            )
        }
        return PublicLaundrySnapshot(asOf = now.toString(), machines = machines)
    }

    private fun info(raw: JSONObject): ApplianceInfo {
        val rawState = raw.optJSONObject("runState")
            ?.optString("currentState")
            ?.takeIf { it.isNotEmpty() }
        val state = rawState?.takeIf { it in KNOWN_STATES }
        val timer = raw.optJSONObject("timer") ?: JSONObject()
        val remaining = timer.optInt("remainHour", 0) * 60 + timer.optInt("remainMinute", 0)
        val total = timer.optInt("totalHour", 0) * 60 + timer.optInt("totalMinute", 0)
        val error = raw.optString("error").takeIf { it.isNotEmpty() }
        val operational = operationalStatus(state, remaining, error)
        val running = operational == "RUNNING"
        val observedAt = Instant.now()
        val finishAt = if (running) observedAt.plusSeconds(remaining * 60L).toString() else null
        val projectedStatus = when (operational) {
            "RUNNING" -> if (remaining <= 0) "AWAITING_COMPLETION_CONFIRMATION" else "ESTIMATED_RUNNING"
            "COMPLETED" -> "CONFIRMED_COMPLETED"
            "PAUSED" -> "PAUSED"
            "ERROR" -> "ERROR"
            "IDLE", "SCHEDULED" -> "IDLE"
            else -> "UNKNOWN"
        }
        val projectedRemaining = when (operational) {
            "RUNNING" -> finishAt?.let {
                max(0, ceil((Instant.parse(it).toEpochMilli() - observedAt.toEpochMilli()) / 60_000.0).toInt())
            } ?: remaining
            "COMPLETED" -> 0
            "UNKNOWN" -> null
            else -> remaining
        }
        return ApplianceInfo(
            rawState = rawState,
            state = state,
            operational = operational,
            remaining = remaining,
            total = total,
            error = error,
            finishAt = finishAt,
            observedAt = observedAt,
            projectedStatus = projectedStatus,
            projectedRemaining = projectedRemaining,
        )
    }

    private fun appliance(machineId: String, kind: String, raw: JSONObject, now: Instant): LaundryAppliance {
        val info = info(raw)
        return LaundryAppliance(
            machineId = machineId,
            appliance = kind,
            operationalStatus = info.operational,
            remainingMinutes = info.remaining,
            totalMinutes = info.total,
            estimatedFinishAt = info.finishAt,
            errorCode = info.error,
            projection = LaundryProjection(
                remainingMinutes = info.projectedRemaining,
                status = info.projectedStatus,
                estimated = false,
            ),
        )
    }

    /** Full server-contract JSON (dashboardLaundrySnapshotSchema compliant). */
    private fun serverStyleJson(root: JSONObject, now: Instant): String {
        val quality = JSONObject()
            .put("collectorHealthy", true)
            .put("collection", "SUCCESS")
            .put("sourceFreshness", "REFRESH_OBSERVED")
            .put("lastCheckedAt", now.toString())
            .put("expectedRefreshIntervalSeconds", 300)
        val machines = JSONArray()
        for (machineId in sortedMachineIds(root)) {
            val tower = root.getJSONObject(machineId)
            val machine = JSONObject().put("id", machineId)
            for (kind in listOf("washer", "dryer")) {
                val raw = tower.optJSONObject(kind)
                machine.put(
                    kind,
                    if (raw == null) JSONObject.NULL else applianceJson(machineId, kind, raw, now),
                )
            }
            machines.put(machine)
        }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("asOf", now.toString())
            .put("final", true)
            .put("quality", quality)
            .put("machines", machines)
            .put("capacity", capacityJson(normalize(root, now)))
            .toString()
    }

    /**
     * 서버 PublicDataService.capacity와 동일한 규칙의 시작 가능 횟수 계산.
     * 6·7번은 공용 구역이라 남성·여성 양쪽에 모두 산입된다.
     */
    private fun capacityJson(snapshot: PublicLaundrySnapshot): JSONObject {
        val basis = "WASHER_AND_DRYER_HEADROOM_60_MIN"
        return JSONObject()
            .put("basis", basis)
            .put("men", capacityEstimateJson(snapshot, "men"))
            .put("women", capacityEstimateJson(snapshot, "women"))
    }

    private fun machineNumber(id: String): Int? =
        Regex("(?:워시타워[_\\s-]*)?(\\d+)$").find(id.trim())?.groupValues?.get(1)?.toIntOrNull()

    private fun zoneMatches(id: String, access: String): Boolean {
        val number = machineNumber(id) ?: return false
        return number in 6..7 || (if (access == "men") number in 1..5 else number in 8..9)
    }

    private fun available(appliance: LaundryAppliance?): Boolean =
        appliance != null && appliance.operationalStatus == "IDLE" && appliance.projection?.status == "IDLE"

    private fun dryerWithinHour(appliance: LaundryAppliance?): Boolean {
        appliance ?: return false
        if (appliance.operationalStatus == "ERROR" ||
            appliance.projection?.status in setOf("PAUSED", "AWAITING_COMPLETION_CONFIRMATION", "UNKNOWN")
        ) {
            return false
        }
        val remaining = appliance.projection?.remainingMinutes ?: return false
        return remaining in 0..60 &&
            (appliance.operationalStatus == "RUNNING" ||
                appliance.projection.status in setOf("OBSERVED", "ESTIMATED_RUNNING"))
    }

    private fun pendingDryer(appliance: LaundryAppliance?): Boolean {
        appliance ?: return false
        if (available(appliance) || appliance.operationalStatus == "ERROR") return false
        val projection = appliance.projection ?: return false
        if (appliance.operationalStatus !in setOf("RUNNING", "COURSE_RUNNING", "PAUSED", "SCHEDULED") &&
            projection.status !in setOf("OBSERVED", "ESTIMATED_RUNNING", "AWAITING_COMPLETION_CONFIRMATION", "PAUSED")
        ) {
            return false
        }
        if (appliance.operationalStatus in setOf("PAUSED", "SCHEDULED") ||
            projection.status in setOf("PAUSED", "AWAITING_COMPLETION_CONFIRMATION")
        ) {
            return true
        }
        return projection.remainingMinutes == null || projection.remainingMinutes <= 60
    }

    private fun capacityEstimateJson(snapshot: PublicLaundrySnapshot, access: String): JSONObject {
        val accessible = snapshot.machines.filter { zoneMatches(it.id, access) }
        val required = if (access == "men") (1..7).toList() else (6..9).toList()
        val complete = required.all { number ->
            snapshot.machines.any { machineNumber(it.id) == number && it.washer != null && it.dryer != null }
        }
        val washerAvailable = accessible.count { available(it.washer) }
        val projectedDryerSupply = accessible.count { available(it.dryer) || dryerWithinHour(it.dryer) }
        val pendingDryerLoads = accessible.count { pendingDryer(it.washer) }
        val dryerHeadroom = max(0, projectedDryerSupply - pendingDryerLoads)
        // 항상 신선한 직접 조회 결과라면 collection=SUCCESS, REFRESH_OBSERVED가 보장된다.
        val reliable = complete
        return JSONObject()
            .put("access", access)
            .put("washerAvailable", washerAvailable)
            .put("projectedDryerSupply", projectedDryerSupply)
            .put("pendingDryerLoads", pendingDryerLoads)
            .put("dryerHeadroom", dryerHeadroom)
            .put("startableLoads", if (reliable) min(washerAvailable, dryerHeadroom) else JSONObject.NULL)
            .put("reliable", reliable)
    }

    private fun applianceJson(machineId: String, kind: String, raw: JSONObject, now: Instant): JSONObject {
        val info = info(raw)
        val projection = JSONObject()
            .put("status", info.projectedStatus)
            .put("remainingMinutes", info.projectedRemaining ?: JSONObject.NULL)
            .put("estimated", false)
        val state = info.rawState?.let { JSONObject().put("code", it) } ?: JSONObject.NULL
        return JSONObject()
            .put("appliance", kind)
            .put("operationalStatus", info.operational)
            .put("projection", projection)
            .put("state", state)
            .put("remainingMinutes", info.remaining)
            .put("totalMinutes", info.total)
            .put("startedAt", JSONObject.NULL)
            .put("estimatedFinishAt", info.finishAt ?: JSONObject.NULL)
            .put("observedAt", info.observedAt.toString())
            .put("sessionId", sessionIdFor(machineId, kind, raw, info) ?: JSONObject.NULL)
            .put("errorCode", info.error ?: JSONObject.NULL)
    }

    /**
     * 세버와 동일한 규칙으로 세션 식별자를 만든다. 감시 UI는 sessionId가
     * null이면 비활성화되므로 작동 중인 기기에는 반드시 채운다. 세탁기는
     * cycle 횟수(원천에 있음), 건조기는 작동이 이어지는 동안 이전 값을 유지한다.
     */
    private fun sessionIdFor(machineId: String, kind: String, raw: JSONObject, info: ApplianceInfo): String? {
        val key = "$machineId:$kind"
        val prev = PREVIOUS_SESSIONS[key]
        val prevSession = prev?.substringBefore('\u0001')?.takeIf { it.isNotEmpty() }
        val prevOperational = prev?.substringAfter('\u0001', "")
        val value = when {
            info.operational in setOf("IDLE", "COMPLETED") -> prevSession
            kind == "washer" && raw.optJSONObject("cycle").let { it != null && it.has("cycleCount") } ->
                "$machineId:washer:cycle:" + raw.optJSONObject("cycle")!!.optInt("cycleCount")
            prevOperational == "RUNNING" && prevSession != null -> prevSession
            else -> "$machineId:$kind:" + info.observedAt.toEpochMilli()
        }
        PREVIOUS_SESSIONS[key] = (value ?: "") + "\u0001" + info.operational
        return value
    }

    private val PREVIOUS_SESSIONS = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun operationalStatus(state: String?, remaining: Int, error: String?): String = when {
        state == null -> "UNKNOWN"
        state == "ERROR" || error != null -> "ERROR"
        state == "PAUSE" -> "PAUSED"
        state == "END" -> "COMPLETED"
        state in setOf("POWER_OFF", "INITIAL") -> "IDLE"
        state == "RESERVED" -> "SCHEDULED"
        remaining > 0 || state != "POWER_OFF" -> "RUNNING"
        else -> "IDLE"
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private class ApplianceInfo(
        val rawState: String?,
        val state: String?,
        val operational: String,
        val remaining: Int,
        val total: Int,
        val error: String?,
        val finishAt: String?,
        val observedAt: Instant,
        val projectedStatus: String,
        val projectedRemaining: Int?,
    )

    data class ResolvedSource(
        val endpoint: String,
        val snapshot: PublicLaundrySnapshot,
        val serverStyleJson: String,
        val contentSha: String,
    )
}
