package com.mycroft.bloquearsites;

import com.mycroft.bloquearsites.BrowserProfile.Method;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Famílias de navegadores. Navegadores que expõem a barra de endereço do mesmo jeito ficam no
 * mesmo perfil; uma diferença na forma de identificação pede um perfil próprio.
 *
 * Um navegador fora da lista é testado com o método de cada família (identificationOrder): a
 * primeira que consegue ler a URL da barra passa a ser a família dele (IdentifiedBrowsers). Se
 * nenhuma consegue, ele é bloqueado. Navegadores sabidamente sem leitura confiável ficam em
 * KNOWN_UNSUPPORTED e são sempre bloqueados.
 */
public final class BrowserProfiles {
    private BrowserProfiles() {}

    // Chromium e derivados que mantêm a interface do Chrome: url_bar é um EditText que mostra a URL
    // e vira o campo de edição ao ser tocado.
    private static final BrowserProfile CHROMIUM = new BrowserProfile(
            "Chromium",
            Method.VIEW_ID,
            new String[]{
                    "com.android.chrome",
                    "com.chrome.beta",
                    "com.chrome.dev",
                    "com.chrome.canary",
                    "com.brave.browser",
                    "com.brave.browser_beta",
                    "com.brave.browser_nightly",
                    "com.microsoft.emmx",
                    "com.microsoft.emmx.beta",
                    "com.microsoft.emmx.dev",
                    "com.microsoft.emmx.canary",
                    "com.vivaldi.browser",
                    "com.vivaldi.browser.snapshot",
                    "com.kiwibrowser.browser",
                    "org.chromium.chrome",
                    "org.cromite.cromite",
                    "org.bromite.bromite",
                    "us.spotco.mulch"
            },
            "url_bar"
    );

    // Firefox e navegadores da mesma base (Fenix). A toolbar atual é Compose e é lida pelas
    // testTags em FirefoxToolbarNodes; os IDs abaixo cobrem as toolbars antigas em View
    // (Android Components e Fennec).
    private static final BrowserProfile FIREFOX = new BrowserProfile(
            "Firefox",
            Method.FIREFOX_TOOLBAR,
            new String[]{
                    "org.mozilla.firefox",
                    "org.mozilla.firefox_beta",
                    "org.mozilla.fenix",
                    "org.torproject.torbrowser",
                    "org.mozilla.fennec_fdroid",
                    "io.github.forkmaintainers.iceraven",
                    "us.spotco.fennec_dos"
            },
            "mozac_browser_toolbar_url_view",
            "mozac_browser_toolbar_edit_url_view",
            "url_bar_title",
            "url_edit_text"
    );

    // Samsung Internet: location_bar_edit_text é o UrlBar (EditText) da barra completa; ao rolar a
    // página, a barra compacta mostra o domínio em compact_url_text. O texto exibido começa com a
    // marca invisível U+200E, descartada pelo DomainMatcher.
    private static final BrowserProfile SAMSUNG = new BrowserProfile(
            "Samsung Internet",
            Method.VIEW_ID_WITH_REREAD,
            new String[]{
                    "com.sec.android.app.sbrowser",
                    "com.sec.android.app.sbrowser.beta"
            },
            "location_bar_edit_text",
            "compact_url_text"
    );

    // Mi Browser e navegadores da base AOSP (com.android.browser, usado pela MIUI): UrlInputView
    // com ID url, que mostra a URL sem o esquema e vira o campo de edição ao ser tocado.
    private static final BrowserProfile AOSP_BROWSER = new BrowserProfile(
            "Mi Browser/AOSP",
            Method.VIEW_ID_WITH_REREAD,
            new String[]{
                    "com.mi.globalbrowser",
                    "com.android.browser"
            },
            "url"
    );

    private static final BrowserProfile OPERA = new BrowserProfile(
            "Opera",
            Method.VIEW_ID,
            new String[]{
                    "com.opera.browser",
                    "com.opera.browser.beta",
                    "com.opera.mini.native"
            },
            "url_field"
    );

    private static final BrowserProfile DUCKDUCKGO = new BrowserProfile(
            "DuckDuckGo",
            Method.VIEW_ID,
            new String[]{"com.duckduckgo.mobile.android"},
            "omnibarTextInput"
    );

