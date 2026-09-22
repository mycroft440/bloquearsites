package com.mycroft.bloquearsites;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

public final class UrlExtractor {
    private static final int MAX_GENERIC_NODES = 350;
    private static final int MAX_SAMSUNG_NODES = 500;

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
        if (root == null) {
            if (isSamsungPackage(packageName)) {
                String samsung = extractSamsungFromNode(eventSource, null);
                if (samsung != null) return samsung;
            }
            return extractFromNodeIfAddressLike(eventSource);
        }

        BrowserProfile profile = BrowserProfiles.forPackage(packageName);
        if (profile != null) {
            String profiled = extractWithProfile(root, packageName, profile);
            if (profiled != null) return profiled;
        }

        if (isSamsungPackage(packageName)) {
            String samsung = extractSamsung(root, eventSource);
            if (samsung != null) return samsung;
        }

        String fromEvent = extractFromNodeIfAddressLike(eventSource);
        if (fromEvent != null) return fromEvent;

        return extractGeneric(root);
    }

    private String extractWithProfile(
            AccessibilityNodeInfo root,
            String packageName,
            BrowserProfile profile
    ) {
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
            String value = nodeText(node);
            if (DomainMatcher.extractHost(value) != null) return value;
        }
        return null;
    }

    private String extractSamsung(AccessibilityNodeInfo root, AccessibilityNodeInfo eventSource) {
        String fromEvent = extractSamsungFromNode(eventSource, root);
        if (fromEvent != null) return fromEvent;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;

        while (!queue.isEmpty() && visited < MAX_SAMSUNG_NODES) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;

            String candidate = extractSamsungFromNode(node, root);
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
            AccessibilityNodeInfo root
    ) {
        if (node == null) return null;

        String value = nodeText(node);
        if (DomainMatcher.extractHost(value) == null) return null;

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

        // Sem a raiz ainda aceitamos um EditText do próprio Samsung como último recurso.
        if (root == null) return true;

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
        String value = nodeText(node);
        return DomainMatcher.extractHost(value) != null ? value : null;
    }

    private String extractGeneric(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;

        while (!queue.isEmpty() && visited < MAX_GENERIC_NODES) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;

            if (hasAddressLikeId(node)) {
                String value = nodeText(node);
                if (DomainMatcher.extractHost(value) != null) {
                    return value;
                }
            }

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return null;
    }

    private boolean hasSamsungAddressLikeId(AccessibilityNodeInfo node) {
        String id = node.getViewIdResourceName();
        if (id == null) return false;

        String lower = id.toLowerCase(Locale.ROOT);
        for (String marker : SAMSUNG_ID_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
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

    private boolean isSamsungPackage(String packageName) {
        return SAMSUNG_PACKAGE.equals(packageName) || SAMSUNG_BETA_PACKAGE.equals(packageName);
    }

    private String nodeText(AccessibilityNodeInfo node) {
        if (node == null) return null;

        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            return text.toString().trim();
        }

        CharSequence description = node.getContentDescription();
        if (description != null && description.length() > 0) {
            return description.toString().trim();
        }
        return null;
    }
}
