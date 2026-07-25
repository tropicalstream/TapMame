/*
 * This file is part of MAME4droid.
 *
 * Copyright (C) 2023 David Valdeita (Seleuco)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 * Linking MAME4droid statically or dynamically with other modules is
 * making a combined work based on MAME4droid. Thus, the terms and
 * conditions of the GNU General Public License cover the whole
 * combination.
 *
 * In addition, as a special exception, the copyright holders of MAME4droid
 * give you permission to combine MAME4droid with free software programs
 * or libraries that are released under the GNU LGPL and with code included
 * in the standard release of MAME under the MAME License (or modified
 * versions of such code, with unchanged license). You may copy and
 * distribute such a system following the terms of the GNU GPL for MAME4droid
 * and the licenses of the other code concerned, provided that you include
 * the source code of that other code when and as the GNU GPL requires
 * distribution of source code.
 *
 * Note that people who make modified versions of MAME4idroid are not
 * obligated to grant this special exception for their modified versions; it
 * is their choice whether to do so. The GNU General Public License
 * gives permission to release a modified version without this exception;
 * this exception also makes it possible to release a modified version
 * which carries forward this exception.
 *
 * MAME4droid is dual-licensed: Alternatively, you can license MAME4droid
 * under a MAME license, as set out in http://mamedev.org/
 */

package com.seleuco.mame4droid.helpers;

import android.util.Log;

import com.seleuco.mame4droid.MAME4droid;
import com.seleuco.mame4droid.scrape.ADBScraper;
import com.seleuco.mame4droid.scrape.IScraper;
import com.seleuco.mame4droid.scrape.ScrapeException;
import com.seleuco.mame4droid.widgets.WarnWidget;
import android.graphics.Color;

import java.io.*;
import java.util.ArrayList;

public class ScraperHelper implements Runnable {

    protected MAME4droid mm = null;

    private static final String TAG = "SCRAPPER";

    public static boolean isRunning = false;
    public static boolean isPaused = false;
	public static boolean isStopped = false;
	public static boolean isScraping = false;

	private static int current = 0;

	private static IScraper scraper = null;
	private static String scraperDir = null;
    private static Thread scraperThread;
    private static final ArrayList<String> names = new ArrayList<>();

	/** Pause/resume/stop are static state, so the worker has to wait on
	 *  something equally static. Waiting on `this` meant a MAME4droid recreated
	 *  by a rotation or a resume held a different monitor than the running
	 *  thread, and neither notify() nor stop() ever reached it. */
	private static final Object LOCK = new Object();

	public static void reset(){
		if(scraper!=null)
			scraper.reset();
	}

    public ScraperHelper(MAME4droid value) {
        mm = value;
		if(scraper!=null){
			scraper.setMAME4droid(value);
		}
    }

    public void initMediaScrap() {

		if(!mm.getPrefsHelper().isScrapingIcons() &&
			!mm.getPrefsHelper().isScrapingSnapshots() &&
			!mm.getPrefsHelper().isScrapingAll() ) {
			Log.d(TAG, "There's nothing to scrape");
			return;
		}

		if (isRunning) return;

		// Build the work list BEFORE latching isRunning. Upstream set the flag
		// first and then walked a list that is null whenever SAF is unused, so
		// the NPE left isRunning stuck true and every later call silently
		// no-oped for the life of the process.
		ArrayList<String> found = listRomsets();
		if (found.isEmpty()) {
			Log.d(TAG, "No romsets to scrape");
			return;
		}

		// The scraper caches its output directory in a final field, so it has
		// to be rebuilt if the install dir moved under us.
		String dir = mm.getMainHelper().getInstallationDIR();
		if (scraper == null || !dir.equals(scraperDir)) {
			scraper = new ADBScraper(dir, mm);
			scraperDir = dir;
		} else {
			scraper.setMAME4droid(mm);
		}

		isRunning = true;
		isPaused = false;
		isStopped = false;
		isScraping = false;
		current = 0;
		names.clear();   // static list: without this a second run scrapes twice
		names.addAll(found);
		scraperThread = new Thread(this);
		scraperThread.start();
    }

