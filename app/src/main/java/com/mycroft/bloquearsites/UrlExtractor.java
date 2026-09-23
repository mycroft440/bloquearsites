package com.mycroft.bloquearsites;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

public final class UrlExtractor {
    private static final int MAX_GENERIC_NODES = 350;
    private static final int MAX_SAMSUNG_NODES = 500;
    private static final int MAX_FIREFOX_NODES = 800;
    private static final int MAX_SAMSUNG_SOURCE_ANCESTORS = 12;
    private static final int MAX_DIAGNOSTIC_NODES = 180;
    private static final int MAX_DIAGNOSTIC_CANDIDATES = 10;

    private static final String FIREFOX_CLASSIC_PACKAGE = "org.mozilla.firefox";
    private static final String SAMSUNG_PACKAGE = "com.sec.android.app.sbrowser";
    private static final String SAMSUNG_BETA_PACKAGE = "com.sec.android.app.sbrowser.beta";

    private static final String[] GENERIC_ID_MARKERS = {
            "url_bar",
            "url_field",
            "toolbar_url",
            "edit_url",
            "address_bar",
            "addressbar",
            "omnibar",
            "location_bar_edit"
    };

    // Toolbar em View (Fennec e Firefox anteriores ao redesign): IDs reais com prefixo do pacote.
    private static final String[] FIREFOX_VIEW_DISPLAY_IDS = {
            "url_bar_title",
            "mozac_browser_toolbar_url_view"
    };

    // Toolbar em Jetpack Compose (Firefox atual): testTags expostas como resource-id, sem prefixo.
    private static final String[] FIREFOX_COMPOSE_DISPLAY_TAGS = {
            "ADDRESSBAR_URL_BOX",
            "ADDRESSBAR_URL"
    };

    private static final String[] SAMSUNG_ID_MARKERS = {
            "location_bar",
            "location__bar",
            "locationbar",
            "location_edit",
            "location_text",
            "url_bar",
            "urlbar",
            "url_text",
            "url_view",
            "address_bar",
            "addressbar",
            "address_text",
            "search_url",
            "search_bar",
            "searchbar",
            "omnibar",
            "omnibox",
            "toolbar_url"
    };

    private static final String[] SAMSUNG_SEMANTIC_MARKERS = {
            "url",
            "address",
            "endereco",
            "endereço",
            "search",
            "pesquisar",
            "keyword",
            "palavra-chave"
    };

    public String extract(AccessibilityNodeInfo root, AccessibilityNodeInfo eventSource, String packageName) {
        if (isSamsungPackage(packageName)) {
            return extractSamsungSourceFirst(root, eventSource, packageName);
        }

        if (isFirefoxClassicPackage(packageName)) {
            return extractFirefoxDisplayedUrl(root, packageName);
        }

        if (root == null) {
            return extractFromNodeIfAddressLike(eventSource);
        }

        BrowserProfile profile = BrowserProfiles.forPackage(packageName);
        if (profile != null) {
            String profiled = extractWithProfile(root, packageName, profile);
            if (profiled != null) return profiled;
        }

        String fromEvent = extractFromNodeIfAddressLike(eventSource);
        if (fromEvent != null) return fromEvent;

        return extractGeneric(root);
    }

