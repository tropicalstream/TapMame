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

	private final MAME4droid mm;
	private ServerSocket server;
	private Thread thread;
	private volatile boolean running;
	private NsdManager nsd;
	private NsdManager.RegistrationListener nsdListener;

	public LinkServer(MAME4droid mm) {
		this.mm = mm;
	}

	public synchronized void start() {
		if (running) return;
		running = true;
		thread = new Thread(this::run, "TapMameLink");
		thread.setDaemon(true);
		thread.start();
		registerNsd();
	}

	public synchronized void stop() {
		running = false;
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
	}

	private void handle(String line, InputStream in, OutputStream out) throws IOException {
		String[] tk = line.split(" ");
		switch (tk[0]) {
			case "PAD": {
				int p = Integer.parseInt(tk[1]);
				long mask = Long.parseLong(tk[2]);
				Emulator.setDigitalData(p, mask);
				break;
			}
			case "AXIS": {
				int p = Integer.parseInt(tk[1]);
				int i = Integer.parseInt(tk[2]);
				Emulator.setAnalogData(p, i, Float.parseFloat(tk[3]), Float.parseFloat(tk[4]));
				break;
			}
			case "KEY": {
				Emulator.setKeyData(Integer.parseInt(tk[1]), Integer.parseInt(tk[2]), (char) 0);
				break;
			}
			case "GAME?": {
				String name = null;
				try { name = Emulator.getValueStr(Emulator.ROM_NAME); } catch (Exception ignored) {}
				reply(out, "GAME " + (name == null ? "" : name));
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
		if (got == size && tmp.renameTo(f)) {
			reply(out, "OK " + name);
			Log.i(TAG, "ROM stored: " + f + " (" + size + " bytes)");
		} else {
			//noinspection ResultOfMethodCallIgnored
			tmp.delete();
			reply(out, "ERR short transfer " + got + "/" + size);
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
