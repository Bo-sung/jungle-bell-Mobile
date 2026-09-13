package com.junglebell.mobile.widget

import android.content.Context

/**
 * Serves the user-registered wash tower source to the in-app web page and
 * keeps the widget cache in sync with it.
 *
 * The web page requests /api/public/laundry on the production origin; the
 * WebViewClient intercepts that request and swaps in this JSON (CORS would
 * block a direct fetch from the page). A fresh fetch also updates the widget
 * cache, so an in-app refresh immediately refreshes the laundry widgets too.
 */
object LaundryDirectFetcher {

    private const val FRESH_TTL_MS = 60_000L
    private const val STALE_TTL_MS = 10 * 60_000L

    private var cachedJson: String? = null
    private var cachedSha: String? = null
    private var cachedAtMs = 0L

    /**
     * Full server-contract laundry JSON from the direct source, or null when
     * no source is registered (or it is unreachable beyond the stale window —
     * the caller then passes the request through to the server).
     */
    @Synchronized
    fun snapshotJson(context: Context): String? {
        val source = LaundrySourceStore.url(context) ?: return null
        val now = System.currentTimeMillis()
        cachedJson?.let { json -> if (now - cachedAtMs < FRESH_TTL_MS) return json }

        val resolved = runCatching {
            RawLaundrySource.resolveAndFetch(source, PublicApiClient.httpClient)
        }.getOrNull()
        if (resolved == null) {
            // Source unreachable: reuse a recent response briefly, then let
            // the request fall through to the server as the fallback.
            cachedJson?.takeIf { now - cachedAtMs < STALE_TTL_MS }?.let { return it }
            return null
        }

        val changed = resolved.contentSha != cachedSha
        cachedJson = resolved.serverStyleJson
        cachedSha = resolved.contentSha
        cachedAtMs = now
        if (changed) refreshWidgetCache(context, resolved.snapshot)
        return resolved.serverStyleJson
    }

    private fun refreshWidgetCache(context: Context, snapshot: PublicLaundrySnapshot) {
        val appContext = context.getApplicationContext()
        val previous = WidgetDataStore.load(appContext)
        WidgetDataStore.save(
            appContext,
            (previous ?: WidgetCache()).copy(
                laundry = snapshot,
                laundrySourceError = false,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        LaundryWidgetProvider.updateAll(appContext)
        LaundryDetailWidgetProvider.updateAll(appContext)
        WashTowerWidgetProvider.updateAll(appContext)
    }
}
