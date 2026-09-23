package com.mycroft.bloquearsites;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Map;

/**
 * Descobre a família de um navegador que não está na lista de pacotes.
 *
 * O navegador é testado com o método de cada família, na ordem de
 * BrowserProfiles.identificationOrder(), sempre com uma página aberta: a tela inicial própria de um
 * navegador pode mostrar um endereço que as páginas não mostram. A família que lê a URL da barra
 * sem falhar por 2 segundos (IdentificationConfirmation) passa a ser a família dele. O resultado
 * fica salvo para as próximas aberturas e para a tela do app. Se nenhuma família se encaixa, o
 * serviço bloqueia o navegador e o registra como rejeitado; ele é testado de novo a cada abertura.
 */
final class IdentifiedBrowsers {
    private static final String PREFS_NAME = "identified_browsers";
    private static final String REJECTED_PREFS_NAME = "rejected_browsers";

    private static final IdentificationConfirmation CONFIRMATION = new IdentificationConfirmation();

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
     * Testa as famílias no navegador aberto. Retorna (e salva) a família que lê a URL da barra depois
     * de confirmada, ou null se nenhuma se encaixa ou se a confirmação ainda está em andamento
     * (isAwaitingConfirmation).
     */
    static BrowserProfile identify(
            Context context,
            UrlExtractor urlExtractor,
            String packageName,
            AccessibilityNodeInfo root
    ) {
        if (root == null || packageName == null) return null;
        if (!NodeSearch.containsWebContent(root)) return null;

        for (BrowserProfile family : BrowserProfiles.identificationOrder()) {
            if (urlExtractor.extractAs(family, root, packageName) == null) continue;

            long now = SystemClock.elapsedRealtime();
            if (!CONFIRMATION.onRead(packageName, family.getFamily(), now)) return null;

            BrowserProfiles.registerIdentified(packageName, family);
            prefs(context).edit().putString(packageName, family.getFamily()).apply();
            rejectedPrefs(context).edit().remove(packageName).apply();
            return family;
        }

        CONFIRMATION.reset(packageName);
        return null;
    }

    /** Se uma família já leu a URL do navegador e aguarda a confirmação. */
    static boolean isAwaitingConfirmation(String packageName) {
        return CONFIRMATION.isPending(packageName);
    }

    /** Registra que o navegador não se encaixou em nenhuma família e foi bloqueado. */
    static void markRejected(Context context, String packageName) {
        CONFIRMATION.reset(packageName);
        rejectedPrefs(context).edit().putBoolean(packageName, true).apply();
    }

    static boolean isRejected(Context context, String packageName) {
        return rejectedPrefs(context).getBoolean(packageName, false);
    }

    private static SharedPreferences rejectedPrefs(Context context) {
        return context.getSharedPreferences(REJECTED_PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
