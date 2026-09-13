package com.junglebell.mobile.widget

import android.content.Context

/**
 * The user-registered wash tower source endpoint (one-time QR scan or manual
 * input). Kept on-device only — never committed, never sent to the server.
 * Shared by the app process and the widget sync worker.
 */
object LaundrySourceStore {

    private const val PREFS_NAME = "jungle_bell_laundry_source"
    private const val KEY_ENDPOINT = "endpoint"

    fun url(context: Context): String? =
        prefs(context).getString(KEY_ENDPOINT, null)?.takeIf { it.isNotBlank() }

    /** Saves the working endpoint (already resolved by RawLaundrySource). */
    fun save(context: Context, endpoint: String) {
        prefs(context).edit().putString(KEY_ENDPOINT, endpoint).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ENDPOINT).apply()
    }

    private fun prefs(context: Context) =
        context.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
