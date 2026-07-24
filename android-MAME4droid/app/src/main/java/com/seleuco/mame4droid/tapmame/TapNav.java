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
		if (Emulator.isInGame() && Emulator.isInMenu()) { esc(); return; }
		if (ui != null) mm.runOnUiThread(ui::showMainMenu);
	}

	/**
	 * EXIT button:
	 *   in a game       -> SBS "exit game?" confirm, then back to the list
	 *   not in a game   -> straight out to the main game-select screen
	 *                      (close any settings screen / open menu; never ESC
	 *                       the machine list itself)
	 */
	public static void exit(MAME4droid mm) {
		if (Emulator.isInGame()) {
			GlassesUi ui = mm.getGlassesUi();
			if (ui != null) mm.runOnUiThread(ui::showExitGameConfirm);
			return;
		}
		// not in a game: pop everything back to the frontend root
		if (topIsSubScreen()) { top.runOnUiThread(top::finish); return; }
		GlassesUi ui = mm.getGlassesUi();
		if (ui != null && ui.isMenuVisible()) mm.runOnUiThread(ui::hideMenu);
		// already at the machine list: that IS the main screen — do nothing
	}

	/** One ESC keypress into MAME (backs one level in its own UI). */
	static void esc() {
		new Thread(() -> {
			Emulator.setKeyData(android.view.KeyEvent.KEYCODE_ESCAPE, Emulator.KEY_DOWN, (char) 0);
			try { Thread.sleep(110); } catch (InterruptedException ignored) {}
			Emulator.setKeyData(android.view.KeyEvent.KEYCODE_ESCAPE, Emulator.KEY_UP, (char) 0);
		}, "TapNavEsc").start();
	}
}
