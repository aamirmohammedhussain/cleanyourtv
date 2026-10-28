package com.gathin.tvhome;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * keep_key_services (config.xml or a theme): keeps the named remote helpers on the accessibility list for as long as
 * Home's process lives, for firmware that rewrites the list while Home runs. On the Skyworth Google TV stick in a
 * Wanbo X5 Pro projector (HP4609, Google TV 14, firmware C3.2.5) com.sdt.projector.observer, which switches its own
 * HDMI accessibility service on and off, writes enabled_accessibility_services empty about 4 s after Home starts at
 * every boot; Android then sets accessibility_enabled to 0, and the Settings gear and the other helper keys stop
 * until the helpers are enabled again over ADB. restore_key_services does not cover that: Android unbinds the helpers
 * normally there, so they drop their ServiceRepair record, and that repair only runs when Home starts.
 *
 * A ContentObserver on both settings, registered once per process on the application context, waits until they
 * have been still for a second (the firmware's write and Android's flag write are one change), then puts a missing
 * helper back at the end of the list, every other entry left as it was, and sets accessibility_enabled. It writes only
 * while the list still reads what it read (ServiceRepair.replaceList), so a write the firmware makes meanwhile is not
 * overwritten; that write calls the observer again. The change our own write causes finds nothing missing and ends
 * there. At most {@link #MAX_READDS} re-adds in any {@link #WINDOW_MS}: one more within it pauses the repair for
 * {@link #PAUSE_MS}, then it starts again with a fresh window, so a fight with the firmware stays bounded however long
 * Home runs, and a process that lives for days still repairs a later change. Needs WRITE_SECURE_SETTINGS
 * (RESTORE_KEY_SERVICES=1 at install); without it, or with the list empty, nothing is watched.
 */
final class KeepKeyServices {
    private static final String TAG = "TvHome.KeepKeys";
    /** The helpers keep_key_services can name, by simple class name; any other name is ignored. */
    static final List<String> HELPERS = Collections.unmodifiableList(Arrays.asList("HomeKeyService", "InputKeyService"));
    /** How long both settings have to stay still before a check. */
    static final long SETTLE_MS = 1000;
    static final int MAX_READDS = 5;
    static final long WINDOW_MS = 10 * 60_000L;
    static final long PAUSE_MS = 30 * 60_000L;

    /** Null until the first start that names a helper; then registered for the rest of the process. */
    private static ContentObserver observer;
    private static Handler main;
    /** Starts one check on a thread of its own; posted on main {@link #SETTLE_MS} after the last change. */
    private static Runnable task;
    /** The helpers' class names from the last start; read by the check thread. */
    private static volatile List<String> wanted = Collections.emptyList();
    private static final Budget budget = new Budget(MAX_READDS, WINDOW_MS, PAUSE_MS);
    /** A refused read or write is logged once per process, not on every change. */
    private static boolean failureLogged;

    private KeepKeyServices() {
    }

    /**
     * Home's onCreate, with keep_key_services set. The first call registers the observer on the application context,
     * not the activity's, so a recreated Home neither drops it nor adds a second one; every call checks once.
     */
    static void start(Context context, List<String> names) {
        List<String> helpers = helpers(names);
        if (helpers.isEmpty()) return;
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        List<String> classes = new ArrayList<>();
        for (String helper : helpers) {
            classes.add(("HomeKeyService".equals(helper) ? HomeKeyService.class : InputKeyService.class).getName());
        }
        wanted = Collections.unmodifiableList(classes);
        if (observer == null) {
            Context app = context.getApplicationContext();
            Handler handler = new Handler(Looper.getMainLooper());
            ContentObserver watcher = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    schedule();
                }
            };
            try {
                ContentResolver cr = app.getContentResolver();
                cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                        false, watcher);
                cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                        false, watcher);
            } catch (RuntimeException e) {
                Log.w(TAG, "keep_key_services: cannot watch the accessibility settings: " + e);
                return;
            }
            main = handler;
            task = () -> new Thread(() -> keep(app), "KeepKeyServices").start();
            observer = watcher;
        }
        schedule();
    }

    /** Home is back in front: one more check, in case a change came before the observer did. A no-op while off. */
    static void check() {
        if (observer != null) schedule();
    }

    /** On the main thread: a second after the last change, and only once for a burst of them. */
    private static void schedule() {
        main.removeCallbacks(task);
        main.postDelayed(task, SETTLE_MS);
    }

    /** One check, off the main thread like ServiceRepair's writes: puts back what the list lost, within the budget. */
    private static synchronized void keep(Context app) {
        List<String> classes = new ArrayList<>(wanted);
        if (classes.isEmpty() || budget.pausedAt(SystemClock.elapsedRealtime())) return;
        try {
            // guard_off_in_standby: HomeKeyService steps off the list itself while the screen is off and GuardKeep
            // puts it back when the screen lights (TCL tc8000); putting it back sooner would undo that.
            if (GuardKeep.offInStandby(app)) {
                PowerManager power = app.getSystemService(PowerManager.class);
                if (power != null && !power.isInteractive()) classes.remove(HomeKeyService.class.getName());
            }
            ContentResolver cr = app.getContentResolver();
            String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            List<String> lost = missing(list, app.getPackageName(), classes);
            // Nothing missing, which is also what the change from our own write finds: no write, no loop.
            if (lost.isEmpty()) return;
            if (!budget.take(SystemClock.elapsedRealtime())) {
                // Logged once per pause: during it keep() returns before reading anything.
                Log.w(TAG, "keep_key_services: the accessibility list lost a helper again after " + MAX_READDS
                        + " put-backs in 10 minutes; pausing for 30 minutes");
                // Nothing may change in the meantime, so look again when the pause is over.
                main.postDelayed(KeepKeyServices::schedule, PAUSE_MS);
                return;
            }
            // Only over the list just read: a firmware write since then stays, and its change calls us again.
            if (!ServiceRepair.replaceList(cr, list, withHelpers(list, app.getPackageName(), lost))) return;
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(TAG, "keep_key_services: put " + lost + " back on the accessibility list");
        } catch (RuntimeException e) {
            // The settings are the system's; a refusal leaves the helpers as they were.
            if (!failureLogged) {
                failureLogged = true;
                Log.w(TAG, "keep_key_services: cannot read or write the accessibility settings: " + e);
            }
        }
    }

    /** The helpers keep_key_services names, in its order and once each; any other name is ignored. */
    static List<String> helpers(List<String> names) {
        List<String> out = new ArrayList<>();
        for (String name : names) {
            String helper = name == null ? "" : name.trim();
            if (HELPERS.contains(helper) && !out.contains(helper)) out.add(helper);
        }
        return out;
    }

    /** The helper classes the enabled list does not name, short (pkg/.Class) or in full. */
    static List<String> missing(String list, String pkg, List<String> classes) {
        List<String> entries = entries(list);
        List<String> out = new ArrayList<>();
        for (String cls : classes) {
            boolean named = false;
            for (String entry : entries) {
                if (names(entry, pkg, cls)) named = true;
            }
            if (!named) out.add(cls);
        }
        return out;
    }

    /** The enabled list with these helpers added at the end; every entry already on it stays, in its place. */
    static String withHelpers(String list, String pkg, List<String> classes) {
        List<String> entries = entries(list);
        for (String cls : classes) entries.add(shortName(pkg, cls));
        return String.join(":", entries);
    }

    /** As ComponentName.flattenToShortString writes it: pkg/.Class for a class inside the package. */
    static String shortName(String pkg, String cls) {
        return pkg + "/" + (cls.startsWith(pkg + ".") ? cls.substring(pkg.length()) : cls);
    }

    /** The list's entries; an absent list, the string "null" and empty entries name nothing. */
    private static List<String> entries(String list) {
        List<String> out = new ArrayList<>();
        if (list == null || list.equals("null")) return out;
        for (String entry : list.split(":")) {
            if (!entry.isEmpty()) out.add(entry);
        }
        return out;
    }

    private static boolean names(String entry, String pkg, String cls) {
        int slash = entry.indexOf('/');
        if (slash <= 0 || !entry.substring(0, slash).equals(pkg)) return false;
        String name = entry.substring(slash + 1);
        return (name.startsWith(".") ? pkg + name : name).equals(cls);
    }

    /**
     * At most {@code max} re-adds in any {@code windowMs}. The first one over that starts a pause of {@code pauseMs},
     * in which nothing is allowed; after it the count starts again from an empty window.
     */
    static final class Budget {
        private final int max;
        private final long windowMs;
        private final long pauseMs;
        private final ArrayDeque<Long> times = new ArrayDeque<>();
        private boolean paused;
        private long pausedUntil;

        Budget(int max, long windowMs, long pauseMs) {
            this.max = max;
            this.windowMs = windowMs;
            this.pauseMs = pauseMs;
        }

        /** True, and counted, when one more re-add at {@code now} fits; false during a pause, or when it starts one. */
        boolean take(long now) {
            if (pausedAt(now)) return false;
            if (paused) {
                paused = false;
                times.clear();
            }
            while (!times.isEmpty() && now - times.peekFirst() >= windowMs) times.pollFirst();
            if (times.size() >= max) {
                paused = true;
                pausedUntil = now + pauseMs;
                return false;
            }
            times.addLast(now);
            return true;
        }

        /** True while a pause started by {@link #take} has not run out at {@code now}. */
        boolean pausedAt(long now) {
            return paused && now < pausedUntil;
        }
    }
}
