package com.mycroft.bloquearsites;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    public void normalizedListMatchesLikeTheFullComparison() {
        List<String> blocked = Arrays.asList("example.com", "instagram.com", "bücher.de", "co.uk");
        Set<String> normalized = new HashSet<>();
        for (String domain : blocked) normalized.add(DomainMatcher.normalizeBlockedInput(domain));

        String[] urls = {
                "https://news.example.com/a",
                "https://example.com",
                "https://evil-example.com",
                "example.com.evil.net",
                "\u200Em.instagram.com",
                "https://www.bücher.de/buch",
                "https://bbc.co.uk",
                "https://google.com",
                "pesquisa sem url",
                "chrome://settings"
        };
        for (String url : urls) {
            assertEquals(
                    url,
                    DomainMatcher.findMatchedDomain(url, blocked) != null,
                    DomainMatcher.findMatchedDomainNormalized(url, normalized) != null
            );
        }
        assertEquals(
                "example.com",
                DomainMatcher.findMatchedDomainNormalized("https://a.b.example.com", normalized)
        );
        assertNull(
                DomainMatcher.findMatchedDomainNormalized("https://example.com", new HashSet<>())
        );
    }

    @Test
    public void normalizesInternationalizedDomains() {
        assertEquals("xn--bcher-kva.de", DomainMatcher.normalizeBlockedInput("https://bücher.de"));
    }
}