	/**
	 * Base names of every installed romset. Upstream asked SAFHelper
	 * unconditionally, which hands back null when the user never picked a
	 * document tree — and TapMame never does, because it keeps romsets in its
	 * own files dir. That single line is why art never downloaded here.
	 */
	private ArrayList<String> listRomsets() {
		ArrayList<String> out = new ArrayList<>();
		ArrayList<String> files;

		String romsDir = mm.getPrefsHelper().getROMsDIR();
		if (romsDir != null && !romsDir.isEmpty()) {
			files = mm.getSAFHelper().getRomsFileNames();
			if (files == null) return out;
		} else {
			files = new ArrayList<>();
			File[] fs = new File(mm.getMainHelper().getInstallationDIR() + "roms").listFiles();
			if (fs == null) return out;
			for (File f : fs)
				if (f.isFile()) files.add(f.getName());
		}

		for (String name : files) {
			String low = name.toLowerCase();
			// .chd too — the ROM manager already accepts all three.
			if (!low.endsWith(".zip") && !low.endsWith(".7z") && !low.endsWith(".chd"))
				continue;
			// lastIndexOf, not indexOf: "sf2.ce.zip" is a real romset filename
			// and the first dot would truncate it to "sf2", scraping the wrong
			// game and filing the art under the wrong name.
			String base = name.substring(0, name.lastIndexOf('.'));
			if (!base.isEmpty() && !out.contains(base)) out.add(base);
		}
		return out;
	}

    @Override
    public void run() {
        Log.d(TAG, "Scraping starts");
		int failures = 0;

        for (String name : names) {

			File f = new File(mm.getMainHelper().getInstallationDIR());

			long freeGB = f.getFreeSpace() / (1024*1024*1024);

			Log.d(TAG, "free space: " + freeGB);

			if(freeGB <= 1)
			{
				new WarnWidget.WarnWidgetHelper(mm, mm.getString(com.seleuco.mame4droid.R.string.scraping_no_space), 3, Color.RED, false);
				ScraperHelper.isStopped = true;
				break;
			}

			boolean scrapping = false;

			try {
				scrapping = scraper.scrape(name, current);
				failures = 0;
			}catch (ScrapeException e) {
				// Only the reachability probe throws. Scraping fires during
				// emulator startup, when the glasses' Wi-Fi has often not
				// associated yet — upstream abandoned the whole session on that
				// first cold-radio failure. Give the radio time and try again.
				if (++failures >= 4) {
					new WarnWidget.WarnWidgetHelper(mm, mm.getString(com.seleuco.mame4droid.R.string.scraping_error, e.getMessage()), 3, Color.RED, false);
					break;
				}
				Log.d(TAG, "ADB unreachable (" + failures + "), backing off: " + e.getMessage());
				if (sleep(15000) || isStopped) break;
				continue;
			}

			if(!ScraperHelper.isScraping && scrapping){
				ScraperHelper.isScraping = true;
				new WarnWidget.WarnWidgetHelper(mm, mm.getString(com.seleuco.mame4droid.R.string.scraping_running), 3, Color.GREEN, true);
			}

			current++;

            synchronized(LOCK) {
                if (isPaused) {
                    try {
						Log.d(TAG, "Scraping paused");
                        LOCK.wait();
						Log.d(TAG, "Scraping resumed");
                    } catch (InterruptedException ignored) {}
                }
            }

			if(isStopped){
				Log.d(TAG, "Scraping stopped");
				break;
			}

			// A courtesy pause between romsets. arcadeitalia serves this for
			// free and a full library is a few hundred requests back to back.
			if (scrapping && sleep(300)) break;
        }
        isRunning = false;

		if(isScraping && !isStopped){
			new WarnWidget.WarnWidgetHelper(mm, mm.getString(com.seleuco.mame4droid.R.string.scraping_ends), 5, Color.GREEN, true);
		}
        Log.d(TAG, "Scraping ends");
    }

	/** Interruptible sleep. Returns true if the wait was cut short, which the
	 *  caller treats as "give up now" — it only happens on shutdown. */
	private static boolean sleep(long ms) {
		try { Thread.sleep(ms); return false; }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); return true; }
	}

    public void pause() {
		Log.d(TAG, "Scraping calling pause");
		synchronized (LOCK) {
			if (!isPaused && isRunning) isPaused = true;
		}
    }

    public void resume() {
		Log.d(TAG, "Scraping calling resume");
		synchronized (LOCK) {
			if (isPaused && isRunning) { isPaused = false; LOCK.notifyAll(); }
		}
    }

	public void stop() {
		Log.d(TAG, "Scraping calling stop");
		synchronized (LOCK) {
			if (isRunning) { isStopped = true; isPaused = false; LOCK.notifyAll(); }
		}
	}

}
