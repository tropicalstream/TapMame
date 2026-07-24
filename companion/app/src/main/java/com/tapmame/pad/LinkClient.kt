package com.tapmame.pad

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

/**
 * The phone end of the TapMame link. Discovers the glasses via NSD
 * (_tapmame._tcp), keeps one TCP connection, ships PAD state changes with
 * no delay (TCP_NODELAY) and streams ROM files. All I/O off the UI thread;
 * a single writer thread drains a queue so pad packets never block touch
 * handling.
 */
class LinkClient(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onLinkState(connected: Boolean, host: String?)
        fun onGame(game: String)
        fun onRomResult(ok: Boolean, msg: String)
        fun onServerMsg(msg: String)
        fun onNpAddr(addr: String)
        fun onNav(mode: String)
        fun onPref(key: String, value: String)
        fun onRestarting()      // glasses are restarting to reload the game list
    }

    companion object {
        const val PORT = 19999
        private const val TAG = "TapMamePad"
        private val CMD_ACKS = setOf(
            "gamemenu", "settings", "menu", "exit", "exitgame", "nethost", "netjoin", "setpref", "reload")
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var running = false
    private val outQueue = LinkedBlockingQueue<Any>()   // String line or RomJob
    private var reader: Thread? = null
    private var writer: Thread? = null
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    @Volatile private var active = false            // we want a link (discovery running)
    @Volatile private var sweeping = false          // a subnet sweep is in progress
    @Volatile private var reconnecting = false      // the keep-trying loop is running
    @Volatile private var connectingHost: String? = null   // host of the in-flight attempt

    private class RomJob(val name: String, val size: Long, val stream: InputStream)

    private fun prefs() = context.getSharedPreferences("pad", Context.MODE_PRIVATE)
    private fun saveHost(ip: String) { prefs().edit().putString("lastHost", ip).apply() }
    private fun lastHost(): String? = prefs().getString("lastHost", null)

    // ---------------------------------------------------------- discovery

    fun startDiscovery() {
        stopDiscovery()
        active = true
        // Keep trying until we're linked, so a dropped connection (the glasses
        // restarting to reload, Wi-Fi flapping, a stale mDNS record) always
        // heals itself instead of stranding the pad. Each attempt re-tries the
        // last address that worked and scans the /24 for the glasses' current
        // one, both validated by a PING/PONG handshake.
        startReconnectLoop()
        try {
            nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            discovery = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) {}
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
                override fun onServiceLost(i: NsdServiceInfo) {}
                override fun onServiceFound(i: NsdServiceInfo) {
                    if (!i.serviceType.contains("_tapmame._tcp")) return
                    @Suppress("DEPRECATION")
                    nsd?.resolveService(i, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(x: NsdServiceInfo, e: Int) {}
                        override fun onServiceResolved(x: NsdServiceInfo) {
                            val host = x.host?.hostAddress ?: return
                            Log.i(TAG, "resolved glasses at $host:${x.port}")
                            connect(host, x.port)
                        }
                    })
                }
            }
            nsd?.discoverServices("_tapmame._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: Exception) {
            Log.w(TAG, "NSD discovery unavailable: $e")
        }
    }

    fun stopDiscovery() {
        active = false
        try { discovery?.let { nsd?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        discovery = null
    }

    // ------------------------------------------------ resilient host finding

    /** True if a TapMame link server answers the PING handshake at ip:19999. */
    private fun probe(ip: String): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, PORT), 250)
            s.soTimeout = 500
            s.getOutputStream().apply { write("PING\n".toByteArray()); flush() }
            BufferedInputStream(s.getInputStream()).bufferedReader().readLine()?.startsWith("PONG") == true
        }
    } catch (_: Exception) { false }

    /** Verify one address in the background, then connect only if it's really us. */
    private fun probeThenConnect(ip: String) {
        Thread {
            if (active && !isConnected && probe(ip) && active && !isConnected) connect(ip)
        }.apply { isDaemon = true; start() }
    }

    /** Local /24 prefix (e.g. "192.168.1.") from our own Wi-Fi address. */
    private fun localSubnetBase(): String? {
        try {
            for (nif in java.net.NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is java.net.Inet4Address && addr.isSiteLocalAddress)
                        return (addr.hostAddress ?: continue).substringBeforeLast('.') + "."
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Last resort when discovery is stale: probe every host on the local /24 in
     * parallel and connect to the first that answers the TapMame handshake — so
     * we find the glasses at whatever IP they're on now, no mDNS required. Runs
     * once at a time, and only if we're still unlinked a beat after discovery.
     */
    private fun startSweep() {
        if (sweeping) return
        sweeping = true
        Thread {
            try {
                Thread.sleep(2500)                       // give NSD / last-good first crack
                if (!active || isConnected) return@Thread
                val base = localSubnetBase() ?: return@Thread
                val found = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val pool = java.util.concurrent.Executors.newFixedThreadPool(40)
                for (i in 1..254) {
                    val ip = base + i
                    pool.execute {
                        if (found.get() == null && active && !isConnected && probe(ip))
                            found.compareAndSet(null, ip)
                    }
                }
                pool.shutdown()
                pool.awaitTermination(6, java.util.concurrent.TimeUnit.SECONDS)
                pool.shutdownNow()
                found.get()?.let { if (active && !isConnected) connect(it) }
            } catch (_: Exception) {
            } finally { sweeping = false }
        }.apply { isDaemon = true; start() }
    }

    /** Keep attempting to (re)connect on a steady beat until we're linked. */
    private fun startReconnectLoop() {
        if (reconnecting) return
        reconnecting = true
        Thread {
            while (active) {
                if (!isConnected) {
                    lastHost()?.let { probeThenConnect(it) }   // fast: the address that last worked
                    startSweep()                                // fallback: find the current one
                }
                try { Thread.sleep(4000) } catch (_: Exception) { break }
            }
            reconnecting = false
        }.apply { isDaemon = true; start() }
    }

    // ---------------------------------------------------------- connection

    @Synchronized
    fun connect(host: String, port: Int = PORT) {
        if (isConnected) return
        if (running && connectingHost == host) return   // same attempt already in flight
        disconnect()
        running = true
        connectingHost = host
        writer = Thread {
            var activeRom: RomJob? = null
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 4000)
                socket = s
                saveHost(host)                 // remember the address that actually worked
                listener.onLinkState(true, host)
                reader = Thread { readLoop(s) }.apply { isDaemon = true; start() }
                val out = BufferedOutputStream(s.getOutputStream())
                while (running) {
                    when (val item = outQueue.take()) {
                        is String -> {
                            out.write((item + "\n").toByteArray())
                            out.flush()
                        }
                        is RomJob -> {
                            activeRom = item
                            out.write(("ROM ${item.name} ${item.size}\n").toByteArray())
                            val buf = ByteArray(65536)
                            var left = item.size
                            while (left > 0) {
                                val n = item.stream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n == -1) break
                                out.write(buf, 0, n)
                                left -= n
                            }
                            out.flush()
                            item.stream.close()
                            activeRom = null
                            // the server's OK/ERR reply arrives via readLoop
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "link lost: $e")
                // an upload dying mid-transfer must be REPORTED, not silent —
                // the pad showed 'Sending…' forever while the socket was dead
                activeRom?.let {
                    try { it.stream.close() } catch (_: Exception) {}
                    listener.onRomResult(false, "${it.name}: connection lost — try again")
                }
            } finally {
                running = false
                connectingHost = null
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                listener.onLinkState(false, null)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun readLoop(s: Socket) {
        try {
            val br = BufferedInputStream(s.getInputStream()).bufferedReader()
            while (running) {
                val line = br.readLine() ?: break
                when {
                    line.startsWith("GAME ") -> listener.onGame(line.substring(5).trim())
                    line == "GAME" -> listener.onGame("")
                    line.startsWith("MSG ") -> listener.onServerMsg(line.substring(4))
                    line == "RESTARTING" -> listener.onRestarting()
                    line.startsWith("NPADDR") -> listener.onNpAddr(line.removePrefix("NPADDR").trim())
                    line.startsWith("NAV ") -> listener.onNav(line.substring(4).trim())
                    line.startsWith("PREF ") -> {
                        val kv = line.substring(5)
                        val i = kv.indexOf('=')
                        if (i >= 0) listener.onPref(kv.substring(0, i), kv.substring(i + 1))
                    }
                    line.startsWith("OK ") && line.substring(3) in CMD_ACKS -> {}   // acks
                    line.startsWith("OK ") -> listener.onRomResult(true, line.substring(3))
                    line.startsWith("ERR ") -> listener.onRomResult(false, line.substring(4))
                }
            }
        } catch (_: Exception) {}
    }

    fun disconnect() {
        running = false
        // report queued uploads instead of silently dropping them
        while (true) {
            val item = outQueue.poll() ?: break
            if (item is RomJob) {
                try { item.stream.close() } catch (_: Exception) {}
                listener.onRomResult(false, "${item.name}: not sent — reconnect and retry")
            }
        }
        outQueue.offer("")   // unblock the writer's take()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    val isConnected get() = running && socket?.isConnected == true

    // ---------------------------------------------------------- commands

    fun sendPad(mask: Long) { if (running) outQueue.offer("PAD 0 $mask") }
    fun sendAxis(type: Int, x: Float, y: Float) { if (running) outQueue.offer("AXIS $type 0 $x $y") }
    fun queryGame() { if (running) outQueue.offer("GAME?") }
    fun queryNav() { if (running) outQueue.offer("NAV?") }
    fun getPref(key: String) { if (running) outQueue.offer("GETPREF $key") }
    fun setPref(key: String, type: String, value: String) { if (running) outQueue.offer("SETPREF $key $type $value") }
    fun openGameSettings() { if (running) outQueue.offer("CMD GAMEMENU") }
    fun openGlobalSettings() { if (running) outQueue.offer("CMD SETTINGS") }
    fun netHost() { if (running) outQueue.offer("CMD NETHOST") }
    fun netJoin(addr: String) { if (running) outQueue.offer("CMD NETJOIN $addr") }
    fun queryNpAddr() { if (running) outQueue.offer("NPADDR?") }
    fun exit() { if (running) outQueue.offer("CMD EXIT") }
    fun menu() { if (running) outQueue.offer("CMD MENU") }
    fun reloadGames() { if (running) outQueue.offer("CMD RELOAD") }
    fun sendRom(name: String, size: Long, stream: InputStream) {
        if (running) outQueue.offer(RomJob(name, size, stream))
        else listener.onRomResult(false, "not connected")
    }
}
