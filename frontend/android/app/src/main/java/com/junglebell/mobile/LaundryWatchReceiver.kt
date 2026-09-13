package com.junglebell.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires the scheduled laundry watch reminder. Zero network requests — the
 * notification is built from the estimate captured at registration. Later
 * corrections ride on the regular 30-minute widget sync instead.
 */
class LaundryWatchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            LaundryWatchManager.ACTION_FIRE -> {
                val watchId = intent.getStringExtra(LaundryWatchManager.EXTRA_WATCH_ID) ?: return
                LaundryWatchManager.onFire(context, watchId)
            }
            Intent.ACTION_BOOT_COMPLETED -> LaundryWatchManager.rescheduleAll(context)
        }
    }
}
