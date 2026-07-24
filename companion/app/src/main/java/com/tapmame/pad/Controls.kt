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
