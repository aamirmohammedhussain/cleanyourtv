package com.gathin.tvhome;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * adb_wifi_on_boot (config.xml, a theme, or its row in Launcher settings): Android 11 and later switch Wireless
 * debugging off at every boot, so a TV still being set up over the network needed a trip to Developer options after
 * each restart. With the switch on and WRITE_SECURE_SETTINGS granted, the first Home of a boot turns it back on. Once
 * per boot, keyed on Settings.Global.BOOT_COUNT and saved before the first attempt, so a Home restart later in the
 * same boot leaves an "off" made by hand alone. Switched off, it never turns debugging off itself; it only stops
 * turning it on, also during a wait that has already begun.
 *
 * Android writes the switch back to 0 when no Wi-Fi network is connected yet, or when the network is not one the
 * owner allowed (it then asks "Allow wireless debugging on this network?"). On the Skyworth stick in a Wanbo X5 Pro
 * Home starts about 2 s before Wi-Fi does, so a single write was undone at once. The write therefore waits for Wi-Fi
 * (at most {@link #WIFI_WAIT_MS}), and is checked {@link #SETTLE_MS} later: {@link #ATTEMPTS} tries in all, then one
 * warning. Bounded, and it never writes 0. Not retried: a reset later than that check (a Wi-Fi roam), or a boot whose
 * Home process was killed during the wait.
 */
final class AdbWifiOnBoot {
    private static final String TAG = "TvHome.AdbWifi";
    /** Settings.Global.ADB_WIFI_ENABLED, which the SDK hides. */
    static final String ADB_WIFI_ENABLED = "adb_wifi_enabled";
    /** The boot already handled, kept with the other once-per-boot records (OpenOnStart.BOOT_PREFS). */
    static final String BOOT_KEY = "adb_wifi_boot";
    static final long POLL_MS = 2000;
    static final long WIFI_WAIT_MS = 120_000;
    /** How long after a write Android has had to switch it back off. */
    static final long SETTLE_MS = 3000;
    static final int ATTEMPTS = 3;
    /** What follows a write: {@link #afterWrite}. */
    static final int DONE = 0, AGAIN = 1, GIVE_UP = 2;
    /** One thread at a time: two quick Home starts must not run two waits. */
    private static final AtomicBoolean running = new AtomicBoolean();
    /** A refused read or write is logged once per process. */
    private static volatile boolean failureLogged;

    private AdbWifiOnBoot() {
    }

    /** Whether this boot still needs its turn: a known boot count, not handled yet. */
    static boolean due(int boot, int handledBoot) {
        return boot >= 0 && boot != handledBoot;
    }

    /** Keep polling while Wi-Fi is not up, the switch is still on and the wait is not over. */
    static boolean keepWaiting(boolean onWifi, boolean switchedOff, long waitedMs) {
        return !onWifi && !switchedOff && waitedMs < WIFI_WAIT_MS;
    }

    /** After a write, read back SETTLE_MS later: done if it held, another attempt while any are left, else give up. */
    static int afterWrite(boolean held, int attempt) {
        if (held) return DONE;
        return attempt < ATTEMPTS ? AGAIN : GIVE_UP;
    }

    /**
     * Android 11 or later (adb_wifi_enabled does not exist before) and WRITE_SECURE_SETTINGS granted by the installer:
     * without both the switch can do nothing, and its row in Launcher settings is hidden.
     */
    static boolean available(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Home's onCreate with the switch on: on a thread of its own, which ends within ATTEMPTS waits of WIFI_WAIT_MS. */
    static void apply(Context context) {
        if (!available(context) || !running.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        try {
            new Thread(() -> {
                try {
                    run(app);
                } catch (RuntimeException | InterruptedException e) {
                    // The setting is the system's; a refusal leaves Wireless debugging as Android left it.
                    if (!failureLogged) {
                        failureLogged = true;
                        Log.w(TAG, "adb_wifi_on_boot: stopped: " + e);
                    }
                } finally {
                    running.set(false);
                }
            }, "AdbWifiOnBoot").start();
        } catch (RuntimeException e) {
            running.set(false);
        }
    }

    private static void run(Context app) throws InterruptedException {
        int boot = OpenOnStart.boot(app);
        SharedPreferences prefs = app.getSharedPreferences(OpenOnStart.BOOT_PREFS, Context.MODE_PRIVATE);
        if (!due(boot, prefs.getInt(BOOT_KEY, -1))) return;
        if (!prefs.edit().putInt(BOOT_KEY, boot).commit()) return;
        ContentResolver cr = app.getContentResolver();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            long waited = 0;
            boolean wifi = onWifi(app);
            boolean off = switchedOff(app);
            while (keepWaiting(wifi, off, waited)) {
                Thread.sleep(POLL_MS);
                waited += POLL_MS;
                wifi = onWifi(app);
                off = switchedOff(app);
            }
            // The row in Launcher settings, or a theme or Follow theme, may have switched it off meanwhile.
            if (off || !Config.load(app).adbWifiOnBoot) {
                Log.i(TAG, "adb_wifi_on_boot: switched off; Wireless debugging left as it is");
                return;
            }
            if (!wifi) {
                Log.w(TAG, "adb_wifi_on_boot: no Wi-Fi after " + WIFI_WAIT_MS / 1000
                        + " s; Wireless debugging stays off this boot");
                return;
            }
            if (Settings.Global.getInt(cr, ADB_WIFI_ENABLED, 0) != 1) {
                Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 1);
                Log.i(TAG, "adb_wifi_on_boot: Wireless debugging turned on for boot " + boot + ", attempt "
                        + attempt + " of " + ATTEMPTS);
            }
            Thread.sleep(SETTLE_MS);
            int next = afterWrite(Settings.Global.getInt(cr, ADB_WIFI_ENABLED, 0) == 1, attempt);
            if (next == DONE) return;
            if (next == GIVE_UP) {
                Log.w(TAG, "adb_wifi_on_boot: Android switched Wireless debugging off again after " + ATTEMPTS
                        + " attempts; most likely its \"Allow wireless debugging on this network?\" prompt"
                        + " is waiting: allow it once with \"Always allow on this network\" ticked");
                return;
            }
        }
    }

    /** The active network is Wi-Fi, which Android needs before it keeps Wireless debugging on. */
    private static boolean onWifi(Context app) {
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        Network n = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    /** Off in Launcher settings: one preference, cheap enough to read at every poll. */
    private static boolean switchedOff(Context app) {
        return !app.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getBoolean(Config.ADB_WIFI_ON_BOOT_KEY, true);
    }

    /** Switched on in Launcher settings: it starts with the next boot, as an Open on start pick does. */
    static void skipThisBoot(Context context) {
        context.getSharedPreferences(OpenOnStart.BOOT_PREFS, Context.MODE_PRIVATE).edit()
                .putInt(BOOT_KEY, OpenOnStart.boot(context)).commit();
    }
}
