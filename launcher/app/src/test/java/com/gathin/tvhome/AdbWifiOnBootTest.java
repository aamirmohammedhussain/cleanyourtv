package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdbWifiOnBootTest {
    @Test public void aNewBootGetsItsTurn() {
        assertTrue(AdbWifiOnBoot.due(12, 11));
        assertTrue(AdbWifiOnBoot.due(1, -1));   // nothing recorded yet
    }

    @Test public void aHomeRestartInTheSameBootLeavesItAlone() {
        // Switched off by hand after the first Home of this boot: a second Home must not switch it back on.
        assertFalse(AdbWifiOnBoot.due(12, 12));
    }

    @Test public void noBootCountMeansNothingIsTouched() {
        assertFalse(AdbWifiOnBoot.due(-1, -1));
        assertFalse(AdbWifiOnBoot.due(-1, 12));
    }

    @Test public void itWaitsForWifiForTwoMinutesAtMost() {
        // Home starts about 2 s before Wi-Fi on the Wanbo X5 Pro's stick; Android undoes a write made before it.
        assertTrue(AdbWifiOnBoot.keepWaiting(false, 0));
        assertTrue(AdbWifiOnBoot.keepWaiting(false, AdbWifiOnBoot.WIFI_WAIT_MS - AdbWifiOnBoot.POLL_MS));
        assertFalse(AdbWifiOnBoot.keepWaiting(false, AdbWifiOnBoot.WIFI_WAIT_MS));
        assertFalse(AdbWifiOnBoot.keepWaiting(true, 0));
        assertEquals(120_000L, AdbWifiOnBoot.WIFI_WAIT_MS);
    }

    @Test public void aWriteThatHeldIsTheEnd() {
        assertEquals(AdbWifiOnBoot.DONE, AdbWifiOnBoot.afterWrite(true, 1));
        assertEquals(AdbWifiOnBoot.DONE, AdbWifiOnBoot.afterWrite(true, AdbWifiOnBoot.ATTEMPTS));
    }

    @Test public void aWriteAndroidUndidIsTriedThreeTimesInAll() {
        assertEquals(3, AdbWifiOnBoot.ATTEMPTS);
        assertEquals(AdbWifiOnBoot.AGAIN, AdbWifiOnBoot.afterWrite(false, 1));
        assertEquals(AdbWifiOnBoot.AGAIN, AdbWifiOnBoot.afterWrite(false, 2));
        assertEquals(AdbWifiOnBoot.GIVE_UP, AdbWifiOnBoot.afterWrite(false, 3));
    }

    @Test public void theSettingIsAndroidsWirelessDebuggingSwitch() {
        // Settings.Global.ADB_WIFI_ENABLED is hidden from the SDK; the name is the one `settings put global` takes.
        assertEquals("adb_wifi_enabled", AdbWifiOnBoot.ADB_WIFI_ENABLED);
    }
}
