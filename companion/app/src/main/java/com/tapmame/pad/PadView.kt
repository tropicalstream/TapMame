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
    private val onMenu: () -> Unit
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

    // service + gear
    private data class Svc(val label: String, val bit: Long, val rect: RectF = RectF())
    private val svc = arrayOf(Svc("COIN", COIN), Svc("START", START), Svc("MENU", OPTION), Svc("EXIT", EXIT))
    private val gearRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = relayout()

    private fun relayout() {
        val w = width.toFloat(); val h = height.toFloat()
        val svcH = h * 0.14f; val svcW = w * 0.16f
        for ((i, s) in svc.withIndex()) {
            val x = w * 0.5f + (i - 2) * (svcW + 12f) + 6f
            s.rect.set(x, 10f, x + svcW, svcH)
        }
        gearRect.set(14f, 10f, 14f + svcH * 1.2f, svcH)

        hasStick = profile.stick != "none"
        hasAnalog = profile.analog != "none" && profile.stick != "dual"
        analogTrackball = profile.analog == "trackball"

        stickCx = w * 0.22f; stickCy = h * 0.60f; stickR = minOf(w, h) * 0.30f

        if (hasAnalog) {
            // analog surface sits where the stick would be
            val aw = w * 0.36f; val ah = h * 0.5f
            analogRect.set(w * 0.04f, h * 0.32f, w * 0.04f + aw, h * 0.32f + ah)
        }

        btnLabels = profile.buttons.take(6).toTypedArray()
        btnCount = btnLabels.size
        layoutButtons(w, h)
    }

    private fun layoutButtons(w: Float, h: Float) {
        if (btnCount == 0) return
        val r = (minOf(w, h) * 0.11f).coerceAtMost(w * 0.09f)
        val cx = w * 0.76f; val cy = h * 0.56f
        when (btnCount) {
            1 -> place(0, cx, cy, r)
            2 -> { place(0, cx - r * 1.3f, cy + r * 0.4f, r); place(1, cx + r * 1.3f, cy - r * 0.4f, r) }
            3 -> for (i in 0 until 3) place(i, cx + (i - 1) * r * 2.4f, cy - (i - 1) * r * 0.5f, r)
            else -> for (i in 0 until btnCount) {   // two staggered rows
                val row = i / 3; val col = i % 3
                place(i, cx + (col - 1) * r * 2.4f + row * r * 0.8f,
                    cy + (row * 2 - 1) * r * 1.3f - col * r * 0.4f, r)
            }
        }
    }

    private fun place(i: Int, cx: Float, cy: Float, r: Float) = btnRects[i].set(cx - r, cy - r, cx + r, cy + r)

    // ------------------------------------------------------------ draw

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF101418.toInt())
        text.textSize = height * 0.045f; text.color = 0xFF9AA4AE.toInt()
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

    private fun down(pid: Int, x: Float, y: Float) {
        if (gearRect.contains(x, y)) { onMenu(); return }
        for (s in svc) if (s.rect.contains(x, y)) { heldButtons[pid] = s.bit; push(); return }
        for (i in 0 until btnCount) if (btnRects[i].contains(x, y)) { heldButtons[pid] = BTN[i]; push(); return }
        if (hasAnalog && analogRect.contains(x, y)) { analogPointer = pid; updateAnalog(x, y); return }
        if (hasStick && hypot(x - stickCx, y - stickCy) <= stickR * 1.35f) {
            stickPointer = pid; updateStick(x, y)
        }
    }

    private fun up(pid: Int) {
        if (pid == stickPointer) { stickPointer = -1; stickMask = 0 }
        if (pid == analogPointer) { analogPointer = -1; releaseAnalog() }
        heldButtons.remove(pid)
        push()
    }

    private fun updateStick(x: Float, y: Float) {
        val dx = x - stickCx; val dy = y - stickCy
        stickMask = if (hypot(dx, dy) < stickR * 0.18f) 0L else gate(dx, dy)
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
