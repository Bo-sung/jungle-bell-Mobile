package com.junglebell.mobile

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.junglebell.mobile.widget.LaundrySourceStore
import com.junglebell.mobile.widget.PublicApiClient
import com.junglebell.mobile.widget.PublicLaundrySnapshot
import com.junglebell.mobile.widget.RawLaundrySource
import com.junglebell.mobile.widget.WidgetDataStore
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local (server-free) laundry completion reminders.
 *
 * The in-app "내 세탁 알림" card issues /api/me/laundry-watches calls; the
 * injected fetch hook routes them here instead of the server.
 *
 * Rate-safety: the watch schedules ONE alarm from the completion estimate we
 * already received (widget cache first, at most one fresh fetch at
 * registration). The alarm fires with zero network requests. Corrections
 * (early completion, estimate drift) piggyback on the already-scheduled
 * 30-minute widget sync — the source never sees extra traffic.
 */
object LaundryWatchManager {

    private const val PREFS_NAME = "jungle_bell_laundry_watches"
    private const val KEY_WATCHES = "watches"
    private const val KEY_TARGET_MS = "targetMs_"
    const val ACTION_FIRE = "com.junglebell.mobile.LAUNDRY_WATCH_FIRE"
    const val EXTRA_WATCH_ID = "watchId"
    const val CHANNEL_ID = "jungle_bell"

    /** 등록 시 위젯 캐시를 신선하다고 인정하는 최대 나이. */
    private const val CACHE_FRESH_MS = 10 * 60_000L
    /** 예상 완료가 5분 이상 밀리면 알람을 다시 맞춘다. */
    private const val DRIFT_RESCHEDULE_MS = 5 * 60_000L

    data class Watch(
        val id: String,
        val machineId: String,
        val appliance: String,
        val sessionId: String?,
        val notificationMode: String,
        val notifyBeforeMinutes: Int,
        val status: String,
        val createdAtEpochMs: Long,
        val updatedAtEpochMs: Long,
    )

    // ---------- storage ----------

    private fun prefs(context: Context) =
        context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(context: Context): MutableList<Watch> {
        val json = prefs(context).getString(KEY_WATCHES, null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(json)
            MutableList(array.length()) { i ->
                val o = array.getJSONObject(i)
                Watch(
                    id = o.getString("id"),
                    machineId = o.getString("machineId"),
                    appliance = o.getString("appliance"),
                    sessionId = o.optString("sessionId").takeIf { it.isNotEmpty() },
                    notificationMode = o.getString("notificationMode"),
                    notifyBeforeMinutes = o.getInt("notifyBeforeMinutes"),
                    status = o.getString("status"),
                    createdAtEpochMs = o.getLong("createdAtEpochMs"),
                    updatedAtEpochMs = o.getLong("updatedAtEpochMs"),
                )
            }
        }.getOrDefault(mutableListOf())
    }

    @Synchronized
    private fun save(context: Context, watches: List<Watch>) {
        val array = JSONArray()
        watches.forEach { w ->
            array.put(
                JSONObject()
                    .put("id", w.id)
                    .put("machineId", w.machineId)
                    .put("appliance", w.appliance)
                    .put("sessionId", w.sessionId ?: "")
                    .put("notificationMode", w.notificationMode)
                    .put("notifyBeforeMinutes", w.notifyBeforeMinutes)
                    .put("status", w.status)
                    .put("createdAtEpochMs", w.createdAtEpochMs)
                    .put("updatedAtEpochMs", w.updatedAtEpochMs),
            )
        }
        prefs(context).edit().putString(KEY_WATCHES, array.toString()).apply()
    }

    // ---------- JSON bridge (called from the injected fetch hook) ----------

    /** GET /api/me/laundry-watches → {"watches":[...]} in the UI schema. */
    @Synchronized
    fun listJson(context: Context): String {
        val arr = JSONArray()
        load(context).forEach { w -> arr.put(uiJson(w)) }
        return JSONObject().put("watches", arr).toString()
    }

