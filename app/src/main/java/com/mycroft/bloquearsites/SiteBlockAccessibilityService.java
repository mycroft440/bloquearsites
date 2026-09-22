package com.mycroft.bloquearsites;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import java.util.Set;

public final class SiteBlockAccessibilityService extends AccessibilityService {
    private static final long BLOCK_DEBOUNCE_MS = 1200L;
    private static final long BANNER_DURATION_MS = 1400L;
    private static final long FIREFOX_RETRY_DELAY_MS = 220L;
    private static final long SAMSUNG_RETRY_DELAY_MS = 250L;

    private static final String FIREFOX_CLASSIC_PACKAGE = "org.mozilla.firefox";
    private static final String SAMSUNG_PACKAGE = "com.sec.android.app.sbrowser";
    private static final String SAMSUNG_BETA_PACKAGE = "com.sec.android.app.sbrowser.beta";
    private static final String SAMSUNG_LOG_TAG = "BloquearSitesSamsung";

    private static final int LEGACY_EVENT_TYPES =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_FOCUSED
                    | AccessibilityEvent.TYPE_VIEW_CLICKED;

    private final UrlExtractor urlExtractor = new UrlExtractor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BlockedSitesStore store;
    private WindowManager windowManager;
    private TextView blockBanner;
    private String lastBlockedKey = "";
    private long lastBlockedAt = 0L;
    private Runnable pendingFirefoxRetry;
    private Runnable pendingSamsungRetry;

    private final Runnable hideBannerRunnable = this::hideBlockBanner;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        store = new BlockedSitesStore(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            // Recebemos todos os eventos para não perder eventos específicos do Samsung Internet.
            // Para os demais navegadores, onAccessibilityEvent mantém exatamente o conjunto antigo.
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            setServiceInfo(info);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (store == null) store = new BlockedSitesStore(this);

        AccessibilityNodeInfo source = event.getSource();
        AccessibilityNodeInfo root = getRootInActiveWindow();

        String packageName = resolvePackageName(event, source, root);
        if (packageName == null || getPackageName().equals(packageName)) return;

        boolean firefoxClassic = isFirefoxClassicPackage(packageName);
        boolean samsung = isSamsungPackage(packageName);
        if (!firefoxClassic) cancelFirefoxRetry();

        if (!samsung && !isLegacyEventType(event.getEventType())) {
            return;
        }

        Set<String> blockedSites = store.getSet();
        if (blockedSites.isEmpty()) {
            if (firefoxClassic) cancelFirefoxRetry();
            if (samsung) cancelSamsungRetry();
            return;
        }

        AccessibilityNodeInfo extractionRoot = root;
        if (samsung && !sameWindow(event, root)) {
            // Se o evento e a raiz apontam para janelas diferentes, a fonte do evento é mais confiável.
            extractionRoot = null;
        }

        String visibleUrl = urlExtractor.extract(extractionRoot, source, packageName);

        if (firefoxClassic) {
            // Firefox clássico pode atualizar a barra depois do evento e manter nós antigos por instantes.
            // Sempre confirmamos a URL em uma leitura curta e posterior antes de bloquear.
            scheduleFirefoxRetry(packageName);
            return;
        }

        if (samsung) {
            logSamsungEvent(event, source, root, visibleUrl);
        }

        if (visibleUrl != null) {
            if (samsung) cancelSamsungRetry();
            handleVisibleUrl(packageName, visibleUrl, blockedSites);
            return;
        }

        if (samsung) {
            scheduleSamsungRetry(packageName);
        }
    }

    private String resolvePackageName(
            AccessibilityEvent event,
            AccessibilityNodeInfo source,
            AccessibilityNodeInfo root
    ) {
        String eventPackage = event.getPackageName() == null
                ? null
                : event.getPackageName().toString();
        String sourcePackage = packageNameOf(source);
        String rootPackage = packageNameOf(root);

        if (isSamsungPackage(sourcePackage)) return sourcePackage;
        if (isSamsungPackage(rootPackage)) return rootPackage;
        if (eventPackage != null) return eventPackage;
        if (sourcePackage != null) return sourcePackage;
        return rootPackage;
    }

