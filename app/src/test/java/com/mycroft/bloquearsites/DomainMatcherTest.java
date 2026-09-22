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
    public void normalizesInternationalizedDomains() {
        assertEquals("xn--bcher-kva.de", DomainMatcher.normalizeBlockedInput("https://bücher.de"));
    }
}
