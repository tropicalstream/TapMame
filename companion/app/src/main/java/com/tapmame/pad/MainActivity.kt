package com.tapmame.pad

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.EditText
import android.widget.Toast
import kotlin.math.abs

/**
 * TapMame Pad — the phone companion. Discovers the glasses on the LAN,
 * becomes the arcade controller, and pushes ROM files into the glasses'
 * roms folder. The ⚙ gear (top-left of the pad) opens the actions dialog
 * (send ROM, reconnect, manual IP).
 */
class MainActivity : Activity(), LinkClient.Listener {

    private lateinit var pad: PadView
    private lateinit var link: LinkClient
    private val ui = Handler(Looper.getMainLooper())
    private val gamePoll = object : Runnable {
        override fun run() {
            link.queryGame()
            link.queryNav()
            ui.postDelayed(this, 2000)
        }
    }
    private var pendingRomPick = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Controls.init(this)
        link = LinkClient(this, this)
        pad = PadView(this,
            { mask -> lastTouchMask = mask; link.sendPad(mask or btMask) },
            { type, x, y -> link.sendAxis(type, x, y) },
            { actionsDialog() },
            { label -> svcAction(label) })
        pad.haptics = getSharedPreferences("pad", MODE_PRIVATE).getBoolean("haptics", true)
        setContentView(pad)
    }

    private fun svcAction(label: String) {
        // Both are context-aware on the glasses: MENU = open menu / back one
        // level; EXIT = leave the game (with an SBS confirm) or, when not in
        // a game, go straight out to the main game-select screen.
        when (label) {
            "MENU" -> link.menu()
            "EXIT" -> link.exit()
        }
    }

    // -------------------------------------------- physical gamepad passthrough
    // Any Bluetooth/USB controller paired to the PHONE drives the glasses
    // automatically: its buttons merge into the same PAD bitmask the on-screen
    // pad sends, and its left stick also rides the analog channel so paddle /
    // dial / trackball games get true analog from a real stick.

    private var btMask = 0L          // held bits from the physical controller
    private var lastTouchMask = 0L   // held bits from the on-screen pad
    private var btAxisX = 0f
    private var btAxisY = 0f

    private fun pushBt(m: Long) {
        if (m == btMask) return
        btMask = m
        link.sendPad(lastTouchMask or btMask)
    }

    private fun keyBit(code: Int): Long = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> PadView.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> PadView.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> PadView.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> PadView.RIGHT
        KeyEvent.KEYCODE_BUTTON_A -> PadView.BTN[0]
        KeyEvent.KEYCODE_BUTTON_B -> PadView.BTN[1]
        KeyEvent.KEYCODE_BUTTON_X -> PadView.BTN[2]
        KeyEvent.KEYCODE_BUTTON_Y -> PadView.BTN[3]
        KeyEvent.KEYCODE_BUTTON_L1 -> PadView.BTN[4]
        KeyEvent.KEYCODE_BUTTON_R1 -> PadView.BTN[5]
        KeyEvent.KEYCODE_BUTTON_START -> PadView.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> PadView.COIN
        else -> 0L
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val bit = keyBit(e.keyCode)
        if (bit != 0L) {
            if (e.repeatCount == 0) when (e.action) {
                KeyEvent.ACTION_DOWN -> pushBt(btMask or bit)
                KeyEvent.ACTION_UP -> pushBt(btMask and bit.inv())
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            && e.action == MotionEvent.ACTION_MOVE) {
            val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val sx = e.getAxisValue(MotionEvent.AXIS_X)
            val sy = e.getAxisValue(MotionEvent.AXIS_Y)
            // hat + left stick both count as the digital dpad (0.5 threshold)
            var m = btMask and (PadView.UP or PadView.DOWN or PadView.LEFT or PadView.RIGHT).inv()
            fun dir(v: Float, neg: Long, pos: Long) {
                if (v < -0.5f) m = m or neg else if (v > 0.5f) m = m or pos
            }
            dir(hx, PadView.LEFT, PadView.RIGHT); dir(hy, PadView.UP, PadView.DOWN)
            dir(sx, PadView.LEFT, PadView.RIGHT); dir(sy, PadView.UP, PadView.DOWN)
            pushBt(m)
            // the stick also feeds MAME's analog inputs (paddle/dial/wheel/…)
            val ax = if (abs(sx) > 0.12f) sx else 0f
            val ay = if (abs(sy) > 0.12f) sy else 0f
            if (abs(ax - btAxisX) > 0.01f || abs(ay - btAxisY) > 0.01f) {
                btAxisX = ax; btAxisY = ay
                link.sendAxis(PadView.LEFT_STICK_DATA, ax, -ay)
            }
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    // ------------------------------------------------- per-game layout choice

    private fun layoutKey(rom: String?) = "layout." + (rom ?: "_default")

    /** The pad profile to show: the player's forced preset, else the auto one. */
    private fun effectiveProfile(rom: String?): ControlProfile {
        val id = getSharedPreferences("pad", MODE_PRIVATE).getString(layoutKey(rom), "auto")
        return Controls.preset(id) ?: Controls.forGame(rom)
    }

    private fun controllerDialog() {
        val prefs = getSharedPreferences("pad", MODE_PRIVATE)
        val rom = appliedRom
        val key = layoutKey(rom)
        val cur = prefs.getString(key, "auto")
        val checked = Controls.PRESETS.indexOfFirst { it.first == cur }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Layout — " + (rom ?: "default") +
                "\n(BT gamepads paired to the phone just work)")
            .setSingleChoiceItems(Controls.PRESETS.map { it.second }.toTypedArray(), checked) { d, which ->
                prefs.edit().putString(key, Controls.PRESETS[which].first).apply()
                pad.profile = effectiveProfile(rom)
                Toast.makeText(this, Controls.PRESETS[which].second, Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private var lastGame: String? = null      // last raw GAME line, for dedupe
    private var appliedRom: String? = null    // romname whose layout is on the pad now
    private var padInit = false               // has any profile been applied yet

    override fun onResume() {
        super.onResume()
        link.startDiscovery()
        ui.postDelayed(gamePoll, 2000)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(gamePoll)
        link.stopDiscovery()
        link.sendPad(0)   // never leave a button latched on the glasses
        link.disconnect()
    }

    // ------------------------------------------------------------ link

    @Volatile private var restarting = false
    private val clearRestarting = Runnable { restarting = false; pad.statusText = "searching for glasses…" }

    override fun onLinkState(connected: Boolean, host: String?) {
        runOnUiThread {
            pad.statusText = when {
                connected -> { restarting = false; ui.removeCallbacks(clearRestarting); "glasses: $host" }
                restarting -> "glasses reloading games — reconnecting…"
                else -> "searching for glasses… (⚙ for manual IP)"
            }
            if (!connected) link.startDiscovery()   // keep-trying loop (idempotent)
        }
    }

    override fun onRestarting() {
        // the glasses are doing a clean restart to rebuild the game list; the
        // link will drop for a few seconds and the reconnect loop will rejoin
        restarting = true
        runOnUiThread {
            pad.statusText = "glasses restarting to load games…"
            Toast.makeText(this, "Loading games on glasses — reconnecting…", Toast.LENGTH_SHORT).show()
            ui.removeCallbacks(clearRestarting)
            ui.postDelayed(clearRestarting, 30000)   // don't imply a reload forever
        }
    }

    override fun onGame(game: String) {
        if (game == lastGame) return
        lastGame = game
        val g = game.trim()
        val prefs = getSharedPreferences("pad", MODE_PRIVATE)
        // Which game's layout SHOULD be shown. The glasses report the real
        // romname when the core exposes it, but intermittently only "(running)"
        // (core hasn't published the name yet). That must NOT wipe the proper
        // per-game panel back to the generic pad — the bug where the layout kept
        // "forgetting" the game. So we remember the running rom (in memory + in
        // prefs, so it survives a reconnect) and hold it through "(running)".
        val target: String? = when {
            g.isEmpty()      -> { prefs.edit().remove("lastRom").apply(); null }       // back at the frontend
            g == "(running)" -> appliedRom ?: prefs.getString("lastRom", null)         // keep the known layout
            else             -> { prefs.edit().putString("lastRom", g).apply(); g }    // a real romname
        }
        runOnUiThread {
            pad.gameText = when {
                g.isEmpty()      -> "no game loaded"
                g == "(running)" -> target ?: "playing…"
                else             -> g
            }
            // only rebuild the pad when the effective game truly changes; don't
            // reload (and reset) the same layout on every 2-second poll
            if (!padInit || target != appliedRom) {
                padInit = true
                appliedRom = target
                pad.profile = effectiveProfile(target)
            }
        }
    }

    override fun onRomResult(ok: Boolean, msg: String) {
        runOnUiThread {
            Toast.makeText(this, if (ok) "ROM stored: $msg" else "ROM failed: $msg", Toast.LENGTH_LONG).show()
        }
    }

    override fun onServerMsg(msg: String) {
        // native notifications from the glasses (netplay errors, version
        // mismatch, disconnects) mirrored to the player's hand
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    override fun onNav(mode: String) {
        runOnUiThread { pad.navMode = mode }
    }

    private val prefCallbacks = HashMap<String, (String) -> Unit>()
    override fun onPref(key: String, value: String) {
        runOnUiThread { prefCallbacks[key]?.invoke(value) }
    }

    override fun onNpAddr(addr: String) {
        val cb = npAddrWaiter ?: return
        if (addr.isNotEmpty()) { npAddrWaiter = null; runOnUiThread { cb(addr) } }
    }

    @Volatile private var npAddrWaiter: ((String) -> Unit)? = null

    // ------------------------------------------------------------ actions

    private fun actionsDialog() {
        val hap = pad.haptics
        val items = arrayOf(
            "Settings…",
            "Controller & layout…",
            "Game settings (DIPs, inputs) on glasses",
            "NetPlay: host — get invite code",
            "NetPlay: join — enter invite code",
            "Send ROM from phone…",
            "Send ROM from NAS (SMB)…",
            "Reload game list on glasses",
            "Haptic feedback: ${if (hap) "ON" else "OFF"}",
            "Enter glasses IP…",
            "Reconnect")
        AlertDialog.Builder(this)
            .setTitle("TapMame Pad")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> SettingsUi.show(this, link, prefCallbacks)
                    1 -> controllerDialog()
                    2 -> link.openGameSettings()
                    3 -> netHostFlow()
                    4 -> netJoinFlow()
                    5 -> pickRom()
                    6 -> nasConnectDialog()
                    7 -> {
                        // after uploading a ROM, this restarts the glasses app so
                        // MAME re-audits its rompath and the new game shows up
                        link.reloadGames()
                        Toast.makeText(this, "Reloading game list on glasses…", Toast.LENGTH_SHORT).show()
                    }
                    8 -> {
                        pad.haptics = !hap
                        getSharedPreferences("pad", MODE_PRIVATE).edit().putBoolean("haptics", pad.haptics).apply()
                        Toast.makeText(this, "Haptics ${if (pad.haptics) "on" else "off"}", Toast.LENGTH_SHORT).show()
                    }
                    9 -> ipDialog()
                    10 -> { link.disconnect(); link.startDiscovery() }
                }
            }.show()
    }

    // ------------------------------------------------------------ netplay

    private fun netHostFlow() {
        link.netHost()
        Toast.makeText(this, "Hosting… waiting for the public address", Toast.LENGTH_SHORT).show()
        // poll the glasses until STUN/UPnP produced an address (max ~15s)
        var polls = 0
        npAddrWaiter = { addr -> showInvite(addr) }
        val poll = object : Runnable {
            override fun run() {
                if (npAddrWaiter == null) return
                link.queryNpAddr()
                if (++polls < 15) ui.postDelayed(this, 1000)
                else { npAddrWaiter = null
                    Toast.makeText(this@MainActivity, "No public address yet — open NetPlay on the glasses to see connection state", Toast.LENGTH_LONG).show() }
            }
        }
        ui.postDelayed(poll, 1500)
    }

    private fun showInvite(addr: String) {
        val first = addr.split("|").firstOrNull { it.contains(".") } ?: addr
        val code = InviteCode.encode(first)
        val shown = if (code != null) "Invite code:\n\n$code" else "Share this address:\n\n$addr"
        AlertDialog.Builder(this)
            .setTitle("NetPlay — Host")
            .setMessage("$shown\n\nThe other player: TapMame Pad → NetPlay: join → enter this. Both sides need their own copy of the same ROM. Load the game on your glasses; it auto-starts on both when they connect.")
            .setPositiveButton("Share…") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "TapMame NetPlay invite: ${code ?: addr}")
                }
                startActivity(Intent.createChooser(send, "Share invite"))
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun netJoinFlow() {
        val et = EditText(this)
        et.hint = "XXXX-XXXX-XX  (or host address)"
        AlertDialog.Builder(this)
            .setTitle("NetPlay — Join")
            .setView(et)
            .setPositiveButton("Join") { _, _ ->
                val addr = InviteCode.decode(et.text.toString())
                if (addr == null) Toast.makeText(this, "That code doesn't parse", Toast.LENGTH_LONG).show()
                else { link.netJoin(addr); Toast.makeText(this, "Joining $addr…", Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------ NAS

    private fun prefs() = getSharedPreferences("nas", MODE_PRIVATE)

    private fun nasConnectDialog() {
        val wrap = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val url = EditText(this).apply { hint = "smb://192.168.1.50/roms/"; setText(prefs().getString("url", "")) }
        val user = EditText(this).apply { hint = "user (empty = guest)"; setText(prefs().getString("user", "")) }
        val pass = EditText(this).apply { hint = "password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(prefs().getString("pass", "")) }
        wrap.addView(url); wrap.addView(user); wrap.addView(pass)
        AlertDialog.Builder(this)
            .setTitle("NAS (SMB) ROM browser")
            .setView(wrap)
            .setPositiveButton("Browse") { _, _ ->
                prefs().edit().putString("url", url.text.toString().trim())
                    .putString("user", user.text.toString()).putString("pass", pass.text.toString()).apply()
                nasBrowse(url.text.toString().trim())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun nasBrowse(url: String) {
        val user = prefs().getString("user", "") ?: ""
        val pass = prefs().getString("pass", "") ?: ""
        Toast.makeText(this, "Listing…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val entries = Nas.list(url, user, pass)
                runOnUiThread {
                    val labels = ArrayList<String>()
                    labels.add("⬆ up")
                    entries.forEach { labels.add(if (it.isDir) "📁 ${it.name}" else "🕹 ${it.name} (${it.size / 1024} KB)") }
                    AlertDialog.Builder(this)
                        .setTitle(url.removePrefix("smb://"))
                        .setItems(labels.toTypedArray()) { _, which ->
                            if (which == 0) {
                                val up = url.trimEnd('/').substringBeforeLast('/', "smb:/")
                                if (up.length > "smb://".length) nasBrowse("$up/") else nasConnectDialog()
                            } else {
                                val e = entries[which - 1]
                                val next = url.trimEnd('/') + "/" + e.name + if (e.isDir) "/" else ""
                                if (e.isDir) nasBrowse(next) else nasSend(next, e.name)
                            }
                        }
                        .setNegativeButton("Close", null)
                        .show()
                }
            } catch (ex: Exception) {
                runOnUiThread { Toast.makeText(this, "NAS: ${ex.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun nasSend(url: String, name: String) {
        if (!name.lowercase().endsWith(".zip") && !name.lowercase().endsWith(".7z") && !name.lowercase().endsWith(".chd")) {
            Toast.makeText(this, "Not a romset file (.zip/.7z/.chd)", Toast.LENGTH_LONG).show()
            return
        }
        val user = prefs().getString("user", "") ?: ""
        val pass = prefs().getString("pass", "") ?: ""
        Thread {
            try {
                val (stream, size) = Nas.open(url, user, pass)
                runOnUiThread { Toast.makeText(this, "Sending $name ($size bytes)…", Toast.LENGTH_SHORT).show() }
                link.sendRom(name, size, stream)
            } catch (ex: Exception) {
                runOnUiThread { Toast.makeText(this, "NAS read: ${ex.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun ipDialog() {
        val et = EditText(this)
        et.hint = "192.168.1.205"
        AlertDialog.Builder(this)
            .setTitle("Glasses IP")
            .setView(et)
            .setPositiveButton("Connect") { _, _ ->
                val ip = et.text.toString().trim()
                if (ip.isNotEmpty()) link.connect(ip)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickRom() {
        pendingRomPick = true
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, 41)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41 || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        var name = "rom.zip"; var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                val ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = cur.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = cur.getString(ni)
                if (si >= 0) size = cur.getLong(si)
            }
        }
        if (size <= 0) { Toast.makeText(this, "Cannot read file size", Toast.LENGTH_LONG).show(); return }
        val stream = contentResolver.openInputStream(uri)
        if (stream == null) { Toast.makeText(this, "Cannot open file", Toast.LENGTH_LONG).show(); return }
        Toast.makeText(this, "Sending $name ($size bytes)…", Toast.LENGTH_SHORT).show()
        link.sendRom(name, size, stream)
    }
}
