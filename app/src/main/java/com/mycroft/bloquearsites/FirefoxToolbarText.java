package com.mycroft.bloquearsites;

final class FirefoxToolbarText {
    private FirefoxToolbarText() {}

    /**
     * Extrai a URL exibida pela toolbar do Firefox.
     *
     * A toolbar em Compose (ADDRESSBAR_URL_BOX) não expõe texto: a URL aparece somente na
     * contentDescription, no formato "<título> <url>. <dica da barra>". A dica e o título são
     * localizados, então procuramos o último token com cara de domínio, que é sempre a URL.
     */
    static String findUrl(CharSequence label) {
        if (label == null) return null;

        String value = label.toString().trim();
        if (value.isEmpty()) return null;

        // Toolbars antigas (View) expõem apenas a URL no texto do nó.
        if (DomainMatcher.extractHost(value) != null) return value;

        String[] tokens = value.split("\\s+");
        for (int i = tokens.length - 1; i >= 0; i--) {
            String token = trimPunctuation(tokens[i]);
            if (token.indexOf('.') < 0) continue;
            if (DomainMatcher.extractHost(token) != null) return token;
        }
        return null;
    }

    private static String trimPunctuation(String token) {
        int start = 0;
        int end = token.length();
        while (start < end && !Character.isLetterOrDigit(token.charAt(start))) start++;
        while (end > start && !Character.isLetterOrDigit(token.charAt(end - 1))) end--;
        return token.substring(start, end);
    }
}
