package com.junglebell.mobile.widget

import android.content.Context

/**
 * Persists the latest widget snapshot as JSON so the widget can render
 * offline (last successful sync) between WorkManager runs.
 */
object WidgetDataStore {

    private const val PREFS_NAME = "jungle_bell_widget"
    private const val KEY_CACHE = "cache_v1"

    fun load(context: Context): WidgetCache? {
        val json = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CACHE, null) ?: return null
        return runCatching {
            PublicApiClient.moshi.adapter(WidgetCache::class.java).fromJson(json)
        }.getOrNull()
    }

    fun save(context: Context, cache: WidgetCache) {
        val json = PublicApiClient.moshi.adapter(WidgetCache::class.java).toJson(cache)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CACHE, json)
            .apply()
    }
}