    // Yandex (analisado no APK 26.8): o domínio aparece no título central da barra
    // (bro_omnibar_address_title_text/_view) ou na barra recolhida ao rolar; tocar nele abre o
    // campo de edição suggest_omnibox_query_edit.
    private static final BrowserProfile YANDEX = new BrowserProfile(
            "Yandex",
            Method.VIEW_ID,
            new String[]{
                    "com.yandex.browser",
                    "com.yandex.browser.beta"
            },
            "bro_omnibar_address_title_text",
            "bro_omnibar_address_title_view",
            "bro_omnibox_collapsed_title",
            "suggest_omnibox_query_edit"
    );

    // Barra sem IDs próprios, lida pela estrutura da tela (texto com URL ou domínio junto à borda,
    // fora da página). Opera GX (APK 3.3.9) desenha a barra em Compose, sem IDs. O método depende
    // de a barra mostrar a URL, então não é usado para aceitar navegadores desconhecidos.
    private static final BrowserProfile STRUCTURAL = new BrowserProfile(
            "Barra na tela",
            Method.TOOLBAR_STRUCTURE,
            new String[]{"com.opera.gx"}
    );

    private static final List<BrowserProfile> PROFILES = Collections.unmodifiableList(Arrays.asList(
            CHROMIUM,
            FIREFOX,
            SAMSUNG,
            AOSP_BROWSER,
            OPERA,
            DUCKDUCKGO,
            YANDEX,
            STRUCTURAL
    ));

    // Ordem em que um navegador desconhecido é testado: só famílias lidas por IDs ou pela toolbar do
    // Firefox. A Barra na tela fica de fora porque aceitaria navegadores que mostram o título da
    // página em vez da URL, e neles sites abertos por links passariam.
    private static final List<BrowserProfile> IDENTIFICATION_ORDER =
            Collections.unmodifiableList(Arrays.asList(
                    CHROMIUM,
                    FIREFOX,
                    SAMSUNG,
                    AOSP_BROWSER,
                    OPERA,
                    DUCKDUCKGO,
                    YANDEX
            ));

    // Navegadores testados e sem leitura confiável: nunca são identificados e sempre são bloqueados.
    // Via e UC mostram o título da página na barra, e sites abertos por links ou pela pesquisa
    // ficavam acessíveis; UC Mini é a versão antiga (2016) do UC.
    private static final Set<String> KNOWN_UNSUPPORTED = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "mark.via.gp",
                    "mark.via",
                    "com.UCMobile.intl",
                    "com.UCMobile",
                    "com.uc.browser.en"
            ))
    );

    // Navegadores fora da lista que se encaixaram em uma família. Preenchido pelo IdentifiedBrowsers.
    private static final Map<String, BrowserProfile> IDENTIFIED = new ConcurrentHashMap<>();

    public static boolean isChromium(String packageName) {
        return forPackage(packageName) == CHROMIUM;
    }

    public static boolean isFirefox(String packageName) {
        return forPackage(packageName) == FIREFOX;
    }

    public static boolean isKnownUnsupported(String packageName) {
        return packageName != null && KNOWN_UNSUPPORTED.contains(packageName);
    }

    public static BrowserProfile forPackage(String packageName) {
        if (packageName == null || isKnownUnsupported(packageName)) return null;

        BrowserProfile listed = listedFamily(packageName);
        return listed != null ? listed : IDENTIFIED.get(packageName);
    }

    /** Família da lista fixa de pacotes, sem considerar os derivados identificados. */
    static BrowserProfile listedFamily(String packageName) {
        for (BrowserProfile profile : PROFILES) {
            if (profile.matchesPackage(packageName)) return profile;
        }
        return null;
    }

    static boolean isIdentified(String packageName) {
        return packageName != null && IDENTIFIED.containsKey(packageName);
    }

    static void registerIdentified(String packageName, BrowserProfile family) {
        if (packageName == null || family == null || listedFamily(packageName) != null) return;
        if (isKnownUnsupported(packageName) || !IDENTIFICATION_ORDER.contains(family)) return;
        IDENTIFIED.put(packageName, family);
    }

    static BrowserProfile familyNamed(String family) {
        for (BrowserProfile profile : PROFILES) {
            if (profile.getFamily().equals(family)) return profile;
        }
        return null;
    }

    static BrowserProfile chromium() {
        return CHROMIUM;
    }

    static BrowserProfile firefox() {
        return FIREFOX;
    }

    static List<BrowserProfile> identificationOrder() {
        return IDENTIFICATION_ORDER;
    }

    static List<BrowserProfile> all() {
        return PROFILES;
    }
}
