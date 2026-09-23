package com.mycroft.bloquearsites;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class IdentificationConfirmationTest {
    private static final String BROWSER = "com.example.fork";
    private static final long WAIT = IdentificationConfirmation.CONFIRM_AFTER_MS;

    @Test
    public void firstReadIsNotEnough() {
        IdentificationConfirmation confirmation = new IdentificationConfirmation();

        assertFalse(confirmation.onRead(BROWSER, "Chromium", 1_000L));
        assertTrue(confirmation.isPending(BROWSER));
        assertFalse(confirmation.onRead(BROWSER, "Chromium", 1_000L + WAIT - 1));
    }

    @Test
    public void urlStillReadableAfterTheWaitConfirms() {
        IdentificationConfirmation confirmation = new IdentificationConfirmation();

        confirmation.onRead(BROWSER, "Chromium", 1_000L);

        assertTrue(confirmation.onRead(BROWSER, "Chromium", 1_000L + WAIT));
        assertFalse(confirmation.isPending(BROWSER));
    }

    @Test
    public void titleShownAfterLoadingRestartsTheCount() {
        IdentificationConfirmation confirmation = new IdentificationConfirmation();

        // URL durante o carregamento; depois a barra passa a mostrar o título.
        confirmation.onRead(BROWSER, "Chromium", 1_000L);
        confirmation.reset(BROWSER);

        assertFalse(confirmation.isPending(BROWSER));
        assertFalse(confirmation.onRead(BROWSER, "Chromium", 1_000L + WAIT));
    }

    @Test
    public void anotherFamilyStartsANewCount() {
        IdentificationConfirmation confirmation = new IdentificationConfirmation();

        confirmation.onRead(BROWSER, "Chromium", 1_000L);

        assertFalse(confirmation.onRead(BROWSER, "Opera", 1_000L + WAIT));
        assertTrue(confirmation.onRead(BROWSER, "Opera", 1_000L + 2 * WAIT));
    }

    @Test
    public void browsersAreConfirmedSeparately() {
        IdentificationConfirmation confirmation = new IdentificationConfirmation();

        confirmation.onRead(BROWSER, "Chromium", 1_000L);

        assertFalse(confirmation.onRead("com.example.other", "Chromium", 1_000L + WAIT));
        assertTrue(confirmation.onRead(BROWSER, "Chromium", 1_000L + WAIT));
    }

    @Test
    public void browserOrAppUpdateChangesTheVerifiedVersion() {
        String current = VerifiedBrowsers.version(100L, 200L);

        assertEquals(current, VerifiedBrowsers.version(100L, 200L));
        assertNotEquals(current, VerifiedBrowsers.version(101L, 200L));
        assertNotEquals(current, VerifiedBrowsers.version(100L, 201L));
    }
}
