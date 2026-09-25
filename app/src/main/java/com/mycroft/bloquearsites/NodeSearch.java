package com.mycroft.bloquearsites;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.function.Predicate;

/** Busca na árvore de acessibilidade do navegador, sempre fora do conteúdo da página. */
final class NodeSearch {
    static final int MAX_NODES = 800;

    private NodeSearch() {}

    /**
     * WebView e GeckoView expõem a página como "android.webkit.WebView". Links, textos e campos do
     * conteúdo nunca podem ser confundidos com a barra de endereço.
     */
    static boolean isWebContent(AccessibilityNodeInfo node) {
        CharSequence className = node.getClassName();
        if (className == null) return false;

        String name = className.toString();
        return "android.webkit.WebView".equals(name) || name.startsWith("org.mozilla.geckoview.");
    }

    /** Nó visível do próprio navegador (e não de outra janela ou app). */
    static boolean isVisibleInPackage(AccessibilityNodeInfo node, String packageName) {
        CharSequence nodePackage = node.getPackageName();
        return node.isVisibleToUser()
                && nodePackage != null
                && nodePackage.toString().equals(packageName);
    }

    /** Indica se a janela está mostrando uma página web (WebView ou GeckoView). */
    static boolean containsWebContent(AccessibilityNodeInfo root) {
        if (root == null) return false;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;

        while (!queue.isEmpty() && visited < MAX_NODES) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;

            if (isWebContent(node)) return true;

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return false;
    }

    /** Busca em largura que não entra no conteúdo da página. */
    static AccessibilityNodeInfo findFirst(
            AccessibilityNodeInfo root,
            Predicate<AccessibilityNodeInfo> matcher
    ) {
        AccessibilityNodeInfo[] found = {null};
        visit(root, node -> {
            if (!matcher.test(node)) return false;
            found[0] = node;
            return true;
        });
        return found[0];
    }

    /**
     * Percorre em largura os nós fora do conteúdo da página, até o visitante retornar true. Permite
     * várias buscas numa só passada pela árvore.
     */
    static void visit(AccessibilityNodeInfo root, Predicate<AccessibilityNodeInfo> visitor) {
        if (root == null) return;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;

        while (!queue.isEmpty() && visited < MAX_NODES) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;

            if (isWebContent(node)) continue;
            if (visitor.test(node)) return;

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
    }
}
