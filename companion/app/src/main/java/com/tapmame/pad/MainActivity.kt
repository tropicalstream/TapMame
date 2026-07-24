package com.tapmame.pad

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.EditText
import android.widget.Toast

/**
 * TapMame Pad — the phone companion. Discovers the glasses on the LAN,
 * becomes the arcade controller, and pushes ROM files into the glasses'
 * roms folder. Long-press MENU (on the pad) for the actions dialog
 * (send ROM, reconnect, manual IP).
 */
class MainActivity : Activity(), LinkClient.Listener {

    private lateinit var pad: PadView
    private lateinit var link: LinkClient
    private val ui = Handler(Looper.getMainLooper())
    private val gamePoll = object : Runnable {
        override fun run() {
            link.queryGame()
            ui.postDelayed(this, 3000)
        }
    }
    private var pendingRomPick = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        link = LinkClient(this, this)
        pad = PadView(this) { mask ->
            // OPTION long-form: the MENU service button doubles as the
            // actions dialog when tapped together with EXIT — keep simple:
            link.sendPad(mask)
        }
        setContentView(pad)
        pad.setOnLongClickListener { actionsDialog(); true }
        pad.isLongClickable = true
    }

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

    override fun onLinkState(connected: Boolean, host: String?) {
        runOnUiThread {
            pad.statusText = if (connected) "glasses: $host" else "searching for glasses… (long-press for manual IP)"
            if (!connected) link.startDiscovery()
        }
    }

    override fun onGame(game: String) {
        runOnUiThread {
            pad.gameText = if (game.isEmpty()) "no game loaded" else game
            // simple auto-layout heuristic until the listxml controls DB
            // lands: frontend/no game → all six for UI nav; in-game → six
            // too, MAME ignores unused ones. Kept as a field for the DB.
            pad.buttonCount = 6
        }
    }

    override fun onRomResult(ok: Boolean, msg: String) {
        runOnUiThread {
            Toast.makeText(this, if (ok) "ROM stored: $msg" else "ROM failed: $msg", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------ actions

    private fun actionsDialog() {
        val items = arrayOf("Send ROM to glasses…", "Enter glasses IP…", "Reconnect")
        AlertDialog.Builder(this)
            .setTitle("TapMame Pad")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickRom()
                    1 -> ipDialog()
                    2 -> { link.disconnect(); link.startDiscovery() }
                }
            }.show()
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
