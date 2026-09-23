package com.mycroft.bloquearsites;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Localiza a barra de endereço pela estrutura da tela, para navegadores com IDs ofuscados (Via).
 *
 * A barra é um TextView/EditText baixo, encostado no topo ou na base da janela, dentro de uma
 * faixa larga, e fica fora do conteúdo da página. Na exibição, só vale o texto que é inteiro uma
 * URL ou um domínio: o título da página não identifica o site.
 */
final class ToolbarStructure {
    private static final float EDGE_BAND = 0.20f;
    private static final float MAX_HEIGHT = 0.14f;
    private static final float MIN_WIDTH = 0.30f;
    private static final float MIN_BAR_WIDTH = 0.60f;

    private ToolbarStructure() {}

    /** Campo da barra em edição (com foco), onde o usuário digita o endereço. */
    static AccessibilityNodeInfo findFocusedEditField(AccessibilityNodeInfo root, String packageName) {
        Rect rootBounds = boundsOf(root);
        return NodeSearch.findFirst(root, node ->
                node.isFocused()
                        && node.isEditable()
                        && isToolbarText(node, rootBounds, packageName));
    }

    /** Campo de edição da barra, com ou sem foco. */
    static AccessibilityNodeInfo findEditField(AccessibilityNodeInfo root, String packageName) {
        AccessibilityNodeInfo focused = findFocusedEditField(root, packageName);
        if (focused != null) return focused;

        Rect rootBounds = boundsOf(root);
        return NodeSearch.findFirst(root, node ->
                node.isEditable() && isToolbarText(node, rootBounds, packageName));
    }

    /** Texto da barra que mostra a URL ou o domínio da página. */
    static AccessibilityNodeInfo findUrlDisplay(AccessibilityNodeInfo root, String packageName) {
        Rect rootBounds = boundsOf(root);
        return NodeSearch.findFirst(root, node ->
                wholeUrl(node.getText()) != null && isToolbarText(node, rootBounds, packageName));
    }

    /** O texto inteiro do nó, quando ele é uma URL ou um domínio. */
    static String wholeUrl(CharSequence text) {
        if (text == null) return null;
        String value = text.toString().trim();
        return DomainMatcher.extractHost(value) == null ? null : value;
    }

    private static boolean isToolbarText(
            AccessibilityNodeInfo node,
            Rect rootBounds,
            String packageName
    ) {
        if (rootBounds == null || node.isPassword()) return false;
        if (!NodeSearch.isVisibleInPackage(node, packageName)) return false;

        CharSequence className = node.getClassName();
        String name = className == null ? "" : className.toString();
        if (!name.endsWith("TextView") && !name.endsWith("EditText")) return false;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return false;

        int bandHeight = Math.round(rootBounds.height() * EDGE_BAND);
        boolean nearEdge = bounds.centerY() <= rootBounds.top + bandHeight
                || bounds.centerY() >= rootBounds.bottom - bandHeight;
        boolean shallow = bounds.height() <= Math.round(rootBounds.height() * MAX_HEIGHT);
        if (!nearEdge || !shallow) return false;

        // O texto do domínio pode ser curto; nesse caso a faixa que o contém é que precisa ser larga.
        if (bounds.width() >= Math.round(rootBounds.width() * MIN_WIDTH)) return true;

        AccessibilityNodeInfo parent = node.getParent();
        if (parent == null) return false;
        Rect parentBounds = new Rect();
        parent.getBoundsInScreen(parentBounds);
        return parentBounds.width() >= Math.round(rootBounds.width() * MIN_BAR_WIDTH);
    }

    private static Rect boundsOf(AccessibilityNodeInfo root) {
        if (root == null) return null;
        Rect bounds = new Rect();
        root.getBoundsInScreen(bounds);
        return bounds.isEmpty() ? null : bounds;
    }
}
