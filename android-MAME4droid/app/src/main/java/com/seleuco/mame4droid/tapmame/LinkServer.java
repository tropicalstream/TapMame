/*
 * TapMame link server — the glasses end of the phone companion protocol.
 *
 * Part of TapMame, a MAME4droid-Current fork (GPL-2.0, see COPYING).
 *
 * One TCP port (19999), line-oriented text protocol, announced on the LAN
 * via NSD as _tapmame._tcp so the companion finds the glasses without any
 * IP typing:
 *
 *   PAD <player> <mask>          controller state (IController bitmask)
 *   AXIS <player> <idx> <x> <y>  analog stick/pedal state, floats -1..1
 *   KEY <code> <action>          raw key event into the MAME UI
 *   GAME?                        -> "GAME <romname>" ("" when in frontend)
 *   ROM <name> <size>            followed by <size> raw bytes; saved into
 *                                the roms folder -> "OK" | "ERR <reason>"
 *   PING                         -> "PONG <appversion>"
 *
 * Input is injected through the same static Emulator entry points the
 * built-in touch controller uses, so it behaves identically (including
 * during NetPlay, where local input becomes this player's lockstep feed).
 */
package com.seleuco.mame4droid.tapmame;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import com.seleuco.mame4droid.Emulator;
import com.seleuco.mame4droid.MAME4droid;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class LinkServer {

	public static final int PORT = 19999;
	private static final String TAG = "TapMameLink";

	/** Rom/game name captured on launch (intent path); "" when unknown. */
	public static volatile String currentGame = "";

	private static volatile LinkServer instance;
	private long lastPadMask = 0;

	private final MAME4droid mm;
	private ServerSocket server;
	private Thread thread;
	private volatile boolean running;
	private volatile OutputStream clientOut;
	private NsdManager nsd;
	private NsdManager.RegistrationListener nsdListener;

	public LinkServer(MAME4droid mm) {
		this.mm = mm;
	}

	/** Fire-and-forget line to the connected companion (no-op when none). */
	public static void push(String line) {
		LinkServer s = instance;
		if (s == null) return;
		OutputStream out = s.clientOut;
		if (out == null) return;
		try {
			synchronized (out) {
				out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
				out.flush();
			}
		} catch (IOException ignored) {}
	}

	/**
	 * Open MAME's own in-game menu (TAB): DIP switches, per-game inputs,
	 * sliders. Rendered inside the emulator frame, i.e. SBS on the glasses.
	 */
	public static void openGameMenu(MAME4droid mm) {
		new Thread(() -> {
			Emulator.setKeyData(android.view.KeyEvent.KEYCODE_TAB, Emulator.KEY_DOWN, (char) 0);
			try { Thread.sleep(120); } catch (InterruptedException ignored) {}
			Emulator.setKeyData(android.view.KeyEvent.KEYCODE_TAB, Emulator.KEY_UP, (char) 0);
		}, "TapMameTab").start();
	}

	public synchronized void start() {
		if (running) return;
		running = true;
		instance = this;
		thread = new Thread(this::run, "TapMameLink");
		thread.setDaemon(true);
		thread.start();
		registerNsd();
	}

	public synchronized void stop() {
		running = false;
		if (instance == this) instance = null;
		try { if (server != null) server.close(); } catch (IOException ignored) {}
		if (nsd != null && nsdListener != null) {
			try { nsd.unregisterService(nsdListener); } catch (Exception ignored) {}
			nsdListener = null;
		}
	}

	private void registerNsd() {
		try {
			nsd = (NsdManager) mm.getSystemService(Context.NSD_SERVICE);
			NsdServiceInfo info = new NsdServiceInfo();
			info.setServiceName("TapMame");
			info.setServiceType("_tapmame._tcp.");
			info.setPort(PORT);
			nsdListener = new NsdManager.RegistrationListener() {
				public void onServiceRegistered(NsdServiceInfo i) { Log.i(TAG, "NSD registered"); }
				public void onRegistrationFailed(NsdServiceInfo i, int e) { Log.w(TAG, "NSD failed " + e); }
				public void onServiceUnregistered(NsdServiceInfo i) {}
				public void onUnregistrationFailed(NsdServiceInfo i, int e) {}
			};
			nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener);
		} catch (Exception e) {
			Log.w(TAG, "NSD unavailable: " + e);
		}
	}

	private void run() {
		try {
			server = new ServerSocket(PORT);
		} catch (IOException e) {
			Log.e(TAG, "cannot listen on " + PORT, e);
			return;
		}
		Log.i(TAG, "listening on " + PORT);
		while (running) {
			try (Socket s = server.accept()) {
				s.setTcpNoDelay(true);
				// The companion polls every ~2s, so a live phone always reads
				// well within this window; a half-dead connection (app killed,
				// Wi-Fi dropped) times out and frees the server to accept the
				// phone's fresh connection instead of blocking forever.
				s.setSoTimeout(5000);
				Log.i(TAG, "companion connected: " + s.getInetAddress());
				serve(s);
			} catch (IOException e) {
				if (running) Log.w(TAG, "client error: " + e);
			}
			// a dropped controller must never leave buttons latched
			Emulator.setDigitalData(0, 0);
		}
	}

	private void serve(Socket s) throws IOException {
		InputStream in = new BufferedInputStream(s.getInputStream());
		OutputStream out = new BufferedOutputStream(s.getOutputStream());
		clientOut = out;
		try {
			StringBuilder sb = new StringBuilder(96);
			int c;
			while (running && (c = in.read()) != -1) {
				if (c != '\n') { sb.append((char) c); continue; }
				String line = sb.toString().trim();
				sb.setLength(0);
				if (line.isEmpty()) continue;
				try {
					handle(line, in, out);
				} catch (Exception e) {
					reply(out, "ERR " + e.getMessage());
				}
			}
		} finally {
			clientOut = null;
		}
	}

	private void handle(String line, InputStream in, OutputStream out) throws IOException {
		String[] tk = line.split(" ");
		switch (tk[0]) {
			case "PAD": {
				int p = Integer.parseInt(tk[1]);
				long mask = Long.parseLong(tk[2]);
				long pressed = mask & ~lastPadMask;   // newly-pressed bits (edge)
				lastPadMask = mask;
				final long UP = 0x1, DOWN = 0x10, LEFT = 0x4, RIGHT = 0x40;
				final long FIRE = (1L << 10) | (1L << 8);   // A or START = select

				GlassesUi ui = mm.getGlassesUi();
				// 1) TapMame's own SBS menu is open -> drive it
				if (ui != null && ui.isMenuVisible()) {
					if ((pressed & UP) != 0) mm.runOnUiThread(() -> ui.menuMove(-1));
					if ((pressed & DOWN) != 0) mm.runOnUiThread(() -> ui.menuMove(1));
					if ((pressed & FIRE) != 0) mm.runOnUiThread(ui::menuSelect);
					Emulator.setDigitalData(p, 0);
					break;
				}
				// 2) an Android menu is on top (Global Settings, NetPlay...) ->
				//    the pad becomes a DPAD remote for it
				if (com.seleuco.mame4droid.tapmame.TapNav.androidNavActive()) {
					if ((pressed & UP) != 0) TapNav.androidKey(mm, android.view.KeyEvent.KEYCODE_DPAD_UP);
					if ((pressed & DOWN) != 0) TapNav.androidKey(mm, android.view.KeyEvent.KEYCODE_DPAD_DOWN);
					if ((pressed & LEFT) != 0) TapNav.androidKey(mm, android.view.KeyEvent.KEYCODE_DPAD_LEFT);
					if ((pressed & RIGHT) != 0) TapNav.androidKey(mm, android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
					if ((pressed & FIRE) != 0) TapNav.androidKey(mm, android.view.KeyEvent.KEYCODE_DPAD_CENTER);
					Emulator.setDigitalData(p, 0);
					break;
				}
				// 3) any MAME-drawn menu — the frontend list, the TAB menu, OR
				//    the in-game quit prompt (which doesn't flag as isInMenu, so
				//    inQuitConfirm() covers it): the joystick directions
				//    navigate (as they do in the list), FIRE selects (ENTER —
				//    the key MAME's UI-select honours), B backs out (ESC).
				if (!Emulator.isInGame() || Emulator.isInMenu() || TapNav.inQuitConfirm()) {
					if ((pressed & FIRE) != 0) {
						TapNav.mameKey(mm, android.view.KeyEvent.KEYCODE_ENTER);
						if (TapNav.inQuitConfirm()) TapNav.clearQuitConfirm();
					}
					if ((pressed & (1L << 11)) != 0)   // B = back
						TapNav.mameKey(mm, android.view.KeyEvent.KEYCODE_ESCAPE);
					// directions only (strip buttons) so the menu scrolls
					Emulator.setDigitalData(p, mask & (UP | DOWN | LEFT | RIGHT));
					break;
				}
				// 4) actually in a game -> full gameplay
				Emulator.setDigitalData(p, mask);
				break;
			}
			case "AXIS": {
				// AXIS <analogType> <index> <x> <y> — type is LEFT_STICK_DATA
				// etc.; MAME maps the analog stick to the cabinet's real
				// paddle/dial/wheel/trackball internally.
				int type = Integer.parseInt(tk[1]);
				int i = Integer.parseInt(tk[2]);
				Emulator.setAnalogData(type, i, Float.parseFloat(tk[3]), Float.parseFloat(tk[4]));
				break;
			}
			case "KEY": {
				Emulator.setKeyData(Integer.parseInt(tk[1]), Integer.parseInt(tk[2]), (char) 0);
				break;
			}
			case "GAME?": {
				// GAME_SELECTED is populated by the core for the running
				// machine whatever the launch path (the NetPlay dialog reads
				// the same key); fall back to the Java launch capture.
				String name = null;
				try { name = Emulator.getValueStr(Emulator.GAME_SELECTED); } catch (Exception ignored) {}
				if (name == null || name.isEmpty()) name = currentGame;
				if ((name == null || name.isEmpty()) && Emulator.isInGame()) name = "(running)";
				reply(out, "GAME " + (name == null ? "" : name));
				break;
			}
			case "CMD": {
				switch (tk[1]) {
					case "GAMEMENU":
						Emulator.resume();
						openGameMenu(mm);
						reply(out, "OK gamemenu");
						break;
					case "SETTINGS":
						mm.runOnUiThread(() -> mm.getMainHelper().showSettings());
						reply(out, "OK settings");
						break;
					case "NETHOST":
						// start hosting with the stored mode; the phone then
						// polls NPADDR? and turns the address into an invite code
						mm.runOnUiThread(() -> mm.getNetPlay().createGame());
						reply(out, "OK nethost");
						break;
					case "NETJOIN": {
						final String addr = tk[2];
						mm.runOnUiThread(() -> mm.getNetPlay().joinGame(addr));
						reply(out, "OK netjoin");
						break;
					}
					case "EXIT":
						// context-aware: in a game -> SBS exit-game confirm;
						// not in a game -> straight out to the game-select
						// screen (closing settings / open menus on the way)
						TapNav.exit(mm);
						reply(out, "OK exit");
						break;
					case "MENU":
						// context-aware: something open -> back one level;
						// otherwise open the TapMame SBS menu
						TapNav.back(mm);
						reply(out, "OK menu");
						break;
					case "EXITGAME":   // legacy: direct leave-to-list (no confirm)
						Emulator.resume();
						TapNav.esc(mm);
						reply(out, "OK exitgame");
						break;
					default:
						reply(out, "ERR unknown cmd " + tk[1]);
				}
				break;
			}
			case "GETPREF": {
				// GETPREF <key> -> "PREF <key>=<value>" ("" when unset)
				String key = tk[1];
				Object v = mm.getPrefsHelper().getSharedPreferences().getAll().get(key);
				reply(out, "PREF " + key + "=" + (v == null ? "" : String.valueOf(v)));
				break;
			}
			case "SETPREF": {
				// SETPREF <key> <bool|int|string> <value>
				final String key = tk[1];
				String type = tk[2];
				String val = tk.length > 3 ? line.substring(line.indexOf(type) + type.length() + 1) : "";
				android.content.SharedPreferences.Editor e =
					mm.getPrefsHelper().getSharedPreferences().edit();
				if (type.equals("bool")) e.putBoolean(key, val.equals("true") || val.equals("1"));
				else if (type.equals("int")) e.putInt(key, Integer.parseInt(val.trim()));
				else e.putString(key, val);
				e.apply();
				// re-apply live where the emulator supports it (many MAME
				// options still take effect on the next game launch)
				mm.runOnUiThread(() -> { try { mm.getPrefsHelper().resume(); } catch (Exception ignored) {} });
				reply(out, "OK setpref");
				break;
			}
			case "NAV?": {
				// what the pad's stick+FIRE are currently driving, so the
				// companion can hint the user
				String mode;
				GlassesUi ui = mm.getGlassesUi();
				if (ui != null && ui.isMenuVisible()) mode = "menu";
				else if (com.seleuco.mame4droid.tapmame.TapNav.androidNavActive()) mode = "android";
				else if (Emulator.isInGame()) mode = "game";
				else mode = "frontend";
				reply(out, "NAV " + mode);
				break;
			}
			case "NPADDR?": {
				String a = null;
				try { a = Emulator.netplayGetPublicAddr(); } catch (Exception ignored) {}
				reply(out, "NPADDR " + (a == null ? "" : a.replace('\n', '|')));
				break;
			}
			case "ROM": {
				String name = tk[1];
				long size = Long.parseLong(tk[2]);
				receiveRom(name, size, in, out);
				break;
			}
			case "PING":
				reply(out, "PONG TapMame");
				break;
			default:
				reply(out, "ERR unknown " + tk[0]);
		}
	}

	private void receiveRom(String name, long size, InputStream in, OutputStream out) throws IOException {
		// only a plain zip/7z/chd file name — no path tricks from the wire
		if (name.contains("/") || name.contains("\\") || name.contains("..")) {
			reply(out, "ERR bad name");
			skip(in, size);
			return;
		}
		String dir = mm.getPrefsHelper().getROMsDIR();
		if (dir == null || dir.isEmpty())
			dir = mm.getMainHelper().getInstallationDIR() + "roms";
		File f = new File(dir, name);
		File tmp = new File(dir, name + ".part");
		long got = 0;
		try (FileOutputStream fo = new FileOutputStream(tmp)) {
			byte[] buf = new byte[65536];
			while (got < size) {
				int n = in.read(buf, 0, (int) Math.min(buf.length, size - got));
				if (n == -1) break;
				fo.write(buf, 0, n);
				got += n;
			}
		}
		if (got != size) {
			//noinspection ResultOfMethodCallIgnored
			tmp.delete();
			reply(out, "ERR short transfer " + got + "/" + size);
			return;
		}
		// zip romsets get an integrity pass before they're accepted: every
		// entry is read through, so a truncated/corrupt download is caught
		// here with a clear reason instead of a cryptic in-emulator failure.
		// (Whether the CONTENTS match this MAME version is the emulator's
		// audit — its report shows in-frame when the game is opened.)
		if (name.toLowerCase().endsWith(".zip")) {
			String zipErr = verifyZip(tmp);
			if (zipErr != null) {
				//noinspection ResultOfMethodCallIgnored
				tmp.delete();
				reply(out, "ERR " + name + " is not a valid zip (" + zipErr + ") — re-download the romset");
				return;
			}
		}
		if (tmp.renameTo(f)) {
			reply(out, "OK " + name);
			Log.i(TAG, "ROM stored: " + f + " (" + size + " bytes)");
			// The frontend builds its game list at startup, so a new romset
			// stays invisible until a rescan. When we're just browsing the
			// list, restart the app now so the game appears immediately;
			// mid-game we don't interrupt — it shows up next restart.
			if (!Emulator.isInGameButNotInMenu()) {
				push("MSG ROM stored: " + name + " — refreshing game list…");
				// small delay lets the OK reply + MSG flush to the phone first
				new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
					() -> mm.getMainHelper().restartApp(), 500);
			} else {
				push("MSG ROM stored: " + name + " — it will appear after the game list reloads");
			}
		} else {
			//noinspection ResultOfMethodCallIgnored
			tmp.delete();
			reply(out, "ERR cannot write " + name);
		}
	}

	/** Read every zip entry fully so CRC mismatches surface; null = OK. */
	private static String verifyZip(File f) {
		try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(f)) {
			byte[] buf = new byte[65536];
			java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
			int n = 0;
			while (en.hasMoreElements()) {
				java.util.zip.ZipEntry e = en.nextElement();
				n++;
				try (InputStream is = z.getInputStream(e)) {
					while (is.read(buf) != -1) { /* CRC checked on stream end */ }
				}
			}
			return n == 0 ? "empty archive" : null;
		} catch (Exception e) {
			return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		}
	}

	private static void skip(InputStream in, long n) throws IOException {
		long left = n;
		while (left > 0) {
			long s = in.skip(left);
			if (s <= 0) break;
			left -= s;
		}
	}

	private static void reply(OutputStream out, String line) throws IOException {
		out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
		out.flush();
	}
}
