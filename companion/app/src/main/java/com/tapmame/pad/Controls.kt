package com.tapmame.pad

import android.content.Context
import org.json.JSONObject

/**
 * Per-game controller profile, looked up by romname from assets/controls.json.
 * The layout the pad draws is derived entirely from this: how the stick
 * behaves, which fire buttons exist (and their arcade labels), and any analog
 * surface the cabinet had (dial/paddle/wheel/trackball) — so "non-standard"
 * cabinets map to something intuitive on glass instead of a generic gamepad.
 */
data class ControlProfile(
    val players: Int,
    val stick: String,          // none | 2way | 2way_v | 4way | 8way | dual
    val buttons: List<String>,
    val analog: String,         // none | paddle | dial | wheel | trackball | pedal_shift
    val note: String
)

object Controls {

    /**
     * Layout presets the player can force for a game (Controller menu) when a
     * better fit exists than the auto profile — id, label, profile (null =
     * auto). The touchpad presets swap the stick for a full analog surface,
     * which gives much finer control in paddle/dial games like Circus.
     */
    val PRESETS: List<Triple<String, String, ControlProfile?>> = listOf(
        Triple("auto", "Auto — this game's own layout", null),
        Triple("8way2", "8-way stick + 2 buttons", ControlProfile(2, "8way", listOf("A", "B"), "none", "forced layout")),
        Triple("8way4", "8-way stick + 4 buttons", ControlProfile(2, "8way", listOf("A", "B", "C", "D"), "none", "forced layout")),
        Triple("6btn", "6 buttons (fighting)", ControlProfile(2, "8way", listOf("LP", "MP", "HP", "LK", "MK", "HK"), "none", "forced layout")),
        Triple("4way1", "4-way stick + 1 button (maze)", ControlProfile(2, "4way", listOf("A"), "none", "forced layout")),
        Triple("2way2", "2-way stick + 2 buttons", ControlProfile(2, "2way", listOf("A", "B"), "none", "forced layout")),
        Triple("paddle", "Touchpad paddle — fine analog control", ControlProfile(2, "none", listOf("A", "B"), "paddle", "slide on the pad for fine control")),
        Triple("trackball", "Touchpad trackball", ControlProfile(2, "none", listOf("A", "B"), "trackball", "slide on the pad for fine control")),
    )

    fun preset(id: String?): ControlProfile? = PRESETS.firstOrNull { it.first == id }?.third

    private var db: JSONObject? = null
    private lateinit var fallback: ControlProfile

    fun init(ctx: Context) {
        if (db != null) return
        val json = ctx.assets.open("controls.json").bufferedReader().use { it.readText() }
        db = JSONObject(json)
        fallback = parse(db!!.getJSONObject("_default"))
    }

    /** Profile for a romname; strips clone suffixes toward the parent, else default. */
    fun forGame(name: String?): ControlProfile {
        val d = db ?: return ControlProfile(2, "8way", listOf("A", "B"), "none", "")
        if (name.isNullOrEmpty()) return fallback
        d.optJSONObject(name)?.let { return parse(it) }
        // clones usually share the parent's panel: try trimming trailing digits
        val base = name.trimEnd('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
        if (base != name) d.optJSONObject(base)?.let { return parse(it) }
        return fallback
    }

    private fun parse(o: JSONObject): ControlProfile {
        val btns = ArrayList<String>()
        o.optJSONArray("buttons")?.let { for (i in 0 until it.length()) btns.add(it.getString(i)) }
        return ControlProfile(
            players = o.optInt("players", 2),
            stick = o.optString("stick", "8way"),
            buttons = btns,
            analog = o.optString("analog", "none"),
            note = o.optString("note", "")
        )
    }
}
