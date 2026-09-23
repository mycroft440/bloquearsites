package com.mycroft.bloquearsites;

import com.mycroft.bloquearsites.BrowserProfile.Method;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class BrowserProfilesTest {
    @Test
    public void firefoxFamilySharesOneProfile() {
        BrowserProfile firefox = BrowserProfiles.forPackage("org.mozilla.firefox");

        assertEquals(Method.FIREFOX_TOOLBAR, firefox.getMethod());
        assertSame(firefox, BrowserProfiles.forPackage("org.mozilla.firefox_beta"));
        assertSame(firefox, BrowserProfiles.forPackage("org.mozilla.fenix"));
        assertSame(firefox, BrowserProfiles.forPackage("org.torproject.torbrowser"));

        assertTrue(BrowserProfiles.isFirefox("org.mozilla.firefox_beta"));
        assertFalse(BrowserProfiles.isFirefox("com.android.chrome"));
        assertFalse(BrowserProfiles.isFirefox(null));
    }

    @Test
    public void chromiumFamilyUsesUrlBar() {
        BrowserProfile chrome = BrowserProfiles.forPackage("com.android.chrome");

        assertEquals(Method.VIEW_ID, chrome.getMethod());
        assertEquals(Collections.singletonList("url_bar"), chrome.getAddressViewIds());
        assertSame(chrome, BrowserProfiles.forPackage("com.brave.browser"));
        assertSame(chrome, BrowserProfiles.forPackage("com.microsoft.emmx"));

        assertTrue(BrowserProfiles.isChromium("com.kiwibrowser.browser"));
        assertFalse(BrowserProfiles.isChromium("com.sec.android.app.sbrowser"));
        assertFalse(BrowserProfiles.isChromium(null));
    }

    @Test
    public void samsungUsesItsRealBarIdsAndRereads() {
        BrowserProfile samsung = BrowserProfiles.forPackage("com.sec.android.app.sbrowser");

        assertEquals(Method.VIEW_ID_WITH_REREAD, samsung.getMethod());
        assertTrue(samsung.rereadsAfterEvent());
        assertEquals(
                Arrays.asList("location_bar_edit_text", "compact_url_text"),
                samsung.getAddressViewIds()
        );
        assertSame(samsung, BrowserProfiles.forPackage("com.sec.android.app.sbrowser.beta"));
    }

    @Test
    public void miBrowserSharesTheAospUrlInputView() {
        BrowserProfile mi = BrowserProfiles.forPackage("com.mi.globalbrowser");

        assertEquals(Method.VIEW_ID_WITH_REREAD, mi.getMethod());
        assertEquals("url", mi.getAddressViewIds().get(0));
        assertSame(mi, BrowserProfiles.forPackage("com.android.browser"));
        assertTrue(BrowserProfiles.isAospBrowser("com.android.browser"));
        assertFalse(BrowserProfiles.isAospBrowser("com.android.chrome"));
    }

    @Test
    public void miBrowserReadsTheNewWebpageStyleBar() {
        BrowserProfile mi = BrowserProfiles.forPackage("com.mi.globalbrowser");

        assertTrue(mi.getAddressViewIds().contains("web_bottom_url_click"));
        assertTrue(mi.getAddressViewIds().contains("web_bottom_url"));
        // O campo da tela de pesquisa só serve para digitar: não conta como URL exibida nem
        // identifica navegadores desconhecidos.
        assertEquals(Collections.singletonList("et_input"), mi.getEditFieldViewIds());
        assertFalse(mi.getAddressViewIds().contains("et_input"));
    }

    @Test
    public void onlyTheMiFamilyHasSeparateEditFields() {
        for (BrowserProfile profile : BrowserProfiles.all()) {
            if (profile == BrowserProfiles.forPackage("com.mi.globalbrowser")) continue;
            assertTrue(profile.getFamily(), profile.getEditFieldViewIds().isEmpty());
        }
    }

    @Test
    public void yandexHasItsOwnFamily() {
        BrowserProfile yandex = BrowserProfiles.forPackage("com.yandex.browser");

        assertEquals("Yandex", yandex.getFamily());
        assertEquals(Method.VIEW_ID, yandex.getMethod());
        assertTrue(yandex.getAddressViewIds().contains("bro_omnibar_address_title_text"));
        assertTrue(yandex.getAddressViewIds().contains("suggest_omnibox_query_edit"));
        assertFalse(BrowserProfiles.isChromium("com.yandex.browser"));
    }

    @Test
    public void operaGxIsReadByTheToolbarStructure() {
        BrowserProfile operaGx = BrowserProfiles.forPackage("com.opera.gx");

        assertEquals(Method.TOOLBAR_STRUCTURE, operaGx.getMethod());
        assertTrue(operaGx.getAddressViewIds().isEmpty());
        assertNull(BrowserProfiles.familyNamed("Genérico"));
    }

    @Test
    public void unknownBrowsersAreOnlyAcceptedByReliableFamilies() {
        List<BrowserProfile> order = BrowserProfiles.identificationOrder();

        assertSame(BrowserProfiles.chromium(), order.get(0));
        assertTrue(order.contains(BrowserProfiles.firefox()));
        assertTrue(order.contains(BrowserProfiles.forPackage("com.yandex.browser")));
        // A leitura pela estrutura depende de a barra mostrar a URL: só vale para o Opera GX.
        assertFalse(order.contains(BrowserProfiles.forPackage("com.opera.gx")));
    }

    @Test
    public void viaAndUcAreKnownUnsupported() {
        for (String packageName : Arrays.asList(
                "mark.via.gp", "mark.via", "com.UCMobile.intl", "com.UCMobile", "com.uc.browser.en")) {
            assertTrue(BrowserProfiles.isKnownUnsupported(packageName));
            assertNull(BrowserProfiles.forPackage(packageName));
        }
        assertFalse(BrowserProfiles.isKnownUnsupported("com.android.chrome"));
    }

    @Test
    public void knownUnsupportedBrowsersAreNeverIdentified() {
        BrowserProfiles.registerIdentified("mark.via.gp", BrowserProfiles.chromium());

        assertNull(BrowserProfiles.forPackage("mark.via.gp"));
        assertFalse(BrowserProfiles.isIdentified("mark.via.gp"));
    }

    @Test
    public void structuralFamilyIsNotAssignedToUnknownBrowsers() {
        String unknown = "com.example.titlebrowser";
        BrowserProfiles.registerIdentified(unknown, BrowserProfiles.forPackage("com.opera.gx"));

        assertNull(BrowserProfiles.forPackage(unknown));
    }

    @Test
    public void knownDerivativesAreListedInTheirBaseFamily() {
        assertTrue(BrowserProfiles.isChromium("org.cromite.cromite"));
        assertTrue(BrowserProfiles.isChromium("org.chromium.chrome"));
        assertTrue(BrowserProfiles.isFirefox("io.github.forkmaintainers.iceraven"));
        assertTrue(BrowserProfiles.isFirefox("us.spotco.fennec_dos"));
    }

    @Test
    public void identifiedDerivativeUsesItsBaseFamily() {
        String derivative = "com.example.chromiumfork";
        assertNull(BrowserProfiles.forPackage(derivative));

        BrowserProfiles.registerIdentified(derivative, BrowserProfiles.familyNamed("Chromium"));

        assertSame(BrowserProfiles.chromium(), BrowserProfiles.forPackage(derivative));
        assertTrue(BrowserProfiles.isChromium(derivative));
        assertTrue(BrowserProfiles.isIdentified(derivative));
        assertNull(BrowserProfiles.listedFamily(derivative));
    }

    @Test
    public void listedPackagesAreNeverOverriddenByIdentification() {
        BrowserProfiles.registerIdentified("com.android.chrome", BrowserProfiles.firefox());

        assertTrue(BrowserProfiles.isChromium("com.android.chrome"));
        assertFalse(BrowserProfiles.isIdentified("com.android.chrome"));
    }

    @Test
    public void everyPackageBelongsToASingleFamily() {
        Set<String> seen = new HashSet<>();
        for (BrowserProfile profile : BrowserProfiles.all()) {
            for (String packageName : profile.getPackageNames()) {
                assertTrue("Pacote em duas famílias: " + packageName, seen.add(packageName));
            }
        }
        assertNull(BrowserProfiles.forPackage("com.example.unknown"));
    }
}