    /**
     * Lê a URL carregada na toolbar de exibição do Firefox. O campo de edição é ignorado de
     * propósito: texto digitado e sugestões não representam a página navegada.
     */
    String extractFirefoxDisplayedUrl(AccessibilityNodeInfo root, String packageName) {
        if (root == null || packageName == null) return null;

        int expectedWindowId = root.getWindowId();

        for (String idName : FIREFOX_VIEW_DISPLAY_IDS) {
            String exactId = packageName + ":id/" + idName;
            try {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(exactId);
                if (nodes == null) continue;

                for (AccessibilityNodeInfo node : nodes) {
                    String value = firefoxDisplayValue(node, packageName, expectedWindowId);
                    if (value != null) return value;
                }
            } catch (RuntimeException ignored) {
                // O Firefox pode recriar a toolbar durante a leitura; a próxima tentativa cobre isso.
            }
        }

        // findAccessibilityNodeInfosByViewId só encontra Views reais, não os nós virtuais do
        // Compose. A toolbar atual é localizada percorrendo a árvore pelo resource-id exposto.
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;

        while (!queue.isEmpty() && visited < MAX_FIREFOX_NODES) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;

            if (isFirefoxWebContent(node)) continue;

            if (hasFirefoxComposeDisplayTag(node)) {
                String value = firefoxDisplayValue(node, packageName, expectedWindowId);
                if (value != null) return value;
            }

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }

