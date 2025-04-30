package com.nextcloud.talk.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver that listens for device boot completion to restart periodic notifications.
 * This ensures our notifications continue after device reboots.
 */
class BootPingRestartReceiver : BroadcastReceiver() {
    private val TAG = "BootPingRestartReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device booted, starting PingForegroundService")
            PingForegroundService.start(context)
        }
    }
} 