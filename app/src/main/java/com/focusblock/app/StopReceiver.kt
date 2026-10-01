package com.focusblock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Handles the "Stop" action of the persistent notification.
 *
 * A broadcast is used instead of starting the service directly because
 * background service starts are restricted since Android 12 — the broadcast
 * runs in a temporary allowed window and uses [Context.stopService], which
 * is always permitted and tears the VPN down via the service's onDestroy.
 */
class StopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BlockerVpnService.ACTION_STOP_NOTIFICATION) return
        Log.i("FocusBlock", "stop requested from notification")
        context.stopService(Intent(context, BlockerVpnService::class.java))
    }
}
