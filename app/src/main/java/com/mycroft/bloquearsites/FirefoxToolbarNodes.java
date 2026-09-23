package com.mycroft.bloquearsites;

import android.view.accessibility.AccessibilityNodeInfo;

/**
 * testTags da toolbar em Jetpack Compose da família Firefox.
 *
 * Elas são expostas como resource-id sem prefixo de pacote, e findAccessibilityNodeInfosByViewId
 * não encontra nós virtuais do Compose; por isso são buscadas percorrendo a árvore (NodeSearch).
 */
final class FirefoxToolbarNodes {
    // URL exibida (somente leitura) e o texto interno dela.
    static final String URL_BOX_TAG = "ADDRESSBAR_URL_BOX";
    static final String URL_TAG = "ADDRESSBAR_URL";
    // Campo de edição aberto ao tocar na URL.
    static final String SEARCH_BOX_TAG = "ADDRESSBAR_SEARCH_BOX";

    private FirefoxToolbarNodes() {}

    static boolean hasTag(AccessibilityNodeInfo node, String tag) {
        String id = node.getViewIdResourceName();
        return id != null && (id.equals(tag) || id.endsWith("/" + tag));
    }
}
