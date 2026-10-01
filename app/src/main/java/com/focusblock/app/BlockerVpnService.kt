package com.focusblock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.focusblock.app.blocking.DomainMatcher
import com.focusblock.app.net.DnsMessages
import com.focusblock.app.net.Ipv4Udp
import com.focusblock.app.net.UdpForwarder
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * FocusBlock's blocking engine.
 *
 * It starts a purely local VPN tunnel. Only DNS traffic (port 53, plus a few
 * well-known public resolver IPs so apps that hardcode them are still covered)
 * is routed into the tunnel; everything else on the phone keeps using the
 * normal network directly. DNS queries for blocked domains get a forged
 * NXDOMAIN answer ("this site does not exist"), everything else is forwarded
 * to a real resolver through a protected socket. No traffic is inspected,
 * logged, or sent anywhere except DNS questions themselves.
 *
 * Stopping is deliberately robust and works from every entry point (in-app
 * switch, notification action, system): [shutdown] is idempotent, closes the
 * tunnel synchronously and always removes the foreground state. Note that
 * stopService() alone can never destroy this service while the VPN is up:
 * the system holds a BIND_AUTO_CREATE connection to every active VpnService,
 * so the stop request must reach the running instance via ACTION_STOP, close
 * the TUN fd, and only then does the system release the binding and destroy
 * the service.
 */
class BlockerVpnService : VpnService() {

    @Volatile private var matcher = DomainMatcher(emptyList())
    @Volatile private var running = false
    @Volatile private var blockedCount = 0L

    private var tun: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null
    private var readerThread: Thread? = null
    private val forwarders = ConcurrentHashMap<String, UdpForwarder>()
    private val writeLock = Any()

    @Volatile private var lastBlockedNotifiedMs = 0L

