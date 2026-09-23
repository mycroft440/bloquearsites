package com.mycroft.bloquearsites;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Texto da página aberta no navegador, para o filtro de pornografia (AdultContentFilter).
 *
 * Só entra o texto do conteúdo web (títulos, resultados de busca, origem das imagens) e o que está
 * digitado em campos, como a busca feita. A leitura tem limite de nós: os resultados do topo
 * bastam, e a árvore de uma página grande custaria caro a cada conferência.
 */
final class PageText {
    private static final int MAX_NODES = 600;
    private static final int MAX_TEXT_LENGTH = 300;

    final List<String> texts = new ArrayList<>();
    final List<String> fields = new ArrayList<>();

    private PageText() {}

    static PageText collect(AccessibilityNodeInfo root) {
        PageText page = new PageText();
        if (root == null) return page;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        ArrayDeque<Boolean> insideWeb = new ArrayDeque<>();
        queue.add(root);
        insideWeb.add(false);
        int visited = 0;

        while (!queue.isEmpty() && visited < MAX_NODES) {
            AccessibilityNodeInfo node = queue.removeFirst();
            boolean web = insideWeb.removeFirst() || NodeSearch.isWebContent(node);
            visited++;

            try {
                if (node.isEditable() && !node.isPassword()) {
                    page.addTo(page.fields, node.getText());
                } else if (web) {
                    page.addTo(page.texts, node.getText());
                    page.addTo(page.texts, node.getContentDescription());
                }

                int childCount = node.getChildCount();
                for (int i = 0; i < childCount; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child == null) continue;
                    queue.addLast(child);
                    insideWeb.addLast(web);
                }
            } catch (RuntimeException ignored) {
                // A página pode mudar durante a leitura; a próxima conferência cobre isso.
            }
        }
        return page;
    }

    private void addTo(List<String> target, CharSequence value) {
        if (value == null || value.length() == 0) return;
        String text = value.toString().trim();
        if (text.isEmpty()) return;
        target.add(text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) : text);
    }
}
