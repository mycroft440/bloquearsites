package com.mycroft.bloquearsites;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.function.Predicate;

/**
 * Localiza os nós da toolbar em Jetpack Compose do Firefox (e de Beta/Nightly/Tor).
 *
 * As testTags são expostas como resource-id sem prefixo de pacote, e findAccessibilityNodeInfosByViewId
 * não encontra nós virtuais do Compose; por isso a árvore é percorrida.
 */
final class FirefoxToolbarNodes {
    // URL exibida (somente leitura) e o texto interno dela.
    static final String URL_BOX_TAG = "ADDRESSBAR_URL_BOX";
    static final String URL_TAG = "ADDRESSBAR_URL";
    // Campo de edição aberto ao tocar na URL.
    static final String SEARCH_BOX_TAG = "ADDRESSBAR_SEARCH_BOX";

    static final int MAX_NODES = 800;

    private FirefoxToolbarNodes() {}

    static boolean hasTag(AccessibilityNodeInfo node, String tag) {
        String id = node.getViewIdResourceName();
        return id != null && (id.equals(tag) || id.endsWith("/" + tag));
    }

    /**
     * O GeckoView expõe a página como "android.webkit.WebView". Links, textos e campos do conteúdo
     * nunca podem ser confundidos com a barra de endereço.
     */
    static boolean isWebContent(AccessibilityNodeInfo node) {
        CharSequence className = node.getClassName();
        if (className == null) return false;

        String name = className.toString();
        return "android.webkit.WebView".equals(name) || name.startsWith("org.mozilla.geckoview.");
    }

    /** Busca em largura fora do conteúdo da página. */
    static AccessibilityNodeInfo findFirst(
            AccessibilityNodeInfo root,
            Predicate<AccessibilityNodeInfo> matcher
    ) {
        if (root == null) return null;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;

        while (!queue.isEmpty() && visited < MAX_NODES) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;

            if (isWebContent(node)) continue;
            if (matcher.test(node)) return node;

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return null;
    }
}
