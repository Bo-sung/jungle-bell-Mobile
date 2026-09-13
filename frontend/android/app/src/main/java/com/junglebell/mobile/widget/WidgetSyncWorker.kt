package com.junglebell.mobile.widget

import android.content.Context
import androidx.work.CoroutineWorker
import java.time.Instant
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Periodically fetches the public laundry/meals snapshots, caches them
 * locally, and refreshes every widget instance.
 *
 * Runs every 30 minutes (the minimum WorkManager period) plus a one-shot
 * run on app launch and widget first install.
 */
class WidgetSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val client = PublicApiClient.instance
            val previous = WidgetDataStore.load(applicationContext)

            // Laundry: when the user registered a wash tower source (one-time
            // QR scan), fetch it directly — bypassing the server entirely.
            // On failure fall back to the server snapshot and flag the error
            // so widgets can warn instead of silently showing old data.
            val customSource = LaundrySourceStore.url(applicationContext)
            var laundry: PublicLaundrySnapshot? = null
            var laundrySourceError = false
            if (customSource != null) {
                laundry = runCatching {
                    RawLaundrySource.resolveAndFetch(customSource, PublicApiClient.httpClient)?.snapshot
                }.getOrNull()
                laundrySourceError = laundry == null
            }
            if (laundry == null) {
                laundry = runCatching { client.laundry() }.getOrNull()
            }
            val meals = runCatching { client.meals() }.getOrNull()

            // Fetch today's meal photos for the meal widget image. Cached by
            // media sha, so a sync on an unchanged day costs nothing.
            meals?.let { snapshot ->
                MealParser.todayMeals(snapshot, Instant.now()).forEach { meal ->
                    meal.images.firstOrNull()?.let { image ->
                        MealImageStore.ensureDownloaded(applicationContext, image)
                    }
                }
            }

            // Attendance is personal: only fetch it when the in-app WebView
            // holds a mobile session cookie (user paired with their PC).
            val cookie = PublicApiClient.webViewSessionCookie()
            // sessionPresent means a real paired session (jb_device), not just
            // any anonymous visitor cookie on the origin.
            val hasMobileSession = cookie?.contains("jb_device=") == true
            val attendance = cookie
                ?.let { c -> runCatching { client.attendance(c) }.getOrNull() }

            if (laundry == null && meals == null) {
                // Public requests failed: network down or server unreachable.
                // Attendance (if any) may still have refreshed.
                if (attendance == null) return Result.retry()
            }
            WidgetDataStore.save(
                applicationContext,
                WidgetCache(
                    updatedAt = System.currentTimeMillis(),
                    laundry = laundry ?: previous?.laundry,
                    meals = meals ?: previous?.meals,
                    attendance = attendance ?: previous?.attendance,
                    sessionPresent = hasMobileSession,
                    laundrySourceError = laundrySourceError,
                ),
            )
            MealWidgetProvider.updateAll(applicationContext)
            MealDetailWidgetProvider.updateAll(applicationContext)
            LaundryWidgetProvider.updateAll(applicationContext)
            LaundryDetailWidgetProvider.updateAll(applicationContext)
            WashTowerWidgetProvider.updateAll(applicationContext)
            AttendanceWidgetProvider.updateAll(applicationContext)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_TAG = "widget-sync-periodic"
        private const val ONE_SHOT_TAG = "widget-sync-once"

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetSyncWorker>(30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_TAG, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun enqueueOneTime(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetSyncWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_SHOT_TAG, ExistingWorkPolicy.REPLACE, request)
        }

        private val mirrorLock = Any()
        private var lastMealsMirrorMs = 0L
        private var lastAttendanceMirrorMs = 0L
        private const val MIRROR_INTERVAL_MS = 5 * 60_000L

        /**
         * Called when the in-app page refreshes its own data: mirrors the
         * same fetch into the widget cache (rate-limited) so an in-app
         * refresh also refreshes the home screen widgets.
         */
        fun mirrorMeals(context: Context) {
            val now = System.currentTimeMillis()
            synchronized(mirrorLock) {
                if (now - lastMealsMirrorMs < MIRROR_INTERVAL_MS) return
                lastMealsMirrorMs = now
            }
            kotlin.concurrent.thread(name = "widget-mirror-meals") {
                val meals = runBlocking {
                    runCatching { PublicApiClient.instance.meals() }.getOrNull()
                } ?: return@thread
                val appContext = context.getApplicationContext()
                val previous = WidgetDataStore.load(appContext) ?: WidgetCache()
                WidgetDataStore.save(
                    appContext,
                    previous.copy(meals = meals, updatedAt = System.currentTimeMillis()),
                )
                MealWidgetProvider.updateAll(appContext)
                MealDetailWidgetProvider.updateAll(appContext)
            }
        }

        fun mirrorAttendance(context: Context) {
            val cookie = PublicApiClient.webViewSessionCookie()
                ?.takeIf { it.contains("jb_device=") } ?: return
            val now = System.currentTimeMillis()
            synchronized(mirrorLock) {
                if (now - lastAttendanceMirrorMs < MIRROR_INTERVAL_MS) return
                lastAttendanceMirrorMs = now
            }
            kotlin.concurrent.thread(name = "widget-mirror-attendance") {
                val attendance = runBlocking {
                    runCatching { PublicApiClient.instance.attendance(cookie) }.getOrNull()
                } ?: return@thread
                val appContext = context.getApplicationContext()
                val previous = WidgetDataStore.load(appContext) ?: WidgetCache()
                WidgetDataStore.save(
                    appContext,
                    previous.copy(
                        attendance = attendance,
                        sessionPresent = true,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                AttendanceWidgetProvider.updateAll(appContext)
            }
        }
    }
}
