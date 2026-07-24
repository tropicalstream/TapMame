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
 * The whole controller as one multi-touch canvas: 8-way stick on the left,
 * six fire buttons on the right, service row (COIN/START/MENU/EXIT) along
 * the top. Every touch change recomputes the IController bitmask and, when
 * it differs from the last one sent, hands it to [onMask].
 *
 * Bit values mirror MAME4droid's IController.
 */
class PadView(
    context: Context,
    private val onMask: (Long) -> Unit,
    private val onMenu: () -> Unit
) : View(context) {

    companion object {
        const val UP = 0x1L; const val LEFT = 0x4L; const val DOWN = 0x10L; const val RIGHT = 0x40L
        const val START = 1L shl 8; const val COIN = 1L shl 9
        val BTN = longArrayOf(1L shl 10, 1L shl 11, 1L shl 12, 1L shl 13, 1L shl 14, 1L shl 15)
        const val EXIT = 1L shl 20; const val OPTION = 1L shl 21
        val BTN_NAMES = arrayOf("A", "B", "C", "D", "E", "F")
        val BTN_COLORS = intArrayOf(0xFFE53935.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
            0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFFB8C00.toInt())
    }

    /** How many fire buttons the current game exposes (auto layout). */
    var buttonCount = 2
        set(v) { field = v.coerceIn(1, 6); invalidate() }

    var statusText = "searching for glasses…"
        set(v) { field = v; postInvalidate() }
    var gameText = ""
        set(v) { field = v; postInvalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private var lastMask = 0L
    private var stickPointer = -1
    private var stickMask = 0L
    private val heldButtons = HashMap<Int, Long>()   // pointerId -> bit

    // service buttons: label -> momentary bit
    private data class Svc(val label: String, val bit: Long, val rect: RectF = RectF())
    private val svc = arrayOf(Svc("COIN", COIN), Svc("START", START), Svc("MENU", OPTION), Svc("EXIT", EXIT))

    private val btnRects = Array(6) { RectF() }
    private val gearRect = RectF()
    private var stickCx = 0f; private var stickCy = 0f; private var stickR = 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val svcH = h * 0.14f
        val svcW = w * 0.16f
        for ((i, s) in svc.withIndex()) {
            val x = w * 0.5f + (i - 2) * (svcW + 12f) + 6f
            s.rect.set(x, 10f, x + svcW, svcH)
        }
        gearRect.set(14f, 10f, 14f + svcH * 1.2f, svcH)
        stickCx = w * 0.22f; stickCy = h * 0.58f; stickR = minOf(w, h) * 0.30f
        layoutButtons(w, h)
    }

    private fun layoutButtons(w: Int, h: Int) {
        val r = minOf(w, h) * 0.105f
        val cx = w * 0.76f; val cy = h * 0.56f
        // two arcs of three, angled like an arcade panel
        for (i in 0 until 6) {
            val row = i / 3; val col = i % 3
            val x = cx + (col - 1) * r * 2.5f + row * r * 0.9f
            val y = cy + (row * 2 - 1) * r * 1.35f - col * r * 0.45f
            btnRects[i].set(x - r, y - r, x + r, y + r)
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF101418.toInt())
        // status strip
        text.textSize = height * 0.045f; text.color = 0xFF9AA4AE.toInt()
        c.drawText(statusText + if (gameText.isNotEmpty()) "  ·  $gameText" else "", width / 2f, height * 0.99f, text)
        // menu gear
        fill.color = 0xFF232B33.toInt()
        c.drawRoundRect(gearRect, 14f, 14f, fill)
        stroke.color = 0xFF3A454F.toInt()
        c.drawRoundRect(gearRect, 14f, 14f, stroke)
        text.textSize = gearRect.height() * 0.52f; text.color = Color.WHITE
        c.drawText("⚙", gearRect.centerX(), gearRect.centerY() + text.textSize * 0.35f, text)
        // service row
        for (s in svc) {
            fill.color = 0xFF232B33.toInt()
            c.drawRoundRect(s.rect, 14f, 14f, fill)
            stroke.color = 0xFF3A454F.toInt()
            c.drawRoundRect(s.rect, 14f, 14f, stroke)
            text.textSize = s.rect.height() * 0.42f; text.color = Color.WHITE
            c.drawText(s.label, s.rect.centerX(), s.rect.centerY() + text.textSize * 0.35f, text)
        }
        // stick base
        fill.color = 0xFF1B2229.toInt()
        c.drawCircle(stickCx, stickCy, stickR, fill)
        stroke.color = 0xFF39434D.toInt()
        c.drawCircle(stickCx, stickCy, stickR, stroke)
        // stick knob at direction
        var kx = stickCx; var ky = stickCy
        if (stickMask and UP != 0L) ky -= stickR * 0.45f
        if (stickMask and DOWN != 0L) ky += stickR * 0.45f
        if (stickMask and LEFT != 0L) kx -= stickR * 0.45f
        if (stickMask and RIGHT != 0L) kx += stickR * 0.45f
        fill.color = if (stickMask != 0L) 0xFFE53935.toInt() else 0xFF773232.toInt()
        c.drawCircle(kx, ky, stickR * 0.42f, fill)
        // buttons
        for (i in 0 until buttonCount) {
            val rct = btnRects[i]
            val held = heldButtons.containsValue(BTN[i])
            fill.color = if (held) BTN_COLORS[i] else (BTN_COLORS[i] and 0x00FFFFFF) or 0x66000000
            c.drawOval(rct, fill)
            stroke.color = 0x66FFFFFF
            c.drawOval(rct, stroke)
            text.textSize = rct.height() * 0.38f; text.color = Color.WHITE
            c.drawText(BTN_NAMES[i], rct.centerX(), rct.centerY() + text.textSize * 0.35f, text)
        }
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
                for (i in 0 until e.pointerCount)
                    if (e.getPointerId(i) == stickPointer) updateStick(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> { stickPointer = -1; stickMask = 0; heldButtons.clear(); push() }
        }
        return true
    }

    private fun down(pid: Int, x: Float, y: Float) {
        if (gearRect.contains(x, y)) { onMenu(); return }
        for (s in svc) if (s.rect.contains(x, y)) { heldButtons[pid] = s.bit; push(); return }
        for (i in 0 until buttonCount) if (btnRects[i].contains(x, y)) { heldButtons[pid] = BTN[i]; push(); return }
        if (hypot(x - stickCx, y - stickCy) <= stickR * 1.35f) {
            stickPointer = pid
            updateStick(x, y)
        }
    }

    private fun up(pid: Int) {
        if (pid == stickPointer) { stickPointer = -1; stickMask = 0 }
        heldButtons.remove(pid)
        push()
    }

    private fun updateStick(x: Float, y: Float) {
        val dx = x - stickCx; val dy = y - stickCy
        stickMask = if (hypot(dx, dy) < stickR * 0.18f) 0L else {
            // 8-way from angle, 45° sectors
            val deg = Math.toDegrees(atan2(-dy, dx).toDouble())
            val sector = (((deg + 382.5) % 360) / 45).toInt()  // 0=E,1=NE,...
            when (sector) {
                0 -> RIGHT; 1 -> RIGHT or UP; 2 -> UP; 3 -> UP or LEFT
                4 -> LEFT; 5 -> LEFT or DOWN; 6 -> DOWN; else -> DOWN or RIGHT
            }
        }
        push()
    }

    private fun push() {
        var m = stickMask
        for (b in heldButtons.values) m = m or b
        if (m != lastMask) {
            lastMask = m
            onMask(m)
            invalidate()
        }
    }
}
