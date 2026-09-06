package com.junglebell.mobile.widget

import android.app.Application

/**
 * Application entry point: schedules the widget's periodic background sync
 * and refreshes the widget whenever the app process (re)starts.
 */
class BellApp : Application() {

    override fun onCreate() {
        super.onCreate()
        WidgetSyncWorker.schedulePeriodic(this)
        WidgetSyncWorker.enqueueOneTime(this)
    }
}
