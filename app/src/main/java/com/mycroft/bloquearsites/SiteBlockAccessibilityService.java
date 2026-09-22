package com.mycroft.bloquearsites;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import java.util.Set;

public final class SiteBlockAccessibilityService extends AccessibilityService {
    private static final long BLOCK_DEBOUNCE_MS = 1200L;
    private static final long BANNER_DURATION_MS = 1400L;

    private final UrlExtractor urlExtractor = new UrlExtractor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BlockedSitesStore store;
    private WindowManager windowManager;
    private TextView blockBanner;
    private String lastBlockedKey = "";
    private long lastBlockedAt = 0L;

    private final Runnable hideBannerRunnable = this::hideBlockBanner;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        store = new BlockedSitesStore(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (store == null) store = new BlockedSitesStore(this);

        Set<String> blockedSites = store.getSet();
        if (blockedSites.isEmpty()) return;

        String packageName = event.getPackageName().toString();
        if (getPackageName().equals(packageName)) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo source = event.getSource();

        String visibleUrl = urlExtractor.extract(root, source, packageName);
        if (visibleUrl == null) return;

        String matchedDomain = DomainMatcher.findMatchedDomain(visibleUrl, blockedSites);
        if (matchedDomain == null) return;

        String host = DomainMatcher.extractHost(visibleUrl);
        String blockKey = packageName + "|" + host;
        long now = SystemClock.elapsedRealtime();

        if (blockKey.equals(lastBlockedKey) && now - lastBlockedAt < BLOCK_DEBOUNCE_MS) {
            return;
        }

        lastBlockedKey = blockKey;
        lastBlockedAt = now;
        blockCurrentPage(matchedDomain);
    }

    private void blockCurrentPage(String domain) {
        showBlockBanner(domain);

        boolean wentBack = performGlobalAction(GLOBAL_ACTION_BACK);
        if (!wentBack) {
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    private void showBlockBanner(String domain) {
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }

        mainHandler.removeCallbacks(hideBannerRunnable);

        if (blockBanner == null) {
            blockBanner = new TextView(this);
            blockBanner.setTextColor(Color.WHITE);
            blockBanner.setBackgroundColor(Color.rgb(176, 0, 32));
            blockBanner.setTextSize(18f);
            blockBanner.setGravity(Gravity.CENTER);
            int horizontal = dp(20);
            int vertical = dp(18);
            blockBanner.setPadding(horizontal, vertical, horizontal, vertical);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP;

            try {
                windowManager.addView(blockBanner, params);
            } catch (RuntimeException ignored) {
                blockBanner = null;
            }
        }

        if (blockBanner != null) {
            blockBanner.setText("Site bloqueado: " + domain);
            mainHandler.postDelayed(hideBannerRunnable, BANNER_DURATION_MS);
        }
    }

    private void hideBlockBanner() {
        if (blockBanner == null || windowManager == null) return;
        try {
            windowManager.removeView(blockBanner);
        } catch (RuntimeException ignored) {
        } finally {
            blockBanner = null;
        }
    }

    @Override
    public void onInterrupt() {
        hideBlockBanner();
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        hideBlockBanner();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
