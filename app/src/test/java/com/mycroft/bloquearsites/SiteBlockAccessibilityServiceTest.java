package com.mycroft.bloquearsites;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAccessibilityService;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class SiteBlockAccessibilityServiceTest {
    private static final String CHROME = "com.android.chrome";
    private static final String SAMSUNG = "com.sec.android.app.sbrowser";

    private ServiceController<SiteBlockAccessibilityService> controller;
    private SiteBlockAccessibilityService service;
    private ShadowAccessibilityService shadowService;
    private BlockedSitesStore store;

    @Before
    public void setUp() {
        controller = Robolectric.buildService(SiteBlockAccessibilityService.class).create();
        service = controller.get();
        shadowService = shadowOf(service);
        service.onServiceConnected();
        store = new BlockedSitesStore(service);
        store.add("example.com");
    }

    @After
    public void tearDown() {
        if (controller != null) controller.destroy();
    }

    @Test
    public void coversBlockedPageAndOpensGoogleInTheDetectedBrowserWithoutGoingBack() {
        visit(CHROME, "https://news.example.com/private");

        LinearLayout curtain = curtain();
        assertNotNull(curtain);
        assertNotNull(curtain.getParent());
        assertEquals("Página bloqueada", ((TextView) curtain.getChildAt(0)).getText().toString());
        assertEquals(255, Color.alpha(((ColorDrawable) curtain.getBackground()).getColor()));
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) curtain.getLayoutParams();
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.width);
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.height);
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type);
        assertEquals(0, params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        assertEquals(0, params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        assertEquals(View.GONE, retryButton().getVisibility());

        Intent intent = shadowService.getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertEquals("https://google.com", intent.getDataString());
        assertEquals(CHROME, intent.getPackage());
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue(shadowService.getGlobalActionsPerformed().isEmpty());
    }

    @Test
    public void consumesTouchAndKeyboardInputOnTheCover() {
        visit(CHROME, "example.com");
        LinearLayout curtain = curtain();
        curtain.layout(0, 0, 1080, 1920);
        curtain.requestFocus();

        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 5, 5, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, 5, 5, 0);
        try {
            assertTrue(curtain.dispatchTouchEvent(down));
            assertTrue(curtain.dispatchTouchEvent(up));
            assertTrue(curtain.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)));
            assertTrue(curtain.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A)));
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    @Test
    public void doesNotReleaseOnElapsedTimeMissingUrlOrIntermediatePage() {
        visit(CHROME, "example.com");
        LinearLayout original = curtain();
        visit(CHROME, "");
        advance(10_000);
        assertSame(original, curtain());
        assertEquals(View.VISIBLE, retryButton().getVisibility());

        visit(CHROME, "https://google.com.evil.test/");
        assertSame(original, curtain());
        visit(CHROME, "https://example.org/");
        assertSame(original, curtain());
        shadowService.setRootInActiveWindow(null);
        advance(1000);
        assertSame(original, curtain());
    }

    @Test
    public void removesCoverOnlyAfterGoogleIsObservedAndBlocksAnImmediateReturn() {
        visit(CHROME, "example.com");
        shadowService.getNextStartedActivity();
        visit(CHROME, "https://www.google.com/");
        assertNull(curtain());
        assertNull(pendingPackage());

        visit(CHROME, "example.com");
        assertNotNull(curtain());
        assertNotNull(shadowService.getNextStartedActivity());
    }

    @Test
    public void confirmsThroughBrowserWindowWhenTheCoverOwnsTheActiveRoot() {
        visit(SAMSUNG, "example.com");
        AccessibilityNodeInfo ownRoot = addressNode(service.getPackageName(), "Página bloqueada");
        shadowService.setRootInActiveWindow(ownRoot);
        shadowService.setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, ownRoot),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, addressNode(SAMSUNG, "www.google.com"))
        ));

        advance(300);
        assertNull(curtain());
        assertNull(pendingPackage());
    }

    @Test
    public void doesNotConfirmGoogleInAnotherBrowserAndRestoresCoverOnReturn() {
        visit(CHROME, "example.com");
        visit(SAMSUNG, "https://google.com/");
        assertNull(curtain());
        assertEquals(CHROME, pendingPackage());

        visit(CHROME, "example.com");
        assertNotNull(curtain());
        assertEquals(CHROME, pendingPackage());
    }

    @Test
    public void doesNotConfirmFromABrowserBehindAnotherApplication() {
        visit(CHROME, "example.com");
        AccessibilityNodeInfo ownRoot = addressNode(service.getPackageName(), "Página bloqueada");
        shadowService.setRootInActiveWindow(ownRoot);
        shadowService.setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, ownRoot),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, addressNode("com.android.settings", "")),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, addressNode(CHROME, "google.com"))
        ));

        advance(300);
        assertNull(curtain());
        assertEquals(CHROME, pendingPackage());
    }

    @Test
    public void repeatedEventsDoNotLaunchMoreTabsOrRevealTheBlockedPage() {
        visit(CHROME, "example.com");
        shadowService.getNextStartedActivity();
        for (int i = 0; i < 6; i++) {
            advance(300);
            visit(CHROME, "example.com");
        }
        assertNotNull(curtain());
        assertNull(shadowService.getNextStartedActivity());
    }

    @Test
    public void failedLaunchKeepsTheCoverAndAllowsRetryWithoutReleasingIt() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
        visit(CHROME, "example.com");
        LinearLayout original = curtain();
        assertNotNull(original);
        assertEquals(View.VISIBLE, retryButton().getVisibility());

        advance(1300);
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(false);
        shadowService.clearNextStartedActivities();
        retryButton().performClick();
        assertNotNull(shadowService.getNextStartedActivity());
        assertSame(original, curtain());
        assertTrue(shadowService.getGlobalActionsPerformed().isEmpty());
    }

    @Test
    public void googleDestinationDoesNotCreateARedirectLoopWhenListed() {
        store.add("google.com");
        visit(CHROME, "google.com");
        visit(CHROME, "https://www.google.com/");
        assertNull(curtain());
        assertNull(shadowService.getNextStartedActivity());

        visit(CHROME, "https://mail.google.com/");
        assertNotNull(curtain());
        assertNotNull(shadowService.getNextStartedActivity());
    }

    @Test
    public void allowedPagesStayUntouched() {
        visit(CHROME, "https://evil-example.com/");
        assertNull(curtain());
        assertNull(shadowService.getNextStartedActivity());
    }

    @Test
    public void interruptionDoesNotExposeThePageAndDestroyRemovesTheCoverAndCallbacks() {
        visit(CHROME, "example.com");
        LinearLayout original = curtain();
        service.onInterrupt();
        assertSame(original, curtain());
        shadowService.getNextStartedActivity();

        controller.destroy();
        controller = null;
        assertNull(curtain());
        assertNull(pendingPackage());
        advance(10_000);
        assertFalse(original.isAttachedToWindow());
        assertNull(curtain());
        assertNull(shadowService.getNextStartedActivity());
    }

    private void visit(String packageName, String url) {
        shadowService.setWindows(Collections.emptyList());
        shadowService.setRootInActiveWindow(addressNode(packageName, url));
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        event.setPackageName(packageName);
        service.onAccessibilityEvent(event);
        event.recycle();
    }

    private AccessibilityNodeInfo addressNode(String packageName, String url) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setPackageName(packageName);
        node.setViewIdResourceName(packageName + ":id/url_bar");
        node.setText(url);
        return node;
    }

    private AccessibilityWindowInfo window(int type, AccessibilityNodeInfo root) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        shadowOf(window).setType(type);
        shadowOf(window).setRoot(root);
        return window;
    }

    private LinearLayout curtain() {
        return ReflectionHelpers.getField(service, "blockCurtain");
    }

    private Button retryButton() {
        return ReflectionHelpers.getField(service, "retryRedirectButton");
    }

    private String pendingPackage() {
        return ReflectionHelpers.getField(service, "redirectPackage");
    }

    private void advance(long milliseconds) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds));
    }
}
