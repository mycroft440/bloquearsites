package com.mycroft.bloquearsites;

import java.net.IDN;
import java.net.URI;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;

public final class DomainMatcher {
    private DomainMatcher() {}

    public static String normalizeBlockedInput(String raw) {
        return extractHost(raw);
    }

    public static String extractHost(String raw) {
        if (raw == null) return null;

        String value = removeFormatCharacters(raw).trim();
        if (value.isEmpty() || containsWhitespace(value)) return null;

        value = value
                .replace('\u3002', '.')
                .replace('\uFF0E', '.')
                .replace('\uFF61', '.');

        String lower = value.toLowerCase(Locale.ROOT);
        if (hasExplicitScheme(lower)
                && !lower.startsWith("http://")
                && !lower.startsWith("https://")) {
            return null;
        }

        String parseable = value;
        if (parseable.startsWith("//")) {
            parseable = "https:" + parseable;
        } else if (!hasExplicitScheme(parseable)) {
            parseable = "https://" + parseable;
        }

        String host = null;
        try {
            host = new URI(parseable).getHost();
        } catch (Exception ignored) {
        }

        if (host == null || host.isEmpty()) {
            host = fallbackAuthorityHost(parseable);
        }
        if (host == null || host.isEmpty()) return null;

        host = host.trim().toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.startsWith("www.") && host.length() > 4) {
            host = host.substring(4);
        }

        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }

        if (!host.contains(":")) {
            try {
                host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        if (host.isEmpty()) return null;
        if (!host.contains(".") && !host.contains(":") && !"localhost".equals(host)) {
            return null;
        }
        return host;
    }

    public static String findMatchedDomain(String rawUrl, Collection<String> blockedDomains) {
        String host = extractHost(rawUrl);
        if (host == null || blockedDomains == null || blockedDomains.isEmpty()) return null;

        for (String blocked : blockedDomains) {
            String normalized = normalizeBlockedInput(blocked);
            if (normalized == null) continue;
            if (host.equals(normalized) || host.endsWith("." + normalized)) {
                return normalized;
            }
        }
        return null;
    }

    /**
     * Igual a findMatchedDomain, para uma lista já normalizada: consulta o host e cada domínio
     * acima dele (m.example.com, example.com, com), sem percorrer a lista.
     */
    public static String findMatchedDomainNormalized(String rawUrl, Set<String> normalizedDomains) {
        String host = extractHost(rawUrl);
        if (host == null || normalizedDomains == null || normalizedDomains.isEmpty()) return null;

        String candidate = host;
        while (true) {
            if (normalizedDomains.contains(candidate)) return candidate;
            int dot = candidate.indexOf('.');
            if (dot < 0) return null;
            candidate = candidate.substring(dot + 1);
        }
    }

    private static boolean hasExplicitScheme(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0) return false;

        for (int i = 0; i < colon; i++) {
            char c = value.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '+' || c == '-' || c == '.';
            if (!valid) return false;
        }

        if (colon == 1 && Character.isLetter(value.charAt(0))) {
            return value.regionMatches(colon, "://", 0, 3);
        }

        String scheme = value.substring(0, colon).toLowerCase(Locale.ROOT);
        if ("http".equals(scheme) || "https".equals(scheme)) return true;

        return colon + 2 < value.length()
                && value.charAt(colon + 1) == '/'
                && value.charAt(colon + 2) == '/';
    }

    private static String fallbackAuthorityHost(String parseable) {
        int scheme = parseable.indexOf("://");
        if (scheme < 0) return null;

        int start = scheme + 3;
        int end = parseable.length();
        for (char separator : new char[]{'/', '?', '#'}) {
            int index = parseable.indexOf(separator, start);
            if (index >= 0 && index < end) end = index;
        }

        if (start >= end) return null;
        String authority = parseable.substring(start, end);

        int at = authority.lastIndexOf('@');
        if (at >= 0 && at + 1 < authority.length()) {
            authority = authority.substring(at + 1);
        }

        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            return close > 0 ? authority.substring(0, close + 1) : null;
        }

        int colon = authority.lastIndexOf(':');
        if (colon > 0 && authority.indexOf(':') == colon) {
            authority = authority.substring(0, colon);
        }
        return authority;
    }

    /**
     * Remove caracteres invisíveis de formatação (categoria Unicode Cf), como a marca U+200E que o
     * Samsung Internet coloca antes do domínio na barra. Eles quebram a conversão IDN do host.
     */
    private static String removeFormatCharacters(String value) {
        StringBuilder cleaned = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.getType(c) == Character.FORMAT) {
                if (cleaned == null) cleaned = new StringBuilder(value.substring(0, i));
            } else if (cleaned != null) {
                cleaned.append(c);
            }
        }
        return cleaned == null ? value : cleaned.toString();
    }

    private static boolean containsWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) return true;
        }
        return false;
    }
}
