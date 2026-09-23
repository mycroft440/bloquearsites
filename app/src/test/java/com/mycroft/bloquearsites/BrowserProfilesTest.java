package com.mycroft.bloquearsites;

import com.mycroft.bloquearsites.BrowserProfile.Method;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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
