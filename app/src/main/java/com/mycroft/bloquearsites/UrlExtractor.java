package com.mycroft.bloquearsites;

import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

public final class UrlExtractor {
    private static final int MAX_GENERIC_NODES = 350;
    private static final int MAX_DIAGNOSTIC_CANDIDATES = 10;

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

    /** Lê a URL da barra de endereço usando o método da família do navegador. */
    public String extract(AccessibilityNodeInfo root, AccessibilityNodeInfo eventSource, String packageName) {
        BrowserProfile profile = BrowserProfiles.forPackage(packageName);
        if (profile == null) return extractUnknown(root, eventSource);

        switch (profile.getMethod()) {
            case FIREFOX_TOOLBAR:
                return extractFirefoxDisplayedUrl(root, packageName);
            case TOOLBAR_STRUCTURE:
                return extractByToolbarStructure(root, packageName);
            default:
                return extractByViewIds(root, eventSource, packageName, profile);
        }
    }

    /**
     * Lê a URL com o método de uma família, sem os fallbacks. Um navegador desconhecido se encaixa
     * na família cujo método consegue ler a URL da barra dele.
     */
    String extractAs(BrowserProfile family, AccessibilityNodeInfo root, String packageName) {
        if (family == null || root == null || packageName == null) return null;

        switch (family.getMethod()) {
            case FIREFOX_TOOLBAR:
                return extractFirefoxDisplayedUrl(root, packageName);
            case TOOLBAR_STRUCTURE:
                return extractToolbarDisplay(root, packageName);
            default:
                return extractWithProfile(root, packageName, family);
        }
    }

    /**
     * Se a barra de endereço da família está na tela, mesmo sem uma URL: nas páginas de resultado,
     * a barra do Mi Browser mostra os termos pesquisados. Confirma que o método da família ainda
     * acha a barra na versão instalada do navegador. Uma URL lida por qualquer caminho do bloqueio,
     * inclusive o fallback genérico, também conta.
     */
    boolean hasAddressBar(AccessibilityNodeInfo root, String packageName, BrowserProfile profile) {
        if (root == null || packageName == null || profile == null) return false;
        if (extract(root, null, packageName) != null) return true;

        switch (profile.getMethod()) {
            case FIREFOX_TOOLBAR:
                if (hasVisibleNodeWithId(root, packageName, profile.getAddressViewIds())) return true;
                return NodeSearch.findFirst(root, node ->
                        (FirefoxToolbarNodes.hasTag(node, FirefoxToolbarNodes.URL_BOX_TAG)
                                || FirefoxToolbarNodes.hasTag(node, FirefoxToolbarNodes.URL_TAG)
                                || FirefoxToolbarNodes.hasTag(node, FirefoxToolbarNodes.SEARCH_BOX_TAG))
                                && NodeSearch.isVisibleInPackage(node, packageName)) != null;
            case TOOLBAR_STRUCTURE:
                return extractToolbarDisplay(root, packageName) != null
                        || ToolbarStructure.findToolbarText(root, packageName) != null;
            default:
                return hasVisibleNodeWithId(root, packageName, profile.getAddressViewIds());
        }
    }

    /**
     * Texto exibido na barra quando ele não é uma URL, como os termos pesquisados que o Mi Browser
     * mostra nas páginas de resultado. Só para as famílias lidas por IDs.
     */
    String extractBarText(AccessibilityNodeInfo root, String packageName, BrowserProfile profile) {
        if (root == null || packageName == null || profile == null) return null;
        if (profile.getMethod() != BrowserProfile.Method.VIEW_ID
                && profile.getMethod() != BrowserProfile.Method.VIEW_ID_WITH_REREAD) {
            return null;
        }

        for (String idName : profile.getAddressViewIds()) {
            try {
                List<AccessibilityNodeInfo> nodes =
                        root.findAccessibilityNodeInfosByViewId(packageName + ":id/" + idName);
                if (nodes == null) continue;

                for (AccessibilityNodeInfo node : nodes) {
                    if (node == null || !NodeSearch.isVisibleInPackage(node, packageName)) continue;
                    String text = clean(node.getText());
                    if (text == null) text = clean(node.getContentDescription());
                    if (text != null) return text;
                }
            } catch (RuntimeException ignored) {
                // A barra pode ser recriada durante a leitura; a próxima leitura cobre isso.
            }
        }
        return null;
    }

    private boolean hasVisibleNodeWithId(
            AccessibilityNodeInfo root,
            String packageName,
            List<String> ids
    ) {
        for (String idName : ids) {
            try {
                List<AccessibilityNodeInfo> nodes =
                        root.findAccessibilityNodeInfosByViewId(packageName + ":id/" + idName);
                if (nodes == null) continue;

                for (AccessibilityNodeInfo node : nodes) {
                    if (node != null && NodeSearch.isVisibleInPackage(node, packageName)) return true;
                }
            } catch (RuntimeException ignored) {
                // A barra pode ser recriada durante a leitura; a próxima checagem cobre isso.
            }
        }
        return false;
    }

