package com.mycroft.bloquearsites;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class BrowserProfilesTest {
    @Test
    public void firefoxClassicUsesIndependentProfile() {
        BrowserProfile classic = BrowserProfiles.forPackage("org.mozilla.firefox");
        BrowserProfile beta = BrowserProfiles.forPackage("org.mozilla.firefox_beta");

        assertNotNull(classic);
        assertNotNull(beta);
        assertNotSame(classic, beta);

        assertEquals(
                Arrays.asList(
                        "url_edit_text",
                        "url_bar_title",
                        "mozac_browser_toolbar_edit_url_view",
                        "mozac_browser_toolbar_url_view"
                ),
                classic.getAddressViewIds()
        );

        assertEquals(
                Arrays.asList(
                        "mozac_browser_toolbar_url_view",
                        "mozac_browser_toolbar_edit_url_view"
                ),
                beta.getAddressViewIds()
        );
    }

    @Test
    public void firefoxFamilySharesFirefoxHandling() {
        assertTrue(BrowserProfiles.isFirefox("org.mozilla.firefox"));
        assertTrue(BrowserProfiles.isFirefox("org.mozilla.firefox_beta"));
        assertTrue(BrowserProfiles.isFirefox("org.mozilla.fenix"));
        assertTrue(BrowserProfiles.isFirefox("org.torproject.torbrowser"));

        assertFalse(BrowserProfiles.isFirefox("com.android.chrome"));
        assertFalse(BrowserProfiles.isFirefox("com.sec.android.app.sbrowser"));
        assertFalse(BrowserProfiles.isFirefox(null));
    }

    @Test
    public void chromiumFamilyExcludesOtherEngines() {
        assertTrue(BrowserProfiles.isChromium("com.android.chrome"));
        assertTrue(BrowserProfiles.isChromium("com.brave.browser"));
        assertTrue(BrowserProfiles.isChromium("com.microsoft.emmx"));

        assertFalse(BrowserProfiles.isChromium("org.mozilla.firefox"));
        assertFalse(BrowserProfiles.isChromium("com.sec.android.app.sbrowser"));
        assertFalse(BrowserProfiles.isChromium(null));
    }
}
