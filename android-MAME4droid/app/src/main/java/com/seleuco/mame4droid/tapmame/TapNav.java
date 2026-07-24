/*
 * TapMame navigation state — the glue that lets the companion phone's EXIT
 * and MENU buttons navigate the glasses UI at every level, including the
 * Android sub-activities (Global Settings) that float above the emulator.
 *
 * Part of TapMame, a MAME4droid-Current fork (GPL-2.0, see COPYING).
 *
 * The link server lives inside the emulator activity, so it can't finish a
 * settings activity sitting on top by itself. Each TapMame activity records
 * itself here as the current top activity; the nav helpers act on whatever
 * is actually foreground.
 */
package com.seleuco.mame4droid.tapmame;

import android.app.Activity;

import com.seleuco.mame4droid.Emulator;
import com.seleuco.mame4droid.MAME4droid;

public final class TapNav {

	private TapNav() {}

	/** The TapMame activity currently on top (emulator or a sub-screen). */
	public static volatile Activity top;
	/** A modal dialog (NetPlay, mode picker…) currently shown over top. */
	public static volatile android.app.Dialog activeDialog;

	public static void setTop(Activity a) { top = a; }
	public static void clearTop(Activity a) { if (top == a) top = null; }
	public static void setActiveDialog(android.app.Dialog d) { activeDialog = d; }
	public static void clearActiveDialog(android.app.Dialog d) { if (activeDialog == d) activeDialog = null; }

	private static boolean topIsSubScreen() {
		Activity a = top;
		return a != null && !(a instanceof MAME4droid) && !a.isFinishing();
	}

	/** An Android menu (settings activity or a dialog) is on top — one the
	 *  companion pad should navigate as DPAD/ENTER rather than the game. */
	public static boolean androidNavActive() {
		android.app.Dialog d = activeDialog;
		if (d != null && d.isShowing()) return true;
		return topIsSubScreen();
	}

	/** Inject one Android key (DOWN+UP) into the focused menu window. */
	public static void androidKey(final MAME4droid mm, final int keyCode) {
		final android.app.Dialog d = activeDialog;
		final Activity a = top;
		mm.runOnUiThread(() -> {
			long t = android.os.SystemClock.uptimeMillis();
			android.view.KeyEvent down = new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, keyCode, 0);
			android.view.KeyEvent up = new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, keyCode, 0);
			if (d != null && d.isShowing()) {
				d.dispatchKeyEvent(down); d.dispatchKeyEvent(up);
			} else if (a != null) {
				a.dispatchKeyEvent(down); a.dispatchKeyEvent(up);
			}
		});
	}

	/**
	 * MENU button: go back one level.
	 *   sub-activity on top  -> finish it (leave Global Settings, etc.)
	 *   glasses SBS menu open -> close it
	 *   MAME's own menu open  -> ESC (one step back inside MAME)
	 *   otherwise             -> open the TapMame menu
	 */
	public static void back(MAME4droid mm) {
		if (topIsSubScreen()) { top.runOnUiThread(top::finish); return; }
		GlassesUi ui = mm.getGlassesUi();
		if (ui != null && ui.isMenuVisible()) { mm.runOnUiThread(ui::hideMenu); return; }
		if (Emulator.isInGame() && Emulator.isInMenu()) { esc(mm); return; }
		if (ui != null) mm.runOnUiThread(ui::showMainMenu);
	}

	/**
	 * EXIT button:
	 *   in a game       -> ESC, which brings up MAME's OWN "Are you sure you
	 *                      want to quit?" prompt (Quit -> game list, Return to
	 *                      emulation -> game). We don't add our own confirm or
	 *                      pause the emulator — a paused machine can't process
	 *                      that prompt, which was the bug that left Quit stuck.
	 *   not in a game   -> straight out to the main game-select screen
	 *                      (close any settings screen / open menu; never ESC
	 *                       the machine list itself)
	 */
	// MAME's own quit prompt doesn't register as isInMenu(), so we track when
	// we've raised it: while this window is open, the pad/glasses drive it with
	// UI keys (arrows + ENTER) instead of feeding the game.
	private static volatile long quitConfirmUntil = 0;
	public static boolean inQuitConfirm() { return android.os.SystemClock.uptimeMillis() < quitConfirmUntil; }
	public static void clearQuitConfirm() { quitConfirmUntil = 0; }

	/** Raise MAME's in-frame "Are you sure you want to quit?" and arm nav for it. */
	public static void exitGame(MAME4droid mm) {
		GlassesUi ui = mm.getGlassesUi();
		if (ui != null && ui.isMenuVisible()) mm.runOnUiThread(ui::hideMenu);
		esc(mm);
		quitConfirmUntil = android.os.SystemClock.uptimeMillis() + 15000;
	}

	public static void exit(MAME4droid mm) {
		if (Emulator.isInGame()) {
			// ONE confirmation: MAME's own in-frame prompt (both eyes),
			// answered by a glasses tap or the phone pad.
			exitGame(mm);
			return;
		}
		// not in a game: pop everything back to the frontend root
		if (topIsSubScreen()) { top.runOnUiThread(top::finish); return; }
		GlassesUi ui = mm.getGlassesUi();
		if (ui != null && ui.isMenuVisible()) mm.runOnUiThread(ui::hideMenu);
		// already at the machine list: that IS the main screen — do nothing
	}

	/**
	 * Feed a key straight to MAME4droid's InputHandler.onKey — the exact
	 * handler `input keyevent` reaches through the focused view. The raw
	 * Emulator.setKeyData / EXIT_VALUE / activity.dispatchKeyEvent paths do
	 * NOT drive MAME's "exit machine" and quit-prompt confirm; only real key
	 * events through onKey do.
	 */
	private static android.view.KeyEvent kev(long t, int action, int code) {
		// match a real keyboard event (what `input keyevent` injects): the
		// VIRTUAL_KEYBOARD device + SOURCE_KEYBOARD are what MAME4droid's
		// GameController/Keyboard handlers key off — a bare KeyEvent (source 0)
		// gets mapped differently and won't drive the quit flow.
		return new android.view.KeyEvent(t, t, action, code, 0, 0,
			android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
			android.view.InputDevice.SOURCE_KEYBOARD);
	}

	/** Feed one key press+release into MAME through InputHandler.onKey. */
	public static void mameKey(final MAME4droid mm, final int code) {
		mm.runOnUiThread(() -> {
			long t = android.os.SystemClock.uptimeMillis();
			android.view.View v = mm.getEmuView();
			com.seleuco.mame4droid.input.InputHandler ih = mm.getInputHandler();
			if (ih == null || v == null) return;
			ih.onKey(v, code, kev(t, android.view.KeyEvent.ACTION_DOWN, code));
			ih.onKey(v, code, kev(t, android.view.KeyEvent.ACTION_UP, code));
		});
	}

	/** ESC into MAME (raises the quit prompt on a running game / backs one UI level). */
	public static void esc(final MAME4droid mm) {
		mameKey(mm, android.view.KeyEvent.KEYCODE_ESCAPE);
	}
}