    companion object {
        const val ACTION_START = "com.focusblock.app.action.START"
        const val ACTION_STOP = "com.focusblock.app.action.STOP"
        const val ACTION_STOP_NOTIFICATION = "com.focusblock.app.action.STOP_NOTIFICATION"
        const val ACTION_LIST_CHANGED = "com.focusblock.app.action.LIST_CHANGED"
        const val ACTION_STATE = "com.focusblock.app.action.STATE"
        const val EXTRA_RUNNING = "running"

        private const val TAG = "FocusBlock"
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_BLOCKED = "blocked"
        private const val NOTIFICATION_ID = 42
        private const val BLOCKED_NOTIFICATION_ID = 43
        private const val BLOCKED_NOTIF_INTERVAL_MS = 5_000L
        private const val VPN_MTU = 1500

        /**
         * Address assigned to the TUN interface itself. Must be DIFFERENT from
         * [VPN_ADDRESS]: packets addressed to one of the device's own interface
         * addresses are delivered locally by the kernel and never enter the
         * tunnel, which previously broke every DNS lookup ("no website loads").
         */
        const val VPN_INTERFACE_ADDRESS = "10.0.0.1"

        /**
         * Dummy DNS server address "inside" the tunnel. Advertised to the
         * system via addDnsServer and routed into the TUN via addRoute, so
         * queries for it land in the tunnel and can be answered/forged there.
         */
        const val VPN_ADDRESS = "10.0.0.2"

        /** Upstream resolver used for allowed queries sent to the dummy address. */
        private const val DEFAULT_UPSTREAM = "1.1.1.1"

        /**
         * Well-known public resolvers routed into the tunnel as well, so apps
         * that ignore the system resolver and talk to these directly are still
         * filtered. Non-DNS traffic to these IPs is dropped (they are resolver
         * anycast addresses, rarely browsed to directly).
         */
        private val CAPTURED_RESOLVERS = listOf(
            "1.1.1.1", "1.0.0.1",
            "8.8.8.8", "8.8.4.4",
            "9.9.9.9", "149.112.112.112",
            "208.67.222.222", "208.67.220.220",
        )

        private val VPN_ADDRESS_IP = Ipv4Udp.ipToInt(VPN_ADDRESS)
        private val DEFAULT_UPSTREAM_IP = Ipv4Udp.ipToInt(DEFAULT_UPSTREAM)

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "stop requested")
                // Tear down synchronously, then ask the system to destroy us.
                // Doing the cleanup here (not only in the asynchronous
                // onDestroy) avoids races with a START intent that may already
                // be queued behind this one.
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LIST_CHANGED -> {
                // Only refresh the list; never start the tunnel from this action.
                if (tun != null) {
                    refreshMatcher()
                    updateStatusNotification()
                }
                return START_NOT_STICKY
            }
            else -> {
                refreshMatcher()
                startForegroundWithNotification()
                if (tun == null || readerThread?.isAlive != true) {
                    establishTunnel()
                } else {
                    updateStatusNotification()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN consent revoked")
        shutdown()
        stopSelf()
    }

    // ---------------------------------------------------------------- setup

    private fun refreshMatcher() {
        matcher = DomainMatcher(BlockListStore(this).getAll())
        Log.i(TAG, "block list loaded: ${matcher.size} entries")
    }

    private fun establishTunnel() {
        try {
            // Drop any stale tunnel first (e.g. the reader thread has died).
            closeTunnelQuietly()
            val builder = Builder()
                .setSession("FocusBlock")
                .setMtu(VPN_MTU)
                .addAddress(VPN_INTERFACE_ADDRESS, 32)
                .addDnsServer(VPN_ADDRESS)
                .addRoute(VPN_ADDRESS, 32)
            for (resolver in CAPTURED_RESOLVERS) {
                builder.addRoute(resolver, 32)
            }
            val fd = builder.establish()
            if (fd == null) {
                Log.e(TAG, "establish() returned null (consent missing?)")
                stopSelf()
                return
            }
            tun = fd
            input = FileInputStream(fd.fileDescriptor)
            output = FileOutputStream(fd.fileDescriptor)
            running = true
            isRunning = true
            broadcastState(true)
            readerThread = thread(name = "focusblock-reader", isDaemon = true) { readLoop() }
            Log.i(TAG, "VPN tunnel established")
        } catch (e: Exception) {
            Log.e(TAG, "failed to establish tunnel", e)
            stopSelf()
        }
    }

    /**
     * Fully tears down the tunnel and the foreground state. Idempotent: safe
     * to call multiple times and from any thread. Unlike a guarded version,
     * this ALWAYS clears the foreground notification and broadcasts the new
     * state, so the service can never get stuck "running".
     */
    private fun shutdown() {
        Log.i(TAG, "shutting down")
        running = false
        isRunning = false
        closeTunnelQuietly()
        // Snapshot before closing: UdpForwarder.close() fires onClose, which
        // removes the entry from the map — iterating live would risk a
        // ConcurrentModificationException in the middle of the teardown.
        forwarders.values.toList().forEach { it.close() }
        forwarders.clear()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed", e)
        }
        broadcastState(false)
    }

    private fun closeTunnelQuietly() {
        try {
            input?.close()
        } catch (_: Exception) {
        }
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        input = null
        output = null
    }

    // -------------------------------------------------------------- packet IO

    private fun readLoop() {
        val buf = ByteArray(32767)
        while (running) {
            val n = try {
                input?.read(buf) ?: break
            } catch (e: Exception) {
                Log.i(TAG, "tun read ended: ${e.message}")
                break
            }
            if (n <= 0) continue
            try {
                handlePacket(buf, n)
            } catch (t: Throwable) {
                Log.w(TAG, "packet handling error", t)
            }
        }
        if (running) {
            // TUN closed unexpectedly (e.g. always-on VPN replaced, consent revoked).
            stopSelf()
        }
    }

    private fun writePacket(packet: ByteArray) {
        val out = output ?: return
        synchronized(writeLock) {
            out.write(packet)
        }
    }

    private fun handlePacket(buf: ByteArray, n: Int) {
        if (n < Ipv4Udp.IP_HEADER_LEN || buf[0].toInt() ushr 4 != 4) return // IPv4 only
        val udp = Ipv4Udp.parse(buf, n) ?: return                        // non-UDP / fragmented / malformed
        if (udp.dstPort != 53) return                                      // only DNS is routed here anyway
        val upstreamIp = if (udp.dstIp == VPN_ADDRESS_IP) preferredUpstream() else udp.dstIp

        val payload = buf.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        val name = DnsMessages.extractQueryName(payload, payload.size)

        if (name != null && matcher.isBlocked(name)) {
            blockedCount++
            val dnsReply = DnsMessages.buildNxdomainResponse(payload, payload.size) ?: return
            writePacket(Ipv4Udp.buildResponse(udp, dnsReply))
            notifyBlocked(name)
            return
        }
        forwardDns(udp, payload, upstreamIp)
    }

