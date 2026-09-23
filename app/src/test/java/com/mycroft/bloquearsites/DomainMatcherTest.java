package com.mycroft.bloquearsites;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class DomainMatcherTest {
    @Test
    public void normalizesUrlToDomain() {
        assertEquals("example.com", DomainMatcher.normalizeBlockedInput("https://www.Example.com/path?q=1"));
    }

    @Test
    public void acceptsDomainWithPortAndPath() {
        assertEquals("example.com", DomainMatcher.normalizeBlockedInput("example.com:8443/test"));
    }

    @Test
    public void matchesSubdomainsButNotLookalikeDomains() {
        assertEquals(
                "example.com",
                DomainMatcher.findMatchedDomain("https://news.example.com/a", Arrays.asList("example.com"))
        );
        assertNull(
                DomainMatcher.findMatchedDomain("https://evil-example.com", Arrays.asList("example.com"))
        );
    }

    @Test
    public void ignoresInternalBrowserSchemesAndSearchText() {
        assertNull(DomainMatcher.extractHost("chrome://settings"));
        assertNull(DomainMatcher.extractHost("about:config"));
        assertNull(DomainMatcher.extractHost("pesquisa sem url"));
    }

    @Test
    public void ignoresInvisibleFormatCharacters() {
        // Samsung Internet exibe "\u200E" + domínio na barra de endereço.
        assertEquals("instagram.com", DomainMatcher.extractHost("\u200Einstagram.com"));
        assertEquals(
                "instagram.com",
                DomainMatcher.findMatchedDomain("\u200Em.instagram.com", Arrays.asList("instagram.com"))
        );
        assertEquals("example.com", DomainMatcher.extractHost("\u202Aexample.com\u202C"));
        assertNull(DomainMatcher.extractHost("\u200E"));
    }

    @Test
    public void normalizesInternationalizedDomains() {
        assertEquals("xn--bcher-kva.de", DomainMatcher.normalizeBlockedInput("https://bücher.de"));
    }
}