        return null;
    }

    private String firefoxDisplayValue(
            AccessibilityNodeInfo node,
            String packageName,
            int expectedWindowId
    ) {
        if (node == null || !node.isVisibleToUser()) return null;

        CharSequence nodePackage = node.getPackageName();
        if (nodePackage == null || !packageName.equals(nodePackage.toString())) return null;

        int nodeWindowId = node.getWindowId();
        if (expectedWindowId >= 0 && nodeWindowId >= 0 && nodeWindowId != expectedWindowId) {
            return null;
        }

        String fromText = FirefoxToolbarText.findUrl(node.getText());
        if (fromText != null) return fromText;
        return FirefoxToolbarText.findUrl(node.getContentDescription());
    }

    private boolean hasFirefoxComposeDisplayTag(AccessibilityNodeInfo node) {
        String id = node.getViewIdResourceName();
        if (id == null) return false;

        for (String tag : FIREFOX_COMPOSE_DISPLAY_TAGS) {
            if (id.equals(tag) || id.endsWith("/" + tag)) return true;
        }
        return false;
    }

    private boolean isFirefoxWebContent(AccessibilityNodeInfo node) {
        CharSequence className = node.getClassName();
        if (className == null) return false;

        // O GeckoView expõe a página como "android.webkit.WebView". Links e textos do conteúdo
        // nunca podem ser confundidos com a URL navegada.
        String name = className.toString();
        return "android.webkit.WebView".equals(name) || name.startsWith("org.mozilla.geckoview.");
    }

    private String extractSamsungSourceFirst(
            AccessibilityNodeInfo root,
            AccessibilityNodeInfo eventSource,
            String packageName
    ) {
        BrowserProfile profile = BrowserProfiles.forPackage(packageName);

        // Samsung Internet pode expor a barra na subárvore/ancestrais da fonte do evento
        // antes de ela aparecer de forma estável em getRootInActiveWindow().
        if (eventSource != null) {
            String fromSource = extractSamsungFromSourceChain(eventSource, packageName, profile);
            if (fromSource != null) return fromSource;
        }

        if (root == null) return null;

        if (profile != null) {
            String profiled = extractWithProfile(root, packageName, profile);
            if (profiled != null) return profiled;
        }

        return extractSamsungTree(root);
    }

    private String extractSamsungFromSourceChain(
            AccessibilityNodeInfo eventSource,
            String packageName,
            BrowserProfile profile
    ) {
        AccessibilityNodeInfo current = eventSource;

        for (int depth = 0;
             current != null && depth < MAX_SAMSUNG_SOURCE_ANCESTORS;
             depth++) {

            if (profile != null) {
                String profiled = extractWithProfile(current, packageName, profile);
                if (profiled != null) return profiled;
            }

            String directCandidate = extractSamsungFromNode(current, null, false);
            if (directCandidate != null) return directCandidate;

            try {
                current = current.getParent();
            } catch (RuntimeException ignored) {
                current = null;
            }
        }

        return null;
    }

    private String extractWithProfile(
            AccessibilityNodeInfo root,
            String packageName,
            BrowserProfile profile
    ) {
        if (root == null || profile == null) return null;

        for (String idName : profile.getAddressViewIds()) {
            String exactId = packageName + ":id/" + idName;
            try {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(exactId);
                String value = firstValidText(nodes);
                if (value != null) return value;
            } catch (RuntimeException ignored) {
                // Alguns navegadores/forks não expõem IDs estáveis; os fallbacks cobrem esse caso.
            }
        }
        return null;
    }

    private String firstValidText(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return null;
        for (AccessibilityNodeInfo node : nodes) {
            String value = firstUrlLikeValue(node);
            if (value != null) return value;
        }
        return null;
    }

    private String extractSamsungTree(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;

        while (!queue.isEmpty() && visited < MAX_SAMSUNG_NODES) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;

            String candidate = extractSamsungFromNode(node, root, true);
            if (candidate != null) return candidate;

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return null;
    }

    private String extractSamsungFromNode(
            AccessibilityNodeInfo node,
            AccessibilityNodeInfo root,
            boolean allowGeometryFallback
    ) {
        if (node == null) return null;

        String value = firstUrlLikeValue(node);
        if (value == null) return null;

        if (hasSamsungAddressLikeId(node)) {
            return value;
        }

        CharSequence nodePackage = node.getPackageName();
        if (nodePackage == null || !isSamsungPackage(nodePackage.toString())) {
            return null;
        }

        if (hasSamsungAddressSemantics(node)) {
            return value;
        }

        if (!allowGeometryFallback) return null;

        CharSequence className = node.getClassName();
        String classNameString = className == null ? "" : className.toString();
        if (classNameString.endsWith("EditText") && isSamsungAddressBarGeometry(node, root)) {
            return value;
        }

        return null;
    }

    private boolean isSamsungAddressBarGeometry(
            AccessibilityNodeInfo node,
            AccessibilityNodeInfo root
    ) {
        if (node == null || node.isPassword() || !node.isVisibleToUser()) return false;

        if (root == null) return false;

        Rect rootBounds = new Rect();
        Rect nodeBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        node.getBoundsInScreen(nodeBounds);

        if (rootBounds.width() <= 0 || rootBounds.height() <= 0
                || nodeBounds.width() <= 0 || nodeBounds.height() <= 0) {
            return false;
        }

        boolean wideEnough = nodeBounds.width() >= Math.round(rootBounds.width() * 0.35f);
        int centerY = nodeBounds.centerY();
        int edgeBand = Math.round(rootBounds.height() * 0.30f);
        boolean nearTop = centerY <= rootBounds.top + edgeBand;
        boolean nearBottom = centerY >= rootBounds.bottom - edgeBand;

        return wideEnough && (nearTop || nearBottom);
    }

    private boolean hasSamsungAddressSemantics(AccessibilityNodeInfo node) {
        return containsSamsungSemanticMarker(node.getContentDescription())
                || containsSamsungSemanticMarker(node.getHintText());
    }

    private boolean containsSamsungSemanticMarker(CharSequence value) {
        if (value == null || value.length() == 0) return false;

        String lower = value.toString().toLowerCase(Locale.ROOT);
        for (String marker : SAMSUNG_SEMANTIC_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    private String extractFromNodeIfAddressLike(AccessibilityNodeInfo node) {
        if (node == null || !hasAddressLikeId(node)) return null;
        return firstUrlLikeValue(node);
    }

    private String extractGeneric(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;

        while (!queue.isEmpty() && visited < MAX_GENERIC_NODES) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;

            if (hasAddressLikeId(node)) {
                String value = firstUrlLikeValue(node);
                if (value != null) return value;
            }

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return null;
    }

    String describeFirefoxToolbarCandidates(AccessibilityNodeInfo root) {
        if (root == null) return "<sem-root>";

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        int candidates = 0;
        StringBuilder summary = new StringBuilder();

        while (!queue.isEmpty()
                && visited < MAX_FIREFOX_NODES
                && candidates < MAX_DIAGNOSTIC_CANDIDATES) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;

            if (isFirefoxWebContent(node)) continue;

            String id = node.getViewIdResourceName();
            if (id != null && isFirefoxToolbarLikeId(id)) {
                CharSequence className = node.getClassName();
                if (summary.length() > 0) summary.append("; ");
                summary.append("id=").append(id)
                        .append(",class=").append(className == null ? "" : className)
                        .append(",window=").append(node.getWindowId())
                        .append(",visible=").append(node.isVisibleToUser())
                        .append(",textHost=").append(hostOrDash(node.getText()))
                        .append(",descHost=").append(hostOrDash(node.getContentDescription()));
                candidates++;
            }

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }

        if (summary.length() == 0) {
            return "<nenhum-candidato-em-" + visited + "-nos>";
        }
        return summary.toString();
    }

    private boolean isFirefoxToolbarLikeId(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains("addressbar")
                || lower.contains("toolbar")
                || lower.contains("url");
    }

    private String hostOrDash(CharSequence value) {
        String host = DomainMatcher.extractHost(FirefoxToolbarText.findUrl(value));
        return host == null ? "-" : host;
    }

    String describeSamsungCandidates(AccessibilityNodeInfo root) {
        if (root == null) return "<sem-no>";

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        int candidates = 0;
        StringBuilder summary = new StringBuilder();

        while (!queue.isEmpty()
                && visited < MAX_DIAGNOSTIC_NODES
                && candidates < MAX_DIAGNOSTIC_CANDIDATES) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;

            String id = node.getViewIdResourceName();
            CharSequence className = node.getClassName();
            String classNameString = className == null ? "" : className.toString();

            boolean interestingId = id != null && isSamsungInterestingId(id);
            boolean editText = classNameString.endsWith("EditText");

            if (interestingId || editText) {
                if (summary.length() > 0) summary.append("; ");
                summary.append("id=").append(id == null ? "<sem-id>" : id)
                        .append(",class=").append(classNameString)
                        .append(",text=").append(hasText(node.getText()))
                        .append(",desc=").append(hasText(node.getContentDescription()))
                        .append(",hint=").append(hasText(node.getHintText()))
                        .append(",visible=").append(node.isVisibleToUser())
                        .append(",editable=").append(node.isEditable());
                candidates++;
            }

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }

        if (summary.length() == 0) {
            return "<nenhum-candidato-em-" + visited + "-nos>";
        }
        return summary.toString();
    }

    private boolean hasText(CharSequence value) {
        return value != null && value.length() > 0;
    }

    private boolean isSamsungInterestingId(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        for (String marker : SAMSUNG_ID_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    private boolean hasSamsungAddressLikeId(AccessibilityNodeInfo node) {
        String id = node.getViewIdResourceName();
        return id != null && isSamsungInterestingId(id);
    }

    private boolean hasAddressLikeId(AccessibilityNodeInfo node) {
        String id = node.getViewIdResourceName();
        if (id == null) return false;

        String lower = id.toLowerCase(Locale.ROOT);
        for (String marker : GENERIC_ID_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    private boolean isFirefoxClassicPackage(String packageName) {
        return FIREFOX_CLASSIC_PACKAGE.equals(packageName);
    }

    private boolean isSamsungPackage(String packageName) {
        return SAMSUNG_PACKAGE.equals(packageName) || SAMSUNG_BETA_PACKAGE.equals(packageName);
    }

    private String firstUrlLikeValue(AccessibilityNodeInfo node) {
        if (node == null) return null;

        String text = clean(node.getText());
        if (DomainMatcher.extractHost(text) != null) return text;

        String description = clean(node.getContentDescription());
        if (DomainMatcher.extractHost(description) != null) return description;

        return null;
    }

    private String clean(CharSequence value) {
        if (value == null || value.length() == 0) return null;
        String cleaned = value.toString().trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
