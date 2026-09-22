package com.mycroft.bloquearsites;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

public final class UrlExtractor {
    private static final int MAX_GENERIC_NODES = 350;

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

    public String extract(AccessibilityNodeInfo root, AccessibilityNodeInfo eventSource, String packageName) {
        if (root == null) return extractFromNodeIfAddressLike(eventSource);

        BrowserProfile profile = BrowserProfiles.forPackage(packageName);
        if (profile != null) {
            String profiled = extractWithProfile(root, packageName, profile);
            if (profiled != null) return profiled;
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
                // Alguns navegadores/forks não expõem IDs estáveis; o fallback genérico cobre esse caso.
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

    private boolean hasAddressLikeId(AccessibilityNodeInfo node) {
        String id = node.getViewIdResourceName();
        if (id == null) return false;

        String lower = id.toLowerCase(Locale.ROOT);
        for (String marker : GENERIC_ID_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
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
