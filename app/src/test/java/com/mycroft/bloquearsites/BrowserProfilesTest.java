package com.mycroft.bloquearsites;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;

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
}