    private void handleVisibleUrl(
            String packageName,
            String visibleUrl,
            Set<String> blockedSites
    ) {
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

    private void scheduleFirefoxRetry(String expectedPackage) {
        cancelFirefoxRetry();

        pendingFirefoxRetry = () -> {
            pendingFirefoxRetry = null;

            AccessibilityNodeInfo root = getRootInActiveWindow();
            String rootPackage = packageNameOf(root);
            if (root == null || !isFirefoxClassicPackage(rootPackage)) {
                return;
            }

            if (store == null) store = new BlockedSitesStore(this);
            Set<String> blockedSites = store.getSet();
            if (blockedSites.isEmpty()) return;

            String visibleUrl = urlExtractor.extract(root, null, rootPackage);
            if (visibleUrl != null) {
                handleVisibleUrl(rootPackage, visibleUrl, blockedSites);
            }
        };

        mainHandler.postDelayed(pendingFirefoxRetry, FIREFOX_RETRY_DELAY_MS);
    }

    private void cancelFirefoxRetry() {
        if (pendingFirefoxRetry == null) return;
        mainHandler.removeCallbacks(pendingFirefoxRetry);
        pendingFirefoxRetry = null;
    }

    private void scheduleSamsungRetry(String expectedPackage) {
        cancelSamsungRetry();

        pendingSamsungRetry = () -> {
            pendingSamsungRetry = null;

            AccessibilityNodeInfo root = getRootInActiveWindow();
            String rootPackage = packageNameOf(root);
            if (root == null || !isSamsungPackage(rootPackage)) {
                logSamsungRetry("sem raiz Samsung ativa", root, null);
                return;
            }

            if (store == null) store = new BlockedSitesStore(this);
            Set<String> blockedSites = store.getSet();
            if (blockedSites.isEmpty()) return;

            String visibleUrl = urlExtractor.extract(root, null, rootPackage);
            logSamsungRetry(
                    expectedPackage.equals(rootPackage) ? "mesmo pacote" : "pacote Samsung alterado",
                    root,
                    visibleUrl
            );

            if (visibleUrl != null) {
                handleVisibleUrl(rootPackage, visibleUrl, blockedSites);
            }
        };

        mainHandler.postDelayed(pendingSamsungRetry, SAMSUNG_RETRY_DELAY_MS);
    }

    private void cancelSamsungRetry() {
        if (pendingSamsungRetry == null) return;
        mainHandler.removeCallbacks(pendingSamsungRetry);
        pendingSamsungRetry = null;
    }

    private boolean sameWindow(AccessibilityEvent event, AccessibilityNodeInfo root) {
        if (root == null) return false;

        int eventWindowId = event.getWindowId();
        int rootWindowId = root.getWindowId();

        // IDs negativos representam janela indefinida; nesse caso não descartamos a raiz.
        return eventWindowId < 0 || rootWindowId < 0 || eventWindowId == rootWindowId;
    }

    private boolean isLegacyEventType(int eventType) {
        return (LEGACY_EVENT_TYPES & eventType) != 0;
    }

    private String packageNameOf(AccessibilityNodeInfo node) {
        if (node == null || node.getPackageName() == null) return null;
        return node.getPackageName().toString();
    }

    private int windowIdOf(AccessibilityNodeInfo node) {
        return node == null ? -1 : node.getWindowId();
    }

    private boolean isFirefoxClassicPackage(String packageName) {
        return FIREFOX_CLASSIC_PACKAGE.equals(packageName);
    }

    private boolean isSamsungPackage(String packageName) {
        return SAMSUNG_PACKAGE.equals(packageName) || SAMSUNG_BETA_PACKAGE.equals(packageName);
    }

    private boolean isDebugBuild() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private void logSamsungEvent(
            AccessibilityEvent event,
            AccessibilityNodeInfo source,
            AccessibilityNodeInfo root,
            String visibleUrl
    ) {
        if (!isDebugBuild()) return;

        String host = DomainMatcher.extractHost(visibleUrl);
        Log.d(
                SAMSUNG_LOG_TAG,
                "event=" + AccessibilityEvent.eventTypeToString(event.getEventType())
                        + " eventWindow=" + event.getWindowId()
                        + " sourceWindow=" + windowIdOf(source)
                        + " rootWindow=" + windowIdOf(root)
                        + " sourcePkg=" + packageNameOf(source)
                        + " rootPkg=" + packageNameOf(root)
                        + " host=" + (host == null ? "<nao-detectado>" : host)
        );

        if (visibleUrl == null) {
            Log.d(
                    SAMSUNG_LOG_TAG,
                    "sourceCandidates=" + urlExtractor.describeSamsungCandidates(source)
            );
            Log.d(
                    SAMSUNG_LOG_TAG,
                    "rootCandidates=" + urlExtractor.describeSamsungCandidates(root)
            );
        }
    }

    private void logSamsungRetry(
            String reason,
            AccessibilityNodeInfo root,
            String visibleUrl
    ) {
        if (!isDebugBuild()) return;

        String host = DomainMatcher.extractHost(visibleUrl);
        Log.d(
                SAMSUNG_LOG_TAG,
                "retry=" + reason
                        + " rootWindow=" + windowIdOf(root)
                        + " rootPkg=" + packageNameOf(root)
                        + " host=" + (host == null ? "<nao-detectado>" : host)
        );

        if (visibleUrl == null) {
            Log.d(
                    SAMSUNG_LOG_TAG,
                    "retryCandidates=" + urlExtractor.describeSamsungCandidates(root)
            );
        }
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
        cancelFirefoxRetry();
        cancelSamsungRetry();
        hideBlockBanner();
    }

    @Override
    public void onDestroy() {
        cancelFirefoxRetry();
        cancelSamsungRetry();
        mainHandler.removeCallbacksAndMessages(null);
        hideBlockBanner();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
