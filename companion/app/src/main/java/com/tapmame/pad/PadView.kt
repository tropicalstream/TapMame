package com.tapmame.pad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * A controller that reshapes itself to the running game's profile
 * ([ControlProfile]): the movement stick honours the cabinet's real gate
 * (2-way, 4-way, 8-way, twin, or none), the fire buttons carry the game's own
 * labels, and cabinets with a paddle / spinner / wheel / trackball get an
 * analog surface instead of a stick. Every change to the pressed state
 * recomputes a digital [IController] bitmask handed to [onMask]; analog
 * surfaces call [onAxis] with a MAME analog-type + value.
 */
class PadView(
    context: Context,
    private val onMask: (Long) -> Unit,
    private val onAxis: (type: Int, x: Float, y: Float) -> Unit,
    private val onMenu: () -> Unit,
    private val onAction: (String) -> Unit   // "EXIT" | "MENU" service taps
) : View(context) {

    companion object {
        const val UP = 0x1L; const val LEFT = 0x4L; const val DOWN = 0x10L; const val RIGHT = 0x40L
        const val START = 1L shl 8; const val COIN = 1L shl 9
        val BTN = longArrayOf(1L shl 10, 1L shl 11, 1L shl 12, 1L shl 13, 1L shl 14, 1L shl 15)
        const val EXIT = 1L shl 20; const val OPTION = 1L shl 21
        val BTN_COLORS = intArrayOf(0xFFE53935.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
            0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFFB8C00.toInt())
        const val LEFT_STICK_DATA = 1   // Emulator.LEFT_STICK_DATA
    }

    var profile: ControlProfile = ControlProfile(2, "8way", listOf("A", "B", "C", "D", "E", "F"), "none", "")
        set(v) { field = v; if (width > 0) relayout(); postInvalidate() }

    var statusText = "searching for glasses…"
        set(v) { field = v; postInvalidate() }
    var gameText = ""
        set(v) { field = v; postInvalidate() }
    /** A transient notice (ROM stored, upload failed). Drawn in the pad itself
     *  rather than as a system toast: toasts QUEUE, so a couple of uploads left
     *  several stacked up playing one after another and the last one sat on
     *  screen long after it stopped being true. Setting this replaces whatever
     *  was there, and the host clears it on a timer. */
    var noticeText = ""
        set(v) { field = v; postInvalidate() }
    /** Vibrate on control presses (toggle in the ⚙ menu). */
    var haptics = true
    /** What the pad currently drives on the glasses: game|frontend|android|menu. */
    var navMode = "frontend"
        set(v) { if (v != field) { field = v; postInvalidate() } }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private var lastMask = 0L

    // stick
    private var hasStick = false
    private var stickCx = 0f; private var stickCy = 0f; private var stickR = 0f
    private var stickPointer = -1
    private var stickMask = 0L

    // analog surface (paddle/dial/wheel/trackball)
    private var hasAnalog = false
    private val analogRect = RectF()
    private var analogPointer = -1
    private var analogValX = 0f; private var analogValY = 0f
    private var analogTrackball = false

    // fire buttons
    private var btnCount = 0
    private val btnRects = Array(6) { RectF() }
    private var btnLabels = arrayOf<String>()
    private val heldButtons = HashMap<Int, Long>()

    // service + gear. COIN/START are momentary bitmask presses; EXIT and
    // MENU are tap actions (EXIT gets a confirm on the phone, MENU opens
    // the glasses' SBS menu). Order per Mars: COIN START EXIT MENU.
    private data class Svc(val label: String, val bit: Long, val rect: RectF = RectF())
    private val svc = arrayOf(Svc("COIN", COIN), Svc("START", START), Svc("EXIT", 0), Svc("MENU", 0))
    private val gearRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = relayout()

    /**
     * Ergonomic layout (thumb-zone research): in a two-handed grip the
     * thumbs anchor at the BOTTOM corners and sweep comfortable arcs
     * covering roughly the lower two-thirds of each half, so the stick and
     * the fire fan sit low, anchored to their corners. Infrequent,
     * deliberate controls (COIN/START/EXIT/MENU, gear) live along the TOP —
     * the hardest-to-reach strip — so mid-game thumbs never hit them by
     * accident. Both orientations use the same corner-anchor logic.
     */
    private fun relayout() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val portrait = h > w

        // service strip along the top
        val svcH = if (portrait) h * 0.07f else h * 0.14f
        val gearW = svcH * 1.2f
        gearRect.set(14f, 10f, 14f + gearW, svcH)
        val left = gearRect.right + 12f
        val svcW = (w - left - 14f - 3 * 10f) / 4f
        for ((i, s) in svc.withIndex()) {
            val x = left + i * (svcW + 10f)
            s.rect.set(x, 10f, x + svcW, svcH)
        }

        hasStick = profile.stick != "none"
        hasAnalog = profile.analog != "none" && profile.stick != "dual"
        analogTrackball = profile.analog == "trackball"

        // stick: anchored toward the bottom-left corner thumb arc
        stickR = minOf(w, h) * (if (portrait) 0.20f else 0.28f)
        stickCx = stickR + w * 0.04f
        stickCy = h - stickR - h * (if (portrait) 0.05f else 0.08f)

        if (hasAnalog) {
            val aw = if (portrait) w * 0.55f else w * 0.36f
            val ah = if (portrait) h * 0.28f else h * 0.46f
            analogRect.set(w * 0.04f, h - ah - h * 0.08f, w * 0.04f + aw, h - h * 0.08f)
        }

        btnLabels = profile.buttons.take(6).toTypedArray()
        btnCount = btnLabels.size
        layoutButtons(w, h, portrait)
    }

    /**
     * Fire buttons fan along the right thumb's natural arc, anchored just
     * off the bottom-right corner: button 0 (primary) closest to the resting
     * thumb, later buttons stepping outward along the sweep; 4-6 buttons
     * form a second, outer ring.
     */
    private fun layoutButtons(w: Float, h: Float, portrait: Boolean) {
        if (btnCount == 0) return
        val m = minOf(w, h)
        val r = (m * if (portrait) 0.075f else 0.105f).coerceAtLeast(56f)
        val ax = w + r * 0.4f          // anchor: just outside bottom-right corner
        val ay = h + r * 0.4f
        val inner = m * (if (portrait) 0.33f else 0.52f)
        val outer = inner + r * (if (portrait) 2.0f else 2.25f)
        // angles measured from the corner: 0° = along the bottom edge,
        // 90° = straight up; 20°..70° keeps the fan on-screen inside the
        // thumb's sweep. The fan centres on the 45° diagonal (the resting
        // thumb) and D/E/F sit on the SAME spokes outside A/B/C, so six
        // buttons read as the classic two curved arcade rows.
        val n1 = minOf(btnCount, 3)
        val step = if (portrait) 28.0 else 25.0   // keep adjacent buttons clear
        val start = 45.0 + (n1 - 1) * step / 2
        for (i in 0 until n1) {
            val a = Math.toRadians(start - i * step)
            place(i, (ax - inner * kotlin.math.cos(a)).toFloat(),
                     (ay - inner * kotlin.math.sin(a)).toFloat(), r)
        }
        for (i in 3 until btnCount) {
            val a = Math.toRadians(start - (i - 3) * step)
            place(i, (ax - outer * kotlin.math.cos(a)).toFloat(),
                     (ay - outer * kotlin.math.sin(a)).toFloat(), r * 0.92f)
        }
    }

    private fun place(i: Int, cx: Float, cy: Float, r: Float) = btnRects[i].set(cx - r, cy - r, cx + r, cy + r)

    // ------------------------------------------------------------ draw

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF101418.toInt())
        // navigation-mode hint banner: when the pad is driving a menu on the
        // glasses instead of a game, spell out what stick + FIRE do
        val hint = when (navMode) {
            "android" -> "NAVIGATING GLASSES MENU  ·  stick = move   FIRE = select   MENU = back"
            "menu" -> "TAPMAME MENU  ·  stick = move   FIRE = select"
            "frontend" -> "GAME SELECT  ·  stick = browse   FIRE = open game"
            else -> ""
        }
        if (hint.isNotEmpty()) {
            val hy = if (height > width) height * 0.30f else height * 0.28f
            text.textSize = minOf(width, height) * 0.03f; text.color = 0xFF7FA8CC.toInt()
            c.drawText(hint, width / 2f, hy, text)
        }
        if (noticeText.isNotEmpty()) {
            text.textSize = minOf(width, height) * 0.038f
            text.color = 0xFFE8C660.toInt()
            c.drawText(noticeText, width / 2f, height * 0.955f, text)
        }
        text.textSize = minOf(width, height) * 0.032f; text.color = 0xFF9AA4AE.toInt()
        val line = buildString {
            append(statusText)
            if (gameText.isNotEmpty()) append("  ·  ").append(gameText)
            if (profile.note.isNotEmpty()) append("  ·  ").append(profile.note)
        }
        c.drawText(line, width / 2f, height * 0.99f, text)

        // gear
        panel(c, gearRect); text.textSize = gearRect.height() * 0.52f; text.color = Color.WHITE
        c.drawText("⚙", gearRect.centerX(), gearRect.centerY() + text.textSize * 0.35f, text)
        // service row
        for (s in svc) {
            panel(c, s.rect); text.textSize = s.rect.height() * 0.4f; text.color = Color.WHITE
            c.drawText(s.label, s.rect.centerX(), s.rect.centerY() + text.textSize * 0.35f, text)
        }

        if (hasStick) drawStick(c)
        if (hasAnalog) drawAnalog(c)
        for (i in 0 until btnCount) drawButton(c, i)
    }

    private fun panel(c: Canvas, r: RectF) {
        fill.color = 0xFF232B33.toInt(); c.drawRoundRect(r, 14f, 14f, fill)
        stroke.color = 0xFF3A454F.toInt(); c.drawRoundRect(r, 14f, 14f, stroke)
    }

    private fun drawStick(c: Canvas) {
        fill.color = 0xFF1B2229.toInt(); c.drawCircle(stickCx, stickCy, stickR, fill)
        stroke.color = 0xFF39434D.toInt(); c.drawCircle(stickCx, stickCy, stickR, stroke)
        // gate hint: draw the allowed axes
        stroke.color = 0x33FFFFFF
        when (profile.stick) {
            "2way" -> c.drawLine(stickCx - stickR, stickCy, stickCx + stickR, stickCy, stroke)
            "2way_v" -> c.drawLine(stickCx, stickCy - stickR, stickCx, stickCy + stickR, stroke)
            "4way" -> { c.drawLine(stickCx - stickR, stickCy, stickCx + stickR, stickCy, stroke)
                        c.drawLine(stickCx, stickCy - stickR, stickCx, stickCy + stickR, stroke) }
        }
        var kx = stickCx; var ky = stickCy
        if (stickMask and UP != 0L) ky -= stickR * 0.45f
        if (stickMask and DOWN != 0L) ky += stickR * 0.45f
        if (stickMask and LEFT != 0L) kx -= stickR * 0.45f
        if (stickMask and RIGHT != 0L) kx += stickR * 0.45f
        fill.color = if (stickMask != 0L) 0xFFE53935.toInt() else 0xFF773232.toInt()
        c.drawCircle(kx, ky, stickR * 0.42f, fill)
    }

    private fun drawAnalog(c: Canvas) {
        fill.color = 0xFF1B2229.toInt(); c.drawRoundRect(analogRect, 20f, 20f, fill)
        stroke.color = 0xFF39434D.toInt(); c.drawRoundRect(analogRect, 20f, 20f, stroke)
        text.textSize = analogRect.height() * 0.12f; text.color = 0xFF6B7681.toInt()
        c.drawText(profile.analog.uppercase(), analogRect.centerX(), analogRect.top + analogRect.height() * 0.16f, text)
        // knob at current value
        val kx = analogRect.centerX() + analogValX * analogRect.width() * 0.42f
        val ky = if (analogTrackball) analogRect.centerY() + analogValY * analogRect.height() * 0.42f
                 else analogRect.centerY()
        fill.color = 0xFFE53935.toInt()
        c.drawCircle(kx, ky, analogRect.width() * 0.13f, fill)
    }

    private fun drawButton(c: Canvas, i: Int) {
        val rct = btnRects[i]
        val held = heldButtons.containsValue(BTN[i])
        fill.color = if (held) BTN_COLORS[i] else (BTN_COLORS[i] and 0x00FFFFFF) or 0x66000000
        c.drawOval(rct, fill)
        stroke.color = 0x66FFFFFF; c.drawOval(rct, stroke)
        text.textSize = (rct.height() * 0.34f).coerceAtMost(rct.width() * 0.42f); text.color = Color.WHITE
        c.drawText(btnLabels[i], rct.centerX(), rct.centerY() + text.textSize * 0.35f, text)
    }

    // ------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                down(e.getPointerId(idx), e.getX(idx), e.getY(idx))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val pid = e.getPointerId(i)
                    if (pid == stickPointer) updateStick(e.getX(i), e.getY(i))
                    if (pid == analogPointer) updateAnalog(e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> {
                stickPointer = -1; stickMask = 0; analogPointer = -1; heldButtons.clear()
                releaseAnalog(); push()
            }
        }
        return true
    }

    private val pressT = HashMap<Int, Long>()   // pointerId -> press uptime

    private fun down(pid: Int, x: Float, y: Float) {
        if (gearRect.contains(x, y)) { buzz(); onMenu(); return }
        for (s in svc) if (s.rect.contains(x, y)) {
            buzz()
            if (s.bit == 0L) onAction(s.label)          // EXIT (confirmed) / MENU
            else { heldButtons[pid] = s.bit; pressT[pid] = android.os.SystemClock.uptimeMillis(); push() }
            return
        }
        for (i in 0 until btnCount) if (btnRects[i].contains(x, y)) {
            buzz(); heldButtons[pid] = BTN[i]; pressT[pid] = android.os.SystemClock.uptimeMillis(); push(); return
        }
        if (hasAnalog && analogRect.contains(x, y)) { buzz(); analogPointer = pid; updateAnalog(x, y); return }
        if (hasStick && hypot(x - stickCx, y - stickCy) <= stickR * 1.35f) {
            stickPointer = pid; updateStick(x, y)
        }
    }

    private fun buzz() {
        if (haptics) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY,
            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    private fun up(pid: Int) {
        if (pid == stickPointer) { stickPointer = -1; stickMask = 0 }
        if (pid == analogPointer) { analogPointer = -1; releaseAnalog() }
        // MAME samples input once per emulated frame plus network latency, so
        // a very quick tap can slip through unseen (COIN/START felt "deaf").
        // Enforce a minimum press: on an early release keep the bit latched
        // and release it after the remainder.
        val bit = heldButtons[pid]
        val t0 = pressT.remove(pid)
        if (bit != null && t0 != null) {
            val held = android.os.SystemClock.uptimeMillis() - t0
            val minHold = 140L
            if (held < minHold) {
                postDelayed({ heldButtons.remove(pid); push() }, minHold - held)
                return
            }
        }
        heldButtons.remove(pid)
        push()
    }

    private fun updateStick(x: Float, y: Float) {
        val dx = x - stickCx; val dy = y - stickCy
        val m = if (hypot(dx, dy) < stickR * 0.18f) 0L else gate(dx, dy)
        if (m != stickMask && m != 0L) buzz()   // tick on each new direction
        stickMask = m
        push()
    }

    /** Constrain a raw direction to the cabinet's gate. */
    private fun gate(dx: Float, dy: Float): Long {
        return when (profile.stick) {
            "2way" -> if (dx < 0) LEFT else RIGHT
            "2way_v" -> if (dy < 0) UP else DOWN
            "4way" -> if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) (if (dx < 0) LEFT else RIGHT)
                      else (if (dy < 0) UP else DOWN)
            else -> {   // 8way / dual
                val deg = Math.toDegrees(atan2(-dy, dx).toDouble())
                when ((((deg + 382.5) % 360) / 45).toInt()) {
                    0 -> RIGHT; 1 -> RIGHT or UP; 2 -> UP; 3 -> UP or LEFT
                    4 -> LEFT; 5 -> LEFT or DOWN; 6 -> DOWN; else -> DOWN or RIGHT
                }
            }
        }
    }

    private fun updateAnalog(x: Float, y: Float) {
        analogValX = ((x - analogRect.centerX()) / (analogRect.width() * 0.42f)).coerceIn(-1f, 1f)
        analogValY = if (analogTrackball)
            ((y - analogRect.centerY()) / (analogRect.height() * 0.42f)).coerceIn(-1f, 1f) else 0f
        onAxis(LEFT_STICK_DATA, analogValX, -analogValY)
        invalidate()
    }

    private fun releaseAnalog() {
        // spinner/wheel/dial recentre; paddle & trackball hold last reading
        if (profile.analog == "dial" || profile.analog == "wheel") {
            analogValX = 0f; analogValY = 0f
            onAxis(LEFT_STICK_DATA, 0f, 0f)
        }
        invalidate()
    }

    private fun push() {
        var m = stickMask
        for (b in heldButtons.values) m = m or b
        if (m != lastMask) { lastMask = m; onMask(m); invalidate() }
    }
}
