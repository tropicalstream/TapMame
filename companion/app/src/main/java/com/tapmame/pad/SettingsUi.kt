package com.tapmame.pad

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/**
 * The MAME / TapMame settings, presented natively ON THE PHONE. The glasses
 * store these as plain SharedPreferences; this screen reads the live values
 * with GETPREF and writes changes with SETPREF, so nothing settings-related
 * has to be shown (uncomfortably, in one eye) on the glasses.
 *
 * Only the options that actually matter on the glasses are surfaced — the
 * on-screen touch-controller half of MAME4droid's settings is irrelevant
 * there. Each row is bound to its preference key by a callback the host
 * registers, so incoming PREF values update the controls live.
 */
object SettingsUi {

    private sealed class Opt(val key: String, val label: String)
    private class BoolOpt(key: String, label: String, val def: Boolean) : Opt(key, label)
    private class ListOpt(key: String, label: String, val labels: List<String>,
                          val values: List<String>, val def: String) : Opt(key, label)
    private class PortOpt(key: String, label: String, val def: String) : Opt(key, label)

    private val SOUND_L = listOf("Off", "11 KHz", "22 KHz", "32 KHz", "44 KHz", "48 KHz")
    private val SOUND_V = listOf("-1", "11025", "22050", "32000", "44100", "48000")
    private val RES_L = listOf("Auto (best speed)", "640×480 (balanced)", "Device native (artwork)")
    private val RES_V = listOf("0", "1", "10")
    private val DELAY_L = listOf("Auto", "1", "2", "3", "4", "5", "6")
    private val DELAY_V = listOf("0", "1", "2", "3", "4", "5", "6")
    private val PROTO_L = listOf("IPv4", "IPv6", "Auto (IPv6 + IPv4)")
    private val PROTO_V = listOf("0", "1", "2")

    private val DZ_L = listOf("Smallest", "Small", "Normal", "Large", "Largest")
    private val DZ_V = listOf("1", "2", "3", "4", "5")
    private val ADZ_L = listOf("Off", "Smallest", "Small", "Normal", "Large")
    private val ADZ_V = listOf("0", "1", "2", "3", "4")
    private val CPU_L = listOf("Auto", "1", "2", "3", "4")
    private val CPU_V = listOf("-1", "1", "2", "3", "4")

    private val SECTIONS: List<Pair<String, List<Opt>>> = listOf(
        "Emulation" to listOf(
            ListOpt("PREF_EMU_SOUND", "Sound", SOUND_L, SOUND_V, "44100"),
            ListOpt("PREF_EMU_RESOLUTION_3", "Emulated resolution", RES_L, RES_V, "1"),
            BoolOpt("PREF_EMU_SHOW_FPS", "Show FPS", false),
            BoolOpt("PREF_EMU_AUTO_FRAMESKIP", "Auto frameskip", false),
            BoolOpt("PREF_FRAME_PACING", "Frame pacing", true),
            BoolOpt("PREF_GLOBAL_AUTOSAVE", "Auto-save & resume games", false),
            BoolOpt("CHEATS", "Cheats", false),
            BoolOpt("PREF_HISCORE", "Save high scores", false),
            BoolOpt("SKIP_GAMEINFO", "Skip game-info screen", false)
        ),
        "Video" to listOf(
            BoolOpt("PREF_BITMAP_FILTERING", "Smooth scaling (bilinear)", true),
            BoolOpt("PREF_ZOOM_TO_WINDOW", "Zoom to window", true),
            BoolOpt("PREF_TAPMAME_SBS", "SBS 3D output (both eyes)", true)
        ),
        "Performance" to listOf(
            BoolOpt("PREF_SPEED_HACKS_2", "Speed hacks", false),
            BoolOpt("PREF_EMU_DISABLE_DRC_4", "Disable DRC recompiler (compatibility)", false),
            BoolOpt("PREF_EMU_DRC_USE_C_4", "DRC C backend (safer, slower)", false),
            ListOpt("PREF_EMU_NUM_PROCESSORS", "CPU threads", CPU_L, CPU_V, "-1")
        ),
        "Input" to listOf(
            BoolOpt("PREF_AUTOFIRE", "Autofire", false),
            ListOpt("PREF_GAMEPAD_DZ", "Gamepad deadzone", DZ_L, DZ_V, "3"),
            ListOpt("PREF_ANALOG_DZ", "Analog deadzone", ADZ_L, ADZ_V, "2")
        ),
        "Vector graphics" to listOf(
            BoolOpt("PREF_VECTOR_IMPROVED", "Improved vector glow", true),
            BoolOpt("PREF_BEAM2X_2", "Wider beam (2×)", false),
            BoolOpt("PREF_FLICKER", "Beam flicker", false)
        ),
        "NetPlay" to listOf(
            PortOpt("PREF_NETPLAY_PORT", "Port", "2080"),
            BoolOpt("netplay_rollback_mode", "Rollback mode (off = lockstep)", false),
            ListOpt("PREF_NETPLAY_DELAY", "Input delay (frames)", DELAY_L, DELAY_V, "0"),
            ListOpt("PREF_NETPLAY_IP_PROTOCOL", "Network protocol", PROTO_L, PROTO_V, "0"),
            BoolOpt("PREF_NETPLAY_UPNP", "UPnP port mapping", true),
            BoolOpt("PREF_NETPLAY_DESYNC_DETECTOR_ENABLED", "Desync detector", true),
            BoolOpt("PREF_NETPLAY_STATS_ENABLED", "Connection stats overlay", false)
        ),
        "Scraping" to listOf(
            BoolOpt("PREF_SCRAPE_ENABLED", "Enable media scraping", false),
            BoolOpt("PREF_SCRAPE_ICONS", "Scrape icons", true),
            BoolOpt("PREF_SCRAPE_SNAPSHOTS", "Scrape snapshots", true)
        ),
        "General" to listOf(
            BoolOpt("PREF_GLOBAL_WARN_ON_EXIT", "Warn on exit", true),
            BoolOpt("PREF_FORCE_UNIFONT", "Force Unicode font", false)
        )
    )

