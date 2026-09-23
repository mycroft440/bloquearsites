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
        assertEquals(Collections.singletonList("url"), mi.getAddressViewIds());
        assertSame(mi, BrowserProfiles.forPackage("com.android.browser"));
    }

    @Test
    public void viaIsFoundByToolbarStructure() {
        BrowserProfile via = BrowserProfiles.forPackage("mark.via.gp");

        assertEquals(Method.TOOLBAR_STRUCTURE, via.getMethod());
        assertTrue(via.rereadsAfterEvent());
        assertTrue(via.getAddressViewIds().isEmpty());
        assertSame(via, BrowserProfiles.forPackage("mark.via"));
    }

    @Test
    public void ucBrowserIsFoundByToolbarStructureInItsOwnFamily() {
        BrowserProfile uc = BrowserProfiles.forPackage("com.UCMobile.intl");

        assertEquals(Method.TOOLBAR_STRUCTURE, uc.getMethod());
        assertEquals("UC Browser", uc.getFamily());
        assertSame(uc, BrowserProfiles.forPackage("com.UCMobile"));
    }

    @Test
    public void onlyFamiliesWithLimitationsCarryANote() {
        assertNull(BrowserProfiles.forPackage("com.android.chrome").getNote());
        assertNull(BrowserProfiles.forPackage("org.mozilla.firefox").getNote());
        assertTrue(BrowserProfiles.forPackage("mark.via.gp").getNote().contains("URL"));
        assertTrue(BrowserProfiles.forPackage("com.UCMobile.intl").getNote().startsWith("parcial"));
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
    public void unknownBrowsersAreTestedAgainstEveryReusableFamily() {
        List<BrowserProfile> order = BrowserProfiles.identificationOrder();

        assertSame(BrowserProfiles.chromium(), order.get(0));
        assertTrue(order.contains(BrowserProfiles.firefox()));
        assertTrue(order.contains(BrowserProfiles.forPackage("com.yandex.browser")));
        assertSame(BrowserProfiles.forPackage("com.opera.gx"), order.get(order.size() - 1));
        // Via e UC são famílias de um navegador só.
        assertFalse(order.contains(BrowserProfiles.forPackage("mark.via.gp")));
        assertFalse(order.contains(BrowserProfiles.forPackage("com.UCMobile.intl")));
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
