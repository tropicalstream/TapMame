/*
 * TapMame glasses UI layer — SBS menus + temple-touchpad gestures.
 *
 * Part of TapMame, a MAME4droid-Current fork (GPL-2.0, see COPYING).
 *
 * The X3 panel is binocular SBS, so Android dialogs (drawn once, floating)
 * are unreadable through the glasses. This view sits on top of the emulator
 * frame and does two jobs:
 *
 *  1. Draws TapMame's own menus TWICE — once per 640px eye — with a
 *     highlight bar, exactly like the rest of the X3 suite.
 *  2. Translates the temple touchpad (which arrives as touchscreen events)
 *     into arcade-frontend navigation: swipe up/down/left/right = one
 *     DPAD step, tap = ENTER (launches the highlighted game), long-press =
 *     TapMame menu. While a game runs, plain taps are swallowed (gameplay
 *     input comes from the companion pad) but long-press still opens the
 *     menu, and BACK asks to exit via an SBS confirm instead of the old
 *     one-eyed dialog.
 */
package com.seleuco.mame4droid.tapmame;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.seleuco.mame4droid.Emulator;
import com.seleuco.mame4droid.MAME4droid;

public class GlassesUi extends View {

	public interface MenuAction { void run(); }

	private static final float SWIPE_MIN = 180f;   // px on the 1280 panel
	private static final int EYE_W = 640;

	private final MAME4droid mm;

	private final Paint veil = new Paint();
	private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint hi = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF rect = new RectF();

	// menu state
	private boolean menuVisible = false;
	private String title = "";
	private String[] items = new String[0];
	private MenuAction[] actions = new MenuAction[0];
	private int sel = 0;

	// gesture state (no long-press: the X3 reserves temple long-press for
	// its own quick-settings shade — the menu opens with double-tap/BACK)
	private float downX, downY;
	private long downT;
	private boolean moved;

	public GlassesUi(MAME4droid mm) {
		super(mm);
		this.mm = mm;
		veil.setColor(0xB4000000);
		panel.setColor(0xF01A2129);
		hi.setColor(0xFF2F5E8E);
		text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
		text.setTextAlign(Paint.Align.CENTER);
		setWillNotDraw(false);
	}

	private boolean sbs() {
		return mm.getPrefsHelper() == null || mm.getPrefsHelper().isSbsEnabled();
	}

	// ------------------------------------------------------------- menus

	private boolean menuPaused = false;

	public void showMenu(String title, String[] items, MenuAction[] actions) {
		showMenu(title, items, actions, true);
	}

	/**
	 * @param pauseGame pause the emulator behind the menu. The exit confirm
	 *   leaves the game RUNNING so that selecting Quit can ESC straight to the
	 *   machine list — a just-resumed machine takes ESC to MAME's own quit
	 *   prompt instead, which then needs a second confirm.
	 */
	public void showMenu(String title, String[] items, MenuAction[] actions, boolean pauseGame) {
		this.title = title;
		this.items = items;
		this.actions = actions;
		this.sel = 0;
		menuVisible = true;
		menuPaused = pauseGame;
		if (pauseGame) Emulator.pause();
		invalidate();
	}

	public void hideMenu() {
		menuVisible = false;
		if (menuPaused) { Emulator.resume(); menuPaused = false; }
		invalidate();
	}

	public boolean isMenuVisible() { return menuVisible; }

	/** The TapMame menu — same reach as the old dialog, both eyes. */
	public void showMainMenu() {
		final boolean inGame = Emulator.isInGame();
		java.util.ArrayList<String> it = new java.util.ArrayList<>();
		java.util.ArrayList<MenuAction> ac = new java.util.ArrayList<>();
		if (inGame) {
			it.add("Resume");             ac.add(this::hideMenu);
			it.add("Game Settings (DIPs, Inputs…)"); ac.add(() -> { hideMenu(); LinkServer.openGameMenu(mm); });
			it.add("Load State");         ac.add(() -> { hideMenu(); loadOrSave(true); });
			it.add("Save State");         ac.add(() -> { hideMenu(); loadOrSave(false); });
			// raises MAME's own in-frame quit confirm (both eyes)
			it.add("Exit Game");          ac.add(() -> { hideMenu(); TapNav.exitGame(mm); });
		} else {
			it.add("Close Menu");         ac.add(this::hideMenu);
			// rebuild MAME's game list so newly uploaded ROMs appear (reliable
			// whole-app restart that never drops the launcher icon)
			it.add("Reload game list");   ac.add(() -> { hideMenu(); LinkServer.reloadGameList(mm); });
		}
		// Settings and NetPlay live on the PHONE now — they were Android
		// screens shown in one eye, which is uncomfortable through the
		// glasses. Only in-frame binocular UI stays here (Game Settings is
		// MAME's own TAB menu, drawn in both eyes).
		it.add("Exit TapMame");           ac.add(this::showExitConfirm);
		showMenu("TAPMAME", it.toArray(new String[0]), ac.toArray(new MenuAction[0]));
	}

