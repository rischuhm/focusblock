package com.focusblock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Handles the "Stop" action of the persistent notification.
 *
 * A broadcast is used instead of starting the service directly: the
 * PendingIntent fires a broadcast (allowed from the background, unlike a
 * service start), and the receiver then hands the stop request to the
 * running service instance.
 *
 * CRITICAL: Context.stopService() alone can never stop this service. While a
 * VPN is active, the Android system holds a BIND_AUTO_CREATE connection to
 * every VpnService, and bound services are not destroyed by stopService() —
 * onDestroy() would never run and the tunnel would stay up forever. The
 * ACTION_STOP intent is therefore delivered to the live service instance,
 * which closes the TUN fd; the system then releases its binding and the
 * service is destroyed normally.
 */
class StopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BlockerVpnService.ACTION_STOP_NOTIFICATION) return
        Log.i("FocusBlock", "stop requested from notification")
        context.startService(
            Intent(context, BlockerVpnService::class.java)
                .setAction(BlockerVpnService.ACTION_STOP)
        )
    }
}
