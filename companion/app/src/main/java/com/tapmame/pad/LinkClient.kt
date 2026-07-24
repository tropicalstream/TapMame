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
    }

    companion object {
        const val PORT = 19999
        private const val TAG = "TapMamePad"
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var running = false
    private val outQueue = LinkedBlockingQueue<Any>()   // String line or RomJob
    private var reader: Thread? = null
    private var writer: Thread? = null
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    private class RomJob(val name: String, val size: Long, val stream: InputStream)

    // ---------------------------------------------------------- discovery

    fun startDiscovery() {
        stopDiscovery()
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
        try { discovery?.let { nsd?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        discovery = null
    }

    // ---------------------------------------------------------- connection

    @Synchronized
    fun connect(host: String, port: Int = PORT) {
        if (running && socket?.isConnected == true) return
        disconnect()
        running = true
        writer = Thread {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 4000)
                socket = s
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
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "link lost: $e")
            } finally {
                running = false
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
                    line.startsWith("OK ") -> listener.onRomResult(true, line.substring(3))
                    line.startsWith("ERR ") -> listener.onRomResult(false, line.substring(4))
                }
            }
        } catch (_: Exception) {}
    }

    fun disconnect() {
        running = false
        outQueue.clear()
        outQueue.offer("")   // unblock the writer's take()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    val isConnected get() = running && socket?.isConnected == true

    // ---------------------------------------------------------- commands

    fun sendPad(mask: Long) { if (running) outQueue.offer("PAD 0 $mask") }
    fun queryGame() { if (running) outQueue.offer("GAME?") }
    fun sendRom(name: String, size: Long, stream: InputStream) {
        if (running) outQueue.offer(RomJob(name, size, stream))
        else listener.onRomResult(false, "not connected")
    }
}