	public void showExitConfirm() {
		showMenu("EXIT TAPMAME?",
			new String[]{"Keep Playing", "Exit"},
			new MenuAction[]{
				this::hideMenu,
				() -> { hideMenu(); mm.finishAndRemoveTask(); android.os.Process.killProcess(android.os.Process.myPid()); }
			});
	}


	private void loadOrSave(boolean load) {
		Emulator.resume();
		int key = load ? Emulator.LOADSTATE : Emulator.SAVESTATE;
		Emulator.setValue(key, 1);
		Emulator.setSaveorload(true);
		try { Thread.sleep(100); } catch (InterruptedException ignored) {}
		Emulator.setValue(key, 0);
	}

	// ------------------------------------------------------------- draw

	@Override
	protected void onDraw(Canvas c) {
		if (!menuVisible) return;
		int h = getHeight();
		c.drawRect(0, 0, getWidth(), h, veil);
		for (int eye = 0; eye < (sbs() ? 2 : 1); eye++) {
			c.save();
			c.translate(eye * EYE_W, 0);
			c.clipRect(0, 0, sbs() ? EYE_W : getWidth(), h);
			drawEye(c, sbs() ? EYE_W : getWidth(), h);
			c.restore();
		}
	}

	private void drawEye(Canvas c, int w, int h) {
		float itemH = 40f;
		float panelH = 84f + items.length * itemH;
		float top = (h - panelH) / 2f;
		rect.set(w * 0.08f, top, w * 0.92f, top + panelH);
		c.drawRoundRect(rect, 14f, 14f, panel);

		text.setColor(0xFFE8B33C);
		text.setTextSize(26f);
		c.drawText(title, w / 2f, top + 44f, text);

		float y = top + 76f;
		for (int i = 0; i < items.length; i++) {
			if (i == sel) {
				rect.set(w * 0.10f, y - 4f, w * 0.90f, y + itemH - 10f);
				c.drawRoundRect(rect, 8f, 8f, hi);
			}
			text.setColor(i == sel ? Color.WHITE : 0xFFB9C2CB);
			text.setTextSize(20f);
			c.drawText(items[i], w / 2f, y + 20f, text);
			y += itemH;
		}
	}

	// ------------------------------------------------------------- input

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent e) {
		if (!sbs()) return false;
		switch (e.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				downX = e.getX(); downY = e.getY(); downT = System.currentTimeMillis();
				moved = false;
				return true;
			case MotionEvent.ACTION_MOVE:
				if (Math.abs(e.getX() - downX) > SWIPE_MIN || Math.abs(e.getY() - downY) > SWIPE_MIN)
					moved = true;
				return true;
			case MotionEvent.ACTION_UP: {
				float dx = e.getX() - downX, dy = e.getY() - downY;
				if (Math.abs(dx) >= SWIPE_MIN || Math.abs(dy) >= SWIPE_MIN) {
					boolean horiz = Math.abs(dx) >= Math.abs(dy);
					if (menuVisible) {
						if (!horiz) move(dy > 0 ? 1 : -1);
						// horizontal swipes are ignored inside menus
					} else {
						sendKey(horiz ? (dx > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT)
								: (dy > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP));
					}
				} else {
					// tap
					if (menuVisible) {
						MenuAction a = sel < actions.length ? actions[sel] : null;
						if (a != null) a.run();
					} else if (Emulator.isInGame() && TapNav.inQuitConfirm()) {
						// MAME's quit prompt: tap = select (Quit / Return).
						// resume() first — a paused machine can't process it
						Emulator.resume();
						sendKey(KeyEvent.KEYCODE_ENTER);
						TapNav.clearQuitConfirm();
					} else if (!Emulator.isInGameButNotInMenu()) {
						// frontend / MAME menu: tap = select (launch the game!)
						sendKey(KeyEvent.KEYCODE_ENTER);
					}
					// during actual gameplay taps are swallowed — the pad plays
				}
				return true;
			}
			case MotionEvent.ACTION_CANCEL:
				return true;
		}
		return true;
	}

	private void move(int d) {
		sel = (sel + d + items.length) % items.length;
		invalidate();
	}

	/** Remote navigation from the companion pad (stick up/down). */
	public void menuMove(int d) {
		if (menuVisible) move(d);
	}

	/** Remote select from the companion pad (FIRE/START). */
	public void menuSelect() {
		if (!menuVisible) return;
		MenuAction a = sel < actions.length ? actions[sel] : null;
		if (a != null) a.run();
	}

	/** One key press into MAME via the reliable InputHandler.onKey path. */
	private void sendKey(final int code) {
		TapNav.mameKey(mm, code);
	}

	/**
	 * BACK on the glasses (temple double-tap): open the TapMame SBS menu —
	 * exactly what a player expects when they double-tap looking for a way
	 * to act on the highlighted game. A second BACK closes it. Exit lives
	 * inside the menu, behind its own SBS confirm.
	 */
	public boolean onBackPressed() {
		if (!sbs()) return false;
		if (menuVisible) { hideMenu(); return true; }
		showMainMenu();
		return true;
	}
}