    /**
     * POST /api/me/laundry-watches. 완료 예상 시각은 이미 받은 위젯 캐시에서
     * 우선 읽고, 캐시가 없거나 오래됐거나 예상 시각이 이미 지났을 때만 1회
     * 새로 가져온다. 그래도 못 구하면 null (등록 실패로 처리).
     */
    @Synchronized
    fun createJson(context: Context, body: String): String? {
        val input = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val machineId = input.optString("machineId")
        val appliance = input.optString("appliance")
        if (machineId.isEmpty() || appliance.isEmpty()) return null
        val now = System.currentTimeMillis()

        val finishAtMs = finishEstimate(context, machineId, appliance, now)
        if (finishAtMs == null) return null

        val id = "jbw_" + sha256("$now|$machineId|$appliance|${input.optString("sessionId")}").take(64)
        val watch = Watch(
            id = id,
            machineId = machineId,
            appliance = appliance,
            sessionId = input.optString("sessionId").takeIf { it.isNotEmpty() },
            notificationMode = input.getString("notificationMode"),
            notifyBeforeMinutes = input.getInt("notifyBeforeMinutes"),
            status = "active",
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        val watches = load(context)
        watches.removeAll {
            it.machineId == watch.machineId && it.appliance == watch.appliance && it.status == "active"
        }
        watches.add(watch)
        save(context, watches)
        schedule(context, watch, finishAtMs)
        return uiJson(watch)
    }

    /** DELETE /api/me/laundry-watches/{id}. */
    @Synchronized
    fun delete(context: Context, id: String) {
        val watches = load(context)
        watches.removeAll { it.id == id }
        save(context, watches)
        cancelAlarm(context, id)
    }

    /**
     * 완료 예상 시각 추정: 위젯 캐시(이미 수신한 데이터) 우선, 없으면 1회 직접
     * 조회. 실행 중인 기기의 estimatedFinishAt만 유효하다.
     */
    private fun finishEstimate(context: Context, machineId: String, appliance: String, now: Long): Long? {
        WidgetDataStore.load(context)?.laundry?.let { snapshot ->
            val ageOk = snapshot.asOf?.let {
                runCatching { now - Instant.parse(it).toEpochMilli() < CACHE_FRESH_MS }.getOrDefault(false)
            } ?: false
            if (ageOk) {
                applianceFrom(snapshot, machineId, appliance)?.estimatedFinishAt?.let {
                    runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
                }?.takeIf { it > now }?.let { return it }
            }
        }
        val source = LaundrySourceStore.url(context) ?: return null
        val resolved = runCatching {
            RawLaundrySource.resolveAndFetch(source, PublicApiClient.httpClient)
        }.getOrNull() ?: return null
        return applianceFrom(resolved.snapshot, machineId, appliance)?.estimatedFinishAt?.let {
            runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
        }?.takeIf { it > now }
    }

    private fun applianceFrom(snapshot: PublicLaundrySnapshot, machineId: String, appliance: String) =
        snapshot.machines.firstOrNull { it.id == machineId }
            ?.let { if (appliance == "washer") it.washer else it.dryer }

    /** The UI contract (personal-contract laundryWatchSchema, strict fields). */
    private fun uiJson(w: Watch): String =
        JSONObject()
            .put("id", w.id)
            .put("machineId", w.machineId)
            .put("appliance", w.appliance)
            .put("sessionId", w.sessionId ?: JSONObject.NULL)
            .put("notificationMode", w.notificationMode)
            .put("notifyBeforeMinutes", w.notifyBeforeMinutes)
            .put("status", w.status)
            .put("createdAtEpochMs", w.createdAtEpochMs)
            .put("updatedAtEpochMs", w.updatedAtEpochMs)
            .toString()

    // ---------- scheduling ----------

    private fun triggerFor(watch: Watch, finishAtMs: Long): Long = when (watch.notificationMode) {
        "before-completion" -> finishAtMs - watch.notifyBeforeMinutes * 60_000L
        "estimated-completion" -> finishAtMs
        else -> finishAtMs + 3 * 60_000L // confirmed-completion
    }

    @Synchronized
    fun schedule(context: Context, watch: Watch, finishAtMs: Long) {
        if (watch.status != "active") return
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val trigger = triggerFor(watch, finishAtMs)
        prefs(context).edit().putLong(KEY_TARGET_MS + watch.id, trigger).apply()
        alarm.setWindow(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + max(1_000L, trigger - System.currentTimeMillis()),
            60_000L,
            firePendingIntent(context, watch.id),
        )
    }

    private fun cancelAlarm(context: Context, watchId: String) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.cancel(firePendingIntent(context, watchId))
        prefs(context).edit().remove(KEY_TARGET_MS + watchId).apply()
    }

