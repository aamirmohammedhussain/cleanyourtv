package com.gathin.tvhome;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KeepKeyServicesTest {
    private static final String PKG = "com.gathin.tvhome";
    private static final String HOME = "com.gathin.tvhome.HomeKeyService";
    private static final String INPUT = "com.gathin.tvhome.InputKeyService";
    private static final List<String> BOTH = Arrays.asList(HOME, INPUT);
    private static final String BOTH_SHORT = "com.gathin.tvhome/.HomeKeyService:com.gathin.tvhome/.InputKeyService";
    private static final long MINUTE = 60_000L;

    @Test public void onlyTheTwoHelpersCount() {
        assertEquals(Arrays.asList("HomeKeyService", "InputKeyService"), KeepKeyServices.helpers(
                Arrays.asList("HomeKeyService", "GuardRevival", "InputKeyService", "com.gathin.tvhome/.HomeKeyService")));
        assertEquals(Collections.singletonList("InputKeyService"),
                KeepKeyServices.helpers(Arrays.asList(" InputKeyService ", "InputKeyService", "homekeyservice")));
        assertTrue(KeepKeyServices.helpers(Collections.emptyList()).isEmpty());
    }

    @Test public void theListsOrderIsKept() {
        assertEquals(Arrays.asList("InputKeyService", "HomeKeyService"),
                KeepKeyServices.helpers(Arrays.asList("InputKeyService", "HomeKeyService")));
    }

    @Test public void anEmptiedListGetsBothHelpersBack() {
        // What the Skyworth HP4609 stick (C3.2.5) leaves a few seconds after boot.
        assertEquals(BOTH, KeepKeyServices.missing("", PKG, BOTH));
        assertEquals(BOTH_SHORT, KeepKeyServices.withHelpers("", PKG, BOTH));
        assertEquals(BOTH, KeepKeyServices.missing(null, PKG, BOTH));
        assertEquals(BOTH_SHORT, KeepKeyServices.withHelpers(null, PKG, BOTH));
        assertEquals(BOTH, KeepKeyServices.missing("null", PKG, BOTH));
        assertEquals(BOTH_SHORT, KeepKeyServices.withHelpers("null", PKG, BOTH));
    }

    @Test public void otherServicesStayFirstInTheirOrder() {
        String others = "com.sdt.hdmi/.MyAccessibilityService:com.example.reader/com.example.reader.Reader";
        String list = others + ":com.gathin.tvhome/.HomeKeyService";
        List<String> lost = KeepKeyServices.missing(list, PKG, BOTH);
        assertEquals(Collections.singletonList(INPUT), lost);
        assertEquals(list + ":com.gathin.tvhome/.InputKeyService", KeepKeyServices.withHelpers(list, PKG, lost));
    }

    @Test public void theFullFormCountsAsOnTheList() {
        String list = "com.gathin.tvhome/com.gathin.tvhome.HomeKeyService:com.gathin.tvhome/.InputKeyService";
        assertTrue(KeepKeyServices.missing(list, PKG, BOTH).isEmpty());
    }

    @Test public void ourOwnWriteLeavesNothingMissing() {
        // The observer sees its own write too; that change must end without another one.
        assertTrue(KeepKeyServices.missing(KeepKeyServices.withHelpers("", PKG, BOTH), PKG, BOTH).isEmpty());
    }

    @Test public void onlyTheNamedHelpersAreChecked() {
        List<String> home = Collections.singletonList(HOME);
        assertTrue(KeepKeyServices.missing("com.gathin.tvhome/.HomeKeyService", PKG, home).isEmpty());
        assertTrue(KeepKeyServices.missing("", PKG, Collections.emptyList()).isEmpty());
    }

    @Test public void emptyEntriesAreDropped() {
        assertEquals("a.b/.C:" + BOTH_SHORT, KeepKeyServices.withHelpers(":a.b/.C::", PKG, BOTH));
    }

    @Test public void anotherApplicationIdIsItsOwnComponent() {
        // launcherApplicationId: the package changes, the classes stay in com.gathin.tvhome.
        String pkg = "com.example.home";
        assertEquals("com.example.home/com.gathin.tvhome.HomeKeyService", KeepKeyServices.shortName(pkg, HOME));
        assertEquals(Collections.singletonList(HOME),
                KeepKeyServices.missing("com.gathin.tvhome/.HomeKeyService", pkg, Collections.singletonList(HOME)));
        assertTrue(KeepKeyServices.missing("com.example.home/com.gathin.tvhome.HomeKeyService", pkg,
                Collections.singletonList(HOME)).isEmpty());
    }

    private static KeepKeyServices.Budget budget() {
        return new KeepKeyServices.Budget(5, 10 * MINUTE, 30 * MINUTE);
    }

    @Test public void fiveReaddsInTenMinutesThenAPause() {
        KeepKeyServices.Budget budget = budget();
        for (int i = 0; i < 5; i++) assertTrue(budget.take(i * MINUTE));
        assertFalse(budget.pausedAt(5 * MINUTE));
        assertFalse(budget.take(5 * MINUTE));
        assertTrue(budget.pausedAt(5 * MINUTE));
    }

    @Test public void nothingIsAllowedDuringThePause() {
        KeepKeyServices.Budget budget = budget();
        for (int i = 0; i < 5; i++) budget.take(0);
        assertFalse(budget.take(0));                    // starts the pause
        assertFalse(budget.take(MINUTE));
        assertFalse(budget.take(30 * MINUTE - 1));
        assertTrue(budget.pausedAt(30 * MINUTE - 1));
    }

    @Test public void afterThePauseItStartsAgainWithAFreshWindow() {
        // A Home process that lives for days must still repair a later change.
        KeepKeyServices.Budget budget = budget();
        for (int i = 0; i < 5; i++) budget.take(0);
        assertFalse(budget.take(0));
        assertFalse(budget.pausedAt(30 * MINUTE));
        for (int i = 0; i < 5; i++) assertTrue(budget.take(30 * MINUTE + i));
        assertFalse(budget.take(30 * MINUTE + 5));      // and the limit holds again
        assertTrue(budget.pausedAt(31 * MINUTE));
        assertTrue(budget.take(24 * 60 * MINUTE));      // a day later
    }

    @Test public void aFightStaysBounded() {
        // Firmware that clears the list every second for a day: never more than 5 per 10 minutes, nor per pause.
        KeepKeyServices.Budget budget = budget();
        int allowed = 0;
        for (long t = 0; t < 24 * 60 * MINUTE; t += 1000) if (budget.take(t)) allowed++;
        assertEquals(5 * 24 * 60 / 30, allowed);       // one burst of 5 per 30-minute pause
    }

    @Test public void aReaddOlderThanTheWindowNoLongerCounts() {
        KeepKeyServices.Budget budget = budget();
        for (int i = 0; i < 5; i++) assertTrue(budget.take(i * MINUTE));
        assertTrue(budget.take(10 * MINUTE));          // the one at 0 has left the window
        assertFalse(budget.take(10 * MINUTE + 30_000));   // 1, 2, 3, 4 and 10 are still in it
    }

    @Test public void readdsSpreadOutKeepGoing() {
        KeepKeyServices.Budget budget = budget();
        for (int i = 0; i < 50; i++) assertTrue(budget.take(i * 3 * MINUTE));
        assertFalse(budget.pausedAt(50 * 3 * MINUTE));
    }

    // config.xml, themes/README.md and docs/TROUBLESHOOTING.md promise these to the owner.
    @Test public void theSwitchUsesFiveInTenMinutes() {
        assertEquals(5, KeepKeyServices.MAX_READDS);
        assertEquals(10 * MINUTE, KeepKeyServices.WINDOW_MS);
        assertEquals(30 * MINUTE, KeepKeyServices.PAUSE_MS);
        assertEquals(Arrays.asList("HomeKeyService", "InputKeyService"), KeepKeyServices.HELPERS);
    }
}
