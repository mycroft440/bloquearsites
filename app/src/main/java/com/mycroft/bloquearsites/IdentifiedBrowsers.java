package com.mycroft.bloquearsites;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Map;

/**
 * Reconhece navegadores que não estão na lista de pacotes, mas cuja barra o app consegue ler.
 *
 * Derivados do Chromium e do Firefox mantêm a barra da base (o url_bar do Chromium; a toolbar do
 * Android Components ou a testTag em Compose do Firefox) e passam a usar a família dela. Os demais
 * entram na família genérica quando têm uma barra que o fallback genérico reconhece, ou quando o
 * app consegue ler uma URL deles. O resultado fica salvo para as próximas aberturas e para a tela
 * do app.
 */
final class IdentifiedBrowsers {
    private static final String PREFS_NAME = "identified_browsers";

    private static final String CHROMIUM_ADDRESS_BAR_ID = "url_bar";
    private static final String[] FIREFOX_VIEW_TOOLBAR_IDS = {
            "mozac_browser_toolbar_url_view",
            "mozac_browser_toolbar_edit_url_view"
    };

    private IdentifiedBrowsers() {}

    /** Carrega os derivados já identificados para o BrowserProfiles. */
    static void load(Context context) {
        Map<String, ?> saved = prefs(context).getAll();
        for (Map.Entry<String, ?> entry : saved.entrySet()) {
            if (!(entry.getValue() instanceof String)) continue;
            BrowserProfile family = BrowserProfiles.familyNamed((String) entry.getValue());
            BrowserProfiles.registerIdentified(entry.getKey(), family);
        }
    }

    /**
     * Tenta reconhecer a família de um navegador desconhecido pela barra de endereço na tela.
     * Retorna a família (e a salva) ou null se a barra não for de nenhuma base conhecida.
     */
    static BrowserProfile identify(
            Context context,
            String packageName,
            AccessibilityNodeInfo root
    ) {
        BrowserProfile family = probe(root, packageName);
        if (family != null) remember(context, packageName, family);
        return family;
    }

    /** Salva a família de um navegador desconhecido (por exemplo, depois de ler uma URL dele). */
    static void remember(Context context, String packageName, BrowserProfile family) {
        BrowserProfiles.registerIdentified(packageName, family);
        prefs(context).edit().putString(packageName, family.getFamily()).apply();
    }

    private static BrowserProfile probe(AccessibilityNodeInfo root, String packageName) {
        if (root == null || packageName == null) return null;

        if (hasViewId(root, packageName, CHROMIUM_ADDRESS_BAR_ID)) {
            return BrowserProfiles.chromium();
        }

        for (String idName : FIREFOX_VIEW_TOOLBAR_IDS) {
            if (hasViewId(root, packageName, idName)) return BrowserProfiles.firefox();
        }

        AccessibilityNodeInfo composeToolbar = NodeSearch.findFirst(root, node ->
                (FirefoxToolbarNodes.hasTag(node, FirefoxToolbarNodes.URL_BOX_TAG)
                        || FirefoxToolbarNodes.hasTag(node, FirefoxToolbarNodes.SEARCH_BOX_TAG))
                        && NodeSearch.isVisibleInPackage(node, packageName));
        if (composeToolbar != null) return BrowserProfiles.firefox();

        AccessibilityNodeInfo genericBar = NodeSearch.findFirst(root, node ->
                UrlExtractor.hasAddressLikeId(node)
                        && NodeSearch.isVisibleInPackage(node, packageName));
        return genericBar == null ? null : BrowserProfiles.generic();
    }

    private static boolean hasViewId(
            AccessibilityNodeInfo root,
            String packageName,
            String idName
    ) {
        try {
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(packageName + ":id/" + idName);
            return nodes != null && !nodes.isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
