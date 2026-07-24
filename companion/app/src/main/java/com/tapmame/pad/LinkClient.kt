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
        fun onRoms(names: List<String>)                 // installed romsets
        fun onRomDeleted(name: String, ok: Boolean, msg: String)
    }

    companion object {
        const val PORT = 19999
        private const val TAG = "TapMamePad"
        private val CMD_ACKS = setOf(
            "gamemenu", "settings", "menu", "exit", "exitgame", "nethost", "netjoin", "setpref", "reload")
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var running = false
    /**
     * Which connection attempt is the live one. `running` alone was a single
     * shared flag: a reconnect set it false then true again, and the PREVIOUS
     * writer — parked in outQueue.take() — woke up, saw it true, and carried
     * on. Two writers then raced for the same queue, an upload could be taken
     * by the one holding a dead socket, and whichever exited first closed the
     * OTHER one's socket in its finally block. Every writer now carries its
     * own generation and only acts while it is still the current one.
     */
    @Volatile private var generation = 0
    private val outQueue = LinkedBlockingQueue<Any>()   // String line or RomJob
    private var reader: Thread? = null
    private var writer: Thread? = null
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    @Volatile private var active = false            // we want a link (discovery running)
    @Volatile private var sweeping = false          // a subnet sweep is in progress
    @Volatile private var reconnecting = false      // the keep-trying loop is running
    @Volatile private var connectingHost: String? = null   // host of the in-flight attempt
    @Volatile private var uploading = false               // a ROM is on the wire RIGHT NOW
    @Volatile private var pendingRom: String? = null      // ROM awaiting the glasses' OK/ERR
    @Volatile private var romVerify: ((List<String>) -> Unit)? = null   // one-shot ROMS? check

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
            var knownFailures = 0
            while (active) {
                if (!isConnected && !uploading) {
                    // Connect straight to the last good address — do NOT probe it
                    // first. The glasses serve ONE client at a time, so a probe
                    // plus a connect meant two sockets per cycle competing for
                    // that single slot (the paired "companion connected" lines
                    // in the glasses log). A failed connect is just as cheap.
                    val known = lastHost()
                    if (known != null) {
                        connect(known)
                        // connect() is ASYNCHRONOUS — the socket is established on
                        // the writer thread. Testing isConnected right here always
                        // read false, which fired the /24 sweep EVERY cycle: 254
                        // probe sockets, one of them landing on the glasses and
                        // fighting the real connection for its single client slot.
                        // That storm — not the Wi-Fi, which is spotless — is what
                        // broke uploads mid-transfer. Give the attempt time to
                        // land before judging it.
                        try { Thread.sleep(3000) } catch (_: Exception) { break }
                        if (isConnected) knownFailures = 0 else knownFailures++
                    }
                    // Only sweep when we genuinely have nowhere to go: no known
                    // address at all, or it has stopped answering repeatedly.
                    if (known == null || knownFailures >= 3) {
                        startSweep()
                        knownFailures = 0
                    }
                }
                try { Thread.sleep(4000) } catch (_: Exception) { break }
            }
            reconnecting = false
        }.apply { isDaemon = true; start() }
    }

    /** A ROMS? answer goes to a pending verification first, else to the UI. */
    private fun deliverRoms(names: List<String>) {
        val v = romVerify
        if (v != null) { romVerify = null; v(names) } else listener.onRoms(names)
    }

    /**
     * Wait for a ROM's OK/ERR off the writer thread. If none comes, do NOT
     * cry failure — ASK. A stored ROM that simply lost its reply (a dropped
     * socket, a lull) is a success the player should be told about, and the
     * glasses can settle it definitively by listing what they actually hold.
     */
    private fun awaitRomAck(name: String) {
        pendingRom = name
        Thread {
            var waited = 0
            while (waited < 40_000 && pendingRom == name && running) {
                try { Thread.sleep(500) } catch (_: Exception) { return@Thread }
                waited += 500
            }
            if (pendingRom != name) return@Thread            // the ack arrived
            // No reply. Ask what the glasses are actually holding before judging.
            romVerify = { names ->
                pendingRom = null
                if (names.any { it.equals(name, ignoreCase = true) })
                    listener.onRomResult(true, "$name (stored — the reply went missing)")
                else
                    listener.onRomResult(false, "$name: no reply and not stored — try again")
            }
            if (isConnected) {
                queryRoms()
                Thread.sleep(6000)
            }
            // Still nothing back: say only what we honestly know.
            if (romVerify != null) {
                romVerify = null
                pendingRom = null
                listener.onRomResult(false, "$name: no reply from glasses — check the game list")
            }
        }.apply { isDaemon = true; start() }
    }

    // ---------------------------------------------------------- connection

    @Synchronized
    fun connect(host: String, port: Int = PORT) {
        // NEVER reconnect on top of a running upload. A stack trace caught both
        // the reconnect loop AND mDNS resolution calling connect() mid-transfer;
        // connect() begins by disconnecting, which closed the socket under the
        // ROM and produced "Connection reset" on the glasses and "Broken pipe"
        // on the phone. The transfer owns the link until it is finished.
        if (uploading) return
        if (isConnected) return
        if (running && connectingHost == host) return   // same attempt already in flight
        disconnect()
        running = true
        connectingHost = host
        val gen = ++generation
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
                while (running && generation == gen) {
                    when (val item = outQueue.take()) {
                        is String -> {
                            out.write((item + "\n").toByteArray())
                            out.flush()
                        }
                        is RomJob -> {
                            activeRom = item
                            uploading = true
                            out.write(("ROM ${item.name} ${item.size}\n").toByteArray())
                            val buf = ByteArray(65536)
                            var left = item.size
                            var sent = 0L
                            while (left > 0) {
                                val n = item.stream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n == -1) break
                                out.write(buf, 0, n)
                                left -= n; sent += n
                            }
                            out.flush()
                            item.stream.close()
                            if (sent != item.size) {
                                listener.onRomResult(false, "${item.name}: could not read the whole file ($sent/${item.size})")
                            } else {
                                // Hand the wait to a watchdog and get straight back to
                                // draining the queue. Blocking the writer here starved
                                // the keepalive polls, the glasses' idle timeout then
                                // closed the socket, and the "OK" we were waiting for
                                // could never arrive on it — the wait CAUSED the stall
                                // it was meant to report.
                                awaitRomAck(item.name)
                            }
                            uploading = false
                            activeRom = null
                        }
                    }
                }
            } catch (e: Exception) {
                uploading = false
                Log.w(TAG, "link lost: $e")
                // an upload dying mid-transfer must be REPORTED, not silent —
                // the pad showed 'Sending…' forever while the socket was dead
                activeRom?.let {
                    try { it.stream.close() } catch (_: Exception) {}
                    listener.onRomResult(false, "${it.name}: connection lost — try again")
                }
            } finally {
                // A superseded writer must exit QUIETLY: the shared socket and
                // link state belong to whoever came after it.
                if (generation == gen) {
                    running = false
                    connectingHost = null
                    try { socket?.close() } catch (_: Exception) {}
                    socket = null
                    listener.onLinkState(false, null)
                }
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
                    line == "ROMS" -> deliverRoms(emptyList())
                    line.startsWith("ROMS ") ->
                        deliverRoms(line.substring(5).split("|").filter { it.isNotBlank() })
                    line.startsWith("DELOK ") -> listener.onRomDeleted(line.substring(6).trim(), true, "")
                    line.startsWith("DELERR ") -> {
                        val rest = line.substring(7)
                        listener.onRomDeleted(rest.substringBefore(":").trim(), false, rest)
                    }
                    line.startsWith("NPADDR") -> listener.onNpAddr(line.removePrefix("NPADDR").trim())
                    line.startsWith("NAV ") -> listener.onNav(line.substring(4).trim())
                    line.startsWith("PREF ") -> {
                        val kv = line.substring(5)
                        val i = kv.indexOf('=')
                        if (i >= 0) listener.onPref(kv.substring(0, i), kv.substring(i + 1))
                    }
                    line.startsWith("OK ") && line.substring(3) in CMD_ACKS -> {}   // acks
                    line.startsWith("OK ") -> { pendingRom = null; listener.onRomResult(true, line.substring(3)) }
                    line.startsWith("ERR ") -> { pendingRom = null; listener.onRomResult(false, line.substring(4)) }
                }
            }
        } catch (_: Exception) {}
    }

    fun disconnect() {
        running = false
        generation++          // every writer alive right now is now superseded
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
    fun queryRoms() { if (running) outQueue.offer("ROMS?") }
    fun deleteRom(name: String) { if (running) outQueue.offer("DEL $name") }
    fun sendRom(name: String, size: Long, stream: InputStream) {
        if (running) outQueue.offer(RomJob(name, size, stream))
        else listener.onRomResult(false, "not connected")
    }
}