    /** value strings the glasses store for a checked box */
    private fun boolOf(v: String, def: Boolean) =
        if (v.isEmpty()) def else v.equals("true", true) || v == "1"

    fun show(ctx: Context, link: LinkClient, callbacks: MutableMap<String, (String) -> Unit>) {
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        // One gate to rule them all: Android fires change/selection/focus
        // callbacks while we're loading the current values into the controls,
        // and any of those would echo a bogus SETPREF back. Suppress every
        // write until the initial load has settled.
        var loading = true
        fun write(key: String, type: String, value: String) { if (!loading) link.setPref(key, type, value) }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(12), px(20), px(20))
        }

        for ((title, opts) in SECTIONS) {
            root.addView(TextView(ctx).apply {
                text = title.uppercase()
                setTextColor(0xFFE8B33C.toInt())
                textSize = 13f
                setPadding(0, px(16), 0, px(6))
            })
            for (o in opts) {
                fun label() = TextView(ctx).apply {
                    text = o.label
                    setTextColor(Color.WHITE)
                    textSize = 15f
                }
                when (o) {
                    is BoolOpt -> {
                        // toggle: label left, switch right — reads well in a row
                        val row = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, px(10), 0, px(10))
                        }
                        row.addView(label().apply {
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        val sw = Switch(ctx)
                        // setChecked fires the listener synchronously, so a
                        // suppress flag keeps the load from echoing a SETPREF
                        var suppress = false
                        callbacks[o.key] = { v -> suppress = true; sw.isChecked = boolOf(v, o.def); suppress = false }
                        sw.setOnCheckedChangeListener { _, c ->
                            if (!suppress) write(o.key, "bool", if (c) "true" else "false")
                        }
                        row.addView(sw)
                        root.addView(row)
                    }
                    is ListOpt -> {
                        // dropdown: label on its own line, the pull-down full-width
                        // beneath it — the professional stacked look
                        val col = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(0, px(10), 0, px(6))
                        }
                        col.addView(label())
                        val sp = Spinner(ctx).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        }
                        sp.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, o.labels)
                        // a Spinner fires onItemSelected for its initial position
                        // AND for setSelection (async), so value-compares can't
                        // reliably tell load from a real choice. Only treat a
                        // selection as the user's if they physically touched it.
                        var userTouched = false
                        callbacks[o.key] = { v ->
                            val cur = if (v.isEmpty()) o.def else v
                            sp.setSelection(o.values.indexOf(cur).coerceAtLeast(0))
                        }
                        sp.setOnTouchListener { view, _ -> userTouched = true; view.performClick(); false }
                        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                            override fun onItemSelected(p: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                                if (userTouched) { userTouched = false; write(o.key, "string", o.values[pos]) }
                            }
                            override fun onNothingSelected(p: AdapterView<*>?) {}
                        }
                        col.addView(sp)
                        root.addView(col)
                    }
                    is PortOpt -> {
                        // number field, also stacked under its label
                        val col = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(0, px(10), 0, px(6))
                        }
                        col.addView(label())
                        val et = EditText(ctx).apply {
                            inputType = InputType.TYPE_CLASS_NUMBER
                            layoutParams = LinearLayout.LayoutParams(px(130), ViewGroup.LayoutParams.WRAP_CONTENT)
                            setText(o.def)
                        }
                        var loaded = o.def
                        callbacks[o.key] = { v -> loaded = if (v.isEmpty()) o.def else v; et.setText(loaded) }
                        et.setOnFocusChangeListener { _, has ->
                            val t = et.text.toString().trim()
                            // only write on a real edit, never on programmatic focus shuffle
                            if (!has && t.isNotEmpty() && t != loaded) { loaded = t; write(o.key, "string", t) }
                        }
                        col.addView(et)
                        root.addView(col)
                    }
                }
            }
        }

        val scroll = ScrollView(ctx).apply { addView(root) }

        // ask the glasses for the current value of everything
        for ((_, opts) in SECTIONS) for (o in opts) link.getPref(o.key)
        // open the write-gate once the load echoes have flushed
        scroll.postDelayed({ loading = false }, 1200)

        AlertDialog.Builder(ctx)
            .setTitle("TapMame Settings")
            .setView(scroll)
            .setOnDismissListener { callbacks.clear() }
            .setPositiveButton("Done", null)
            .show()
    }
}