    /**
     * The resolver allowed queries are forwarded to. Uses the DNS servers of
     * the underlying (non-VPN) network — most compatible with carrier/portal
     * networks that intercept port 53 — and falls back to 1.1.1.1.
     */
    private fun preferredUpstream(): Int {
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return DEFAULT_UPSTREAM_IP
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val link = cm.getLinkProperties(network) ?: continue
                for (dns in link.dnsServers) {
                    if (dns is Inet4Address) {
                        return Ipv4Udp.ipToInt(dns.hostAddress)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to read underlying DNS, using fallback", e)
        }
        return DEFAULT_UPSTREAM_IP
    }

    private fun forwardDns(udp: Ipv4Udp.UdpInfo, payload: ByteArray, upstreamIp: Int) {
        // The client address AND the queried destination identify a flow:
        // replies must come from the exact address the client queried.
        val key = "${udp.srcIp}:${udp.srcPort}:${udp.dstIp}"
        var fwd = forwarders[key]
        if (fwd == null) {
            val created = createForwarder(udp, upstreamIp, key) ?: return
            val existing = forwarders.putIfAbsent(key, created)
            fwd = if (existing != null) {
                created.close()
                existing
            } else {
                created
            }
        }
        try {
            fwd.send(payload)
        } catch (e: Exception) {
            Log.w(TAG, "forward send failed, recreating forwarder", e)
            forwarders.remove(key, fwd)
            fwd.close()
            val created = createForwarder(udp, upstreamIp, key) ?: return
            forwarders[key] = created
            try {
                created.send(payload)
            } catch (e2: Exception) {
                Log.w(TAG, "retry send failed", e2)
            }
        }
    }

    private fun createForwarder(udp: Ipv4Udp.UdpInfo, upstreamIp: Int, key: String): UdpForwarder? {
        return try {
            val socket = DatagramSocket()
            val protectedOk = protect(socket)
            if (!protectedOk) {
                Log.w(TAG, "protect() returned false for forwarder socket")
            }
            UdpForwarder(
                socket = socket,
                clientIp = udp.srcIp,
                clientPort = udp.srcPort,
                serverIp = upstreamIp,
                serverPort = 53,
                // The reply injected into the tunnel must appear to come from
                // the address the client originally queried (e.g. the dummy
                // 10.0.0.2), not from the real upstream — otherwise the
                // client's kernel drops it as an unsolicited packet.
                replySourceIp = udp.dstIp,
                replySourcePort = udp.dstPort,
                onPacket = { packet ->
                    try {
                        writePacket(packet)
                    } catch (t: Throwable) {
                        Log.w(TAG, "failed writing upstream reply to tun", t)
                    }
                },
                onClose = { forwarders.remove(key, it) },
            )
        } catch (e: Exception) {
            Log.e(TAG, "failed to create forwarder", e)
            null
        }
    }

    // ---------------------------------------------------------- notifications

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Protection status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BLOCKED, "Blocked site alerts", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun startForegroundWithNotification() {
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildStatusNotification(), type)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun buildStatusNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // A broadcast (not a service start) is used here: a PendingIntent that
        // starts a service can be rejected on Android 12+ when the app is in
        // the background, which used to make the Stop action silently fail.
        val stop = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, StopReceiver::class.java)
                .setAction(ACTION_STOP_NOTIFICATION)
                .setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("FocusBlock is active")
            .setContentText("Blocking ${matcher.size} site(s). Everything stays on your device.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateStatusNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildStatusNotification())
    }

    private fun notifyBlocked(name: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockedNotifiedMs < BLOCKED_NOTIF_INTERVAL_MS) return
        lastBlockedNotifiedMs = now
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(this, CHANNEL_BLOCKED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Blocked: $name")
            .setContentText("Stay focused — this site is on your block list.")
            .setAutoCancel(true)
            .build()
        nm.notify(BLOCKED_NOTIFICATION_ID, notification)
    }

    private fun broadcastState(running: Boolean) {
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_RUNNING, running)
        )
    }
}
