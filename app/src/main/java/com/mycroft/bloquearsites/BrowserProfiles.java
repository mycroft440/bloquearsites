package com.mycroft.bloquearsites;

import com.mycroft.bloquearsites.BrowserProfile.Method;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Famílias de navegadores. Navegadores que expõem a barra de endereço do mesmo jeito ficam no
 * mesmo perfil; uma diferença na forma de identificação pede um perfil próprio.
 *
 * Além dos pacotes listados, derivados do Chromium e do Firefox que não estão na lista entram na
 * família da base quando a barra dela é reconhecida na tela (IdentifiedBrowsers).
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

    // Via: IDs ofuscados que mudam a cada versão. Por padrão a barra mostra o título da página;
    // a URL só fica visível com "Conteúdo da caixa de URL" em URL ou Domínio.
    private static final BrowserProfile VIA = new BrowserProfile(
            "Via",
            Method.TOOLBAR_STRUCTURE,
            new String[]{
                    "mark.via.gp",
                    "mark.via"
            }
    ).withNote("no Via, mude \u201cConteúdo da caixa de URL\u201d para URL ou Domínio");

    // UC Browser: a barra exibida é montada por código ofuscado. A edição de endereço
    // (SmartURLWindow) é um campo no topo; a barra exibida só identifica o site se mostrar a URL ou
    // o domínio, e não o título da página.
    private static final BrowserProfile UC = new BrowserProfile(
            "UC Browser",
            Method.TOOLBAR_STRUCTURE,
            new String[]{
                    "com.UCMobile.intl",
                    "com.UCMobile"
            }
    ).withNote("parcial: bloqueia endereços digitados; links só se a barra mostrar a URL");

    // Navegadores cuja barra é lida pelo fallback genérico de IDs (url_field, omnibar, address_bar…
    // em UrlExtractor), sem família própria. O redirecionamento usa uma aba nova. Opera GX e Yandex
    // foram confirmados em aparelho; outros navegadores entram aqui quando identificados.
    private static final BrowserProfile GENERIC = new BrowserProfile(
            "Genérico",
            Method.VIEW_ID,
            new String[]{
                    "com.opera.gx",
                    "com.yandex.browser"
            }
    ).withNote("leitura genérica da barra; o Google abre em aba nova");

    private static final List<BrowserProfile> PROFILES = Collections.unmodifiableList(Arrays.asList(
            CHROMIUM,
            FIREFOX,
            SAMSUNG,
            AOSP_BROWSER,
            OPERA,
            DUCKDUCKGO,
            VIA,
            UC,
            GENERIC
    ));

    // Derivados reconhecidos pela barra na tela. Preenchido pelo IdentifiedBrowsers.
    private static final Map<String, BrowserProfile> IDENTIFIED = new ConcurrentHashMap<>();

    public static boolean isChromium(String packageName) {
        return forPackage(packageName) == CHROMIUM;
    }

    public static boolean isFirefox(String packageName) {
        return forPackage(packageName) == FIREFOX;
    }

    public static BrowserProfile forPackage(String packageName) {
        if (packageName == null) return null;

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

    static BrowserProfile generic() {
        return GENERIC;
    }

    static List<BrowserProfile> all() {
        return PROFILES;
    }
}
