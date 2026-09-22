package com.mycroft.bloquearsites;

import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/** Perfil do Firefox, incluindo a recuperação da janela após a capa receber o foco. */
public final class FirefoxProfile {
    static final BrowserProfile PROFILE = new BrowserProfile(
            new String[]{
                    "org.mozilla.firefox",
                    "org.mozilla.firefox_beta",
                    "org.mozilla.fenix",
                    "org.torproject.torbrowser"
            },
            "mozac_browser_toolbar_url_view",
            "mozac_browser_toolbar_edit_url_view"
    );

    private FirefoxProfile() {}

    public static boolean matchesPackage(String packageName) {
        return PROFILE.matchesPackage(packageName);
    }

    public static AccessibilityNodeInfo resolveExtractionRoot(
            String packageName,
            int eventWindowId,
            AccessibilityNodeInfo activeRoot,
            List<AccessibilityWindowInfo> windows
    ) {
        if (!matchesPackage(packageName)) return activeRoot;
        if (matchesEventWindow(activeRoot, packageName, eventWindowId)) return activeRoot;

        // getRootInActiveWindow pode continuar apontando para a capa durante a transição.
        // Só usa a primeira janela de aplicativo: não lê um Firefox encoberto por outro app.
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = window.getRoot();
            return matchesEventWindow(root, packageName, eventWindowId) ? root : null;
        }
        return null;
    }

    private static boolean matchesEventWindow(
            AccessibilityNodeInfo root,
            String packageName,
            int eventWindowId
    ) {
        if (root == null || root.getPackageName() == null
                || !packageName.contentEquals(root.getPackageName())) {
            return false;
        }
        int rootWindowId = root.getWindowId();
        return eventWindowId < 0 || rootWindowId < 0 || eventWindowId == rootWindowId;
    }
}
