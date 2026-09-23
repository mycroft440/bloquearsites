package com.mycroft.bloquearsites;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class BrowserProfiles {
    private BrowserProfiles() {}

    private static final BrowserProfile CHROMIUM = new BrowserProfile(
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
                    "com.kiwibrowser.browser"
            },
            "url_bar"
    );

    private static final BrowserProfile FIREFOX = new BrowserProfile(
            new String[]{"org.mozilla.firefox"},
            "url_edit_text",
            "url_bar_title",
            "mozac_browser_toolbar_edit_url_view",
            "mozac_browser_toolbar_url_view"
    );

    // Beta, Nightly e Tor Browser usam a mesma base do Firefox (Fenix).
    private static final BrowserProfile FIREFOX_PRERELEASE = new BrowserProfile(
            new String[]{
                    "org.mozilla.firefox_beta",
                    "org.mozilla.fenix",
                    "org.torproject.torbrowser"
            },
            "mozac_browser_toolbar_url_view",
            "mozac_browser_toolbar_edit_url_view"
    );

    private static final List<BrowserProfile> PROFILES = Collections.unmodifiableList(Arrays.asList(
            CHROMIUM,
            FIREFOX,
            FIREFOX_PRERELEASE,
            new BrowserProfile(
                    new String[]{
                            "com.sec.android.app.sbrowser",
                            "com.sec.android.app.sbrowser.beta"
                    },
                    "location_bar_edit_text",
                    "location__bar_edit_text",
                    "location_bar",
                    "location__bar",
                    "location_bar_text",
                    "location_bar_url_text",
                    "url_bar",
                    "url_bar_text",
                    "address_bar",
                    "address_bar_edit_text",
                    "search_url_text",
                    "toolbar_url",
                    "toolbar_url_text"
            ),
            new BrowserProfile(
                    new String[]{
                            "com.opera.browser",
                            "com.opera.browser.beta",
                            "com.opera.mini.native"
                    },
                    "url_field"
            ),
            new BrowserProfile(
                    new String[]{"com.duckduckgo.mobile.android"},
                    "omnibarTextInput"
            )
    ));

    public static boolean isChromium(String packageName) {
        return CHROMIUM.matchesPackage(packageName);
    }

    public static boolean isFirefox(String packageName) {
        return FIREFOX.matchesPackage(packageName) || FIREFOX_PRERELEASE.matchesPackage(packageName);
    }

    public static BrowserProfile forPackage(String packageName) {
        for (BrowserProfile profile : PROFILES) {
            if (profile.matchesPackage(packageName)) return profile;
        }
        return null;
    }
}
