package com.mycroft.bloquearsites;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class FirefoxToolbarTextTest {
    @Test
    public void readsUrlFromComposeToolbarDescription() {
        // Formato do Firefox atual: "${title ?: ""} $url. $hint", com título nulo na aba normal.
        assertHost("instagram.com", " instagram.com. Pesquisar ou digitar endereço");
        assertHost("instagram.com", " www.instagram.com/reels/. Search or enter address");
    }

    @Test
    public void prefersUrlOverDomainsInPageTitle() {
        assertHost(
                "m.facebook.com",
                "Bem-vindo ao example.com m.facebook.com/home. Pesquisar ou digitar endereço"
        );
    }

    @Test
    public void readsUrlAfterCachedPageIndicator() {
        assertHost("youtube.com", " Página em cache | youtube.com/watch. Pesquisar ou digitar endereço");
    }

    @Test
    public void readsPlainUrlFromLegacyViewToolbar() {
        assertHost("example.com", "https://www.example.com/path?q=1");
        assertHost("example.com", "example.com");
    }

    @Test
    public void ignoresHintOnlyAndEmptyLabels() {
        assertNull(FirefoxToolbarText.findUrl(null));
        assertNull(FirefoxToolbarText.findUrl("   "));
        assertNull(FirefoxToolbarText.findUrl("Pesquisar ou digitar endereço"));
        assertNull(FirefoxToolbarText.findUrl("Search or enter address"));
    }

    @Test
    public void ignoresInternalPages() {
        assertNull(FirefoxToolbarText.findUrl(" about:blank. Pesquisar ou digitar endereço"));
    }

    private static void assertHost(String expectedHost, String label) {
        assertEquals(expectedHost, DomainMatcher.extractHost(FirefoxToolbarText.findUrl(label)));
    }
}