    private String extractByViewIds(
            AccessibilityNodeInfo root,
            AccessibilityNodeInfo eventSource,
            String packageName,
            BrowserProfile profile
    ) {
        // Sem raiz confiável, a fonte do evento (que costuma ser a própria barra) é pesquisada.
        String profiled = extractWithProfile(root != null ? root : eventSource, packageName, profile);
        if (profiled != null) return profiled;

        return extractUnknown(root, eventSource);
    }

    private String extractUnknown(AccessibilityNodeInfo root, AccessibilityNodeInfo eventSource) {
        String fromEvent = extractFromNodeIfAddressLike(eventSource);
        if (fromEvent != null || root == null) return fromEvent;

        return extractGeneric(root);
    }

    /**
     * Opera GX: a barra é lida numa só passada pela árvore, com as mesmas prioridades de antes (o
     * campo em edição, o texto da barra e, por último, um ID com cara de barra de endereço). Cada
     * passada custa consultas ao navegador, que demoram enquanto a página carrega; com três
     * passadas, o site ficava segundos na tela antes do bloqueio.
     *
     * Sem um campo do navegador conectado ao teclado do serviço, não há campo em edição, e a leitura
     * para no primeiro texto da barra com URL, sem percorrer o resto da tela.
     */
    private String extractByToolbarStructure(AccessibilityNodeInfo root, String packageName) {
        Rect rootBounds = ToolbarStructure.boundsOf(root);
        if (rootBounds == null) return null;

        // O teclado do serviço só existe no Android 13+ (a classe nem carrega antes disso).
        boolean mayBeEditing = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ServiceInputMethod.mayBeEditing(packageName);
        String[] editing = {null};
        boolean[] editingFound = {false};
        String[] shown = {null};
        String[] byId = {null};

        NodeSearch.visit(root, node -> {
            // Em edição, só o campo digitado conta: as sugestões abaixo dele também mostram URLs.
            if (mayBeEditing
                    && node.isFocused()
                    && node.isEditable()
                    && ToolbarStructure.isToolbarText(node, rootBounds, packageName)) {
                editing[0] = ToolbarStructure.wholeUrl(node.getText());
                editingFound[0] = true;
                return true;
            }

            if (shown[0] == null) {
                String url = ToolbarStructure.wholeUrl(node.getText());
                if (url != null && ToolbarStructure.isToolbarText(node, rootBounds, packageName)) {
                    shown[0] = url;
                    return !mayBeEditing;
                }
            }

            if (byId[0] == null
                    && hasAddressLikeId(node)
                    && NodeSearch.isVisibleInPackage(node, packageName)) {
                byId[0] = firstUrlLikeValue(node);
            }
            return false;
        });

        if (editingFound[0]) return editing[0];
        return shown[0] != null ? shown[0] : byId[0];
    }

    /**
     * Barra exibida, pela estrutura: texto com URL ou domínio junto à borda; se não houver, um ID
     * com cara de barra de endereço, sempre fora do conteúdo da página.
     */
    private String extractToolbarDisplay(AccessibilityNodeInfo root, String packageName) {
        AccessibilityNodeInfo shown = ToolbarStructure.findUrlDisplay(root, packageName);
        if (shown != null) return ToolbarStructure.wholeUrl(shown.getText());

        AccessibilityNodeInfo byId = NodeSearch.findFirst(root, node ->
                hasAddressLikeId(node)
                        && NodeSearch.isVisibleInPackage(node, packageName)
                        && firstUrlLikeValue(node) != null);
        return byId == null ? null : firstUrlLikeValue(byId);
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

        // Toolbar atual, em Compose.
        AccessibilityNodeInfo composeUrl = NodeSearch.findFirst(root, node ->
                (FirefoxToolbarNodes.hasTag(node, FirefoxToolbarNodes.URL_BOX_TAG)
                        || FirefoxToolbarNodes.hasTag(node, FirefoxToolbarNodes.URL_TAG))
                        && firefoxDisplayValue(node, packageName, expectedWindowId) != null);
        return composeUrl == null
                ? null
                : firefoxDisplayValue(composeUrl, packageName, expectedWindowId);
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
                && visited < NodeSearch.MAX_NODES
                && candidates < MAX_DIAGNOSTIC_CANDIDATES) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;

            if (NodeSearch.isWebContent(node)) continue;

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

    /** ID com cara de barra de endereço, usado pelo fallback genérico. */
    static boolean hasAddressLikeId(AccessibilityNodeInfo node) {
        String id = node.getViewIdResourceName();
        if (id == null) return false;

        String lower = id.toLowerCase(Locale.ROOT);
        for (String marker : GENERIC_ID_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
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
