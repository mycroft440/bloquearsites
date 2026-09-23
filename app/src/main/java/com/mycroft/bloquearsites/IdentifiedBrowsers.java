package com.mycroft.bloquearsites;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Map;

/**
 * Descobre a família de um navegador que não está na lista de pacotes.
 *
 * O navegador é testado com o método de cada família, na ordem de
 * BrowserProfiles.identificationOrder(): a primeira que consegue ler a URL da barra dele passa a ser
 * a família dele. O resultado fica salvo para as próximas aberturas e para a tela do app. Se
 * nenhuma família se encaixa, o serviço bloqueia o navegador.
 */
final class IdentifiedBrowsers {
    private static final String PREFS_NAME = "identified_browsers";

    private IdentifiedBrowsers() {}

    /** Carrega os navegadores já identificados para o BrowserProfiles. */
    static void load(Context context) {
        Map<String, ?> saved = prefs(context).getAll();
        for (Map.Entry<String, ?> entry : saved.entrySet()) {
            if (!(entry.getValue() instanceof String)) continue;
            BrowserProfile family = BrowserProfiles.familyNamed((String) entry.getValue());
            BrowserProfiles.registerIdentified(entry.getKey(), family);
        }
    }

    /**
     * Testa as famílias no navegador aberto. Retorna (e salva) a primeira que lê a URL da barra, ou
     * null se nenhuma se encaixa.
     */
    static BrowserProfile identify(
            Context context,
            UrlExtractor urlExtractor,
            String packageName,
            AccessibilityNodeInfo root
    ) {
        if (root == null || packageName == null) return null;

        for (BrowserProfile family : BrowserProfiles.identificationOrder()) {
            if (urlExtractor.extractAs(family, root, packageName) != null) {
                BrowserProfiles.registerIdentified(packageName, family);
                prefs(context).edit().putString(packageName, family.getFamily()).apply();
                return family;
            }
        }
        return null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
