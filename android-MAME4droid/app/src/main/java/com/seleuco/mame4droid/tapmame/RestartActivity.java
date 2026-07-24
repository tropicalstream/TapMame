/*
 * Reliable whole-app restart for the X3 (Android 13) — used to rebuild MAME's
 * game list after a ROM upload.
 *
 * Part of TapMame, a MAME4droid-Current fork (GPL-2.0, see COPYING).
 *
 * MAME reads its full driver list and audits the rompath exactly once, when the
 * native core boots, and this fork exposes no in-process rescan. So a freshly
 * uploaded romset is invisible until the app runs as a FRESH process.
 *
 * The obvious "startActivity() then killProcess()" from inside the main process
 * does NOT work on modern Android: the moment the main process dies, the pending
 * relaunch becomes a *background* activity start, which API29+ blocks. The app
 * then simply vanishes back to the launcher — and if the process ends up
 * force-stopped, the icon drops out of the glasses launcher entirely. That was
 * the "the app keeps disappearing after I upload a game" bug.
 *
 * This is the ProcessPhoenix trick. This activity is declared in a SEPARATE
 * process (":restart" in the manifest), so it stays alive while the main MAME
 * process is killed, and can then issue the relaunch as a genuine FOREGROUND
 * start — which the OS always honours — before tearing its own process down.
 * The result: a clean rebuild of the game list, and the icon never disappears.
 */
package com.seleuco.mame4droid.tapmame;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

public class RestartActivity extends Activity {

	private static final String TAG = "TapMame";
	/** PID of the main MAME process to kill before relaunching. */
	public static final String EXTRA_MAIN_PID = "com.tapmame.MAIN_PID";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Kill the old core FIRST so the relaunch is forced to spawn a brand-new
		// process (the main activity is singleTask — without the kill, the launch
		// would just resume the stale instance and skip the ROM re-audit).
		int mainPid = getIntent().getIntExtra(EXTRA_MAIN_PID, -1);
		if (mainPid > 0 && mainPid != Process.myPid()) {
			Process.killProcess(mainPid);
		}

		try {
			Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
			if (launch != null) {
				launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
				startActivity(launch);
			}
		} catch (Exception e) {
			Log.w(TAG, "restart relaunch failed: " + e);
		}

		finish();
		// Tear down this :restart process too, so nothing lingers.
		Runtime.getRuntime().exit(0);
	}
}