    private fun firePendingIntent(context: Context, watchId: String): PendingIntent {
        val intent = Intent(context, LaundryWatchReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_WATCH_ID, watchId)
        return PendingIntent.getBroadcast(
            context,
            watchId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ---------- alarm fire (네트워크 요청 없음) ----------

    fun onFire(context: Context, watchId: String) {
        val watch = load(context).firstOrNull { it.id == watchId } ?: return
        val target = prefs(context).getLong(KEY_TARGET_MS + watchId, 0L)
        val late = System.currentTimeMillis() - target
        val lateMinutes = (late / 60_000L).toInt()
        val machineLabel = watch.machineId.replace(Regex("(?:워시타워[_\\s-]*)?(\\d+)$"), "워시타워 $1")
        val applianceLabel = if (watch.appliance == "washer") "세탁기" else "건조기"

        val (title, body) = when {
            lateMinutes >= 3 -> "$machineLabel $applianceLabel" to
                "예상보다 늦어졌습니다. 세탁실에서 상태를 확인하세요."
            watch.notificationMode == "before-completion" -> "$machineLabel $applianceLabel" to
                "완료 ${watch.notifyBeforeMinutes}분 전입니다. 세탁물을 챙기세요."
            else -> "$machineLabel $applianceLabel" to "예상 완료 시간입니다. 세탁물을 챙기세요."
        }
        notify(context, watchId, title, body)
        delete(context, watchId)
    }

    // ---------- 30분 주기 동기화에 편승한 보정 (추가 요청 없음) ----------

    /**
     * 위젯 동기화가 이미 받아온 스냅샷으로 활성 감시를 보정한다:
     * 세탁이 일찍 끝났으면 조용히 취소(+1회 안내), 예상 완료가 5분 이상
     * 밀렸으면 알람 시각만 다시 맞춘다. 원천에는 아무 요청도 보내지 않는다.
     */
    @Synchronized
    fun syncWithSnapshot(context: Context, snapshot: PublicLaundrySnapshot) {
        val asOf = snapshot.asOf?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return
        if (System.currentTimeMillis() - asOf.toEpochMilli() > 30 * 60_000L) return // 오래된 스냅샷은 무시

        val watches = load(context)
        val active = watches.filter { it.status == "active" }
        if (active.isEmpty()) return
        val now = System.currentTimeMillis()
        var changed = false

        for (watch in active) {
            val appliance = applianceFrom(snapshot, watch.machineId, watch.appliance) ?: continue
            val storedTarget = prefs(context).getLong(KEY_TARGET_MS + watch.id, 0L)
            val finishAtMs = appliance.estimatedFinishAt?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            }

            when {
                // 실행이 끝났는데 아직 알림 전이면: 조용히 취소하고 1회 안내.
                appliance.operationalStatus != "RUNNING" -> {
                    notify(
                        context,
                        watch.id,
                        watch.machineId.replace(Regex("(?:워시타워[_\\s-]*)?(\\d+)$"), "워시타워 $1") +
                            if (watch.appliance == "washer") " 세탁기" else " 건조기",
                        "감시 중인 세탁이 종료된 것으로 보입니다.",
                    )
                    cancelAlarm(context, watch.id)
                    watches.removeAll { it.id == watch.id }
                    changed = true
                }
                finishAtMs == null -> Unit
                // 예상 완료가 크게 밀리면 알람 시각만 재조정.
                abs(triggerFor(watch, finishAtMs) - storedTarget) > DRIFT_RESCHEDULE_MS -> {
                    schedule(context, watch, finishAtMs)
                }
                else -> Unit
            }
        }
        if (changed) save(context, watches)
    }

    /** 재부팅 후 활성 감시 알람을 복구한다. */
    fun rescheduleAll(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        load(context).filter { it.status == "active" }.forEach { watch ->
            val trigger = prefs(context).getLong(KEY_TARGET_MS + watch.id, 0L)
            if (trigger <= 0) return@forEach
            alarm.setWindow(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + max(1_000L, trigger - System.currentTimeMillis()),
                60_000L,
                firePendingIntent(context, watch.id),
            )
        }
    }

    private fun notify(context: Context, notificationIdSeed: String, title: String, body: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Jungle Bell",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Jungle Bell 앱 알림" },
            )
        }
        if (!manager.areNotificationsEnabled()) return
        val builder =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(context)
            }
        val notification: Notification = builder
            .setSmallIcon(com.junglebell.mobile.R.drawable.ic_notification_bell)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        manager.notify(notificationIdSeed.hashCode(), notification)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
