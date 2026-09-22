package com.mycroft.bloquearsites;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Browser;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class BlockRedirectController {
    private static final String REDIRECT_URL = "https://google.com";
    private static final String REDIRECT_HOST = "google.com";
    private static final String LOG_TAG = "BloquearSitesRedirect";

    private static final long REDIRECT_DEBOUNCE_MS = 1200L;
    private static final long REDIRECT_CHECK_DELAY_MS = 250L;
    private static final long SHOW_RETRY_DELAY_MS = 5000L;
    private static final long CURTAIN_MAX_VISIBLE_MS = 3000L;

    private final AccessibilityService service;
    private final Handler mainHandler;
    private final UrlExtractor urlExtractor;

    private WindowManager windowManager;
    private LinearLayout blockCurtain;
    private Button retryRedirectButton;
    private String redirectPackage;
    private boolean redirectFailed;
    private long lastRedirectAt = -REDIRECT_DEBOUNCE_MS;
    private long curtainVisibleUntil = 0L;

    private final Runnable redirectCheckRunnable = this::checkRedirectDestination;
    private final Runnable curtainTimeoutRunnable = this::expireBlockCurtain;

    BlockRedirectController(
            AccessibilityService service,
            Handler mainHandler,
            UrlExtractor urlExtractor
    ) {
        this.service = service;
        this.mainHandler = mainHandler;
        this.urlExtractor = urlExtractor;
        this.windowManager = (WindowManager) service.getSystemService(AccessibilityService.WINDOW_SERVICE);
    }

    boolean refreshBeforeDetection() {
        if (redirectPackage == null) return false;

        checkRedirectDestination();

        // Se a chegada ao Google foi confirmada, o evento que disparou essa confirmação
        // ainda pode carregar a árvore/URL anterior. O serviço deve descartá-lo.
        return redirectPackage == null;
    }

    boolean shouldIgnorePackage(String packageName) {
        return redirectPackage != null && redirectPackage.equals(packageName);
    }

    boolean isRedirectDestination(String visibleUrl) {
        return REDIRECT_HOST.equals(DomainMatcher.extractHost(visibleUrl));
    }

    void start(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;

        mainHandler.removeCallbacks(redirectCheckRunnable);
        mainHandler.removeCallbacks(curtainTimeoutRunnable);
        redirectPackage = packageName;
        redirectFailed = false;
        lastRedirectAt = -REDIRECT_DEBOUNCE_MS;
        curtainVisibleUntil = SystemClock.elapsedRealtime() + CURTAIN_MAX_VISIBLE_MS;

        showBlockCurtain();
        mainHandler.postDelayed(curtainTimeoutRunnable, CURTAIN_MAX_VISIBLE_MS);
        openGoogle();
        mainHandler.postDelayed(redirectCheckRunnable, REDIRECT_CHECK_DELAY_MS);
    }

    void destroy() {
        mainHandler.removeCallbacks(redirectCheckRunnable);
        mainHandler.removeCallbacks(curtainTimeoutRunnable);
        redirectPackage = null;
        curtainVisibleUntil = 0L;
        hideBlockCurtain();
    }

    private void openGoogle() {
        if (redirectPackage == null) return;

        long now = SystemClock.elapsedRealtime();
        if (now - lastRedirectAt < REDIRECT_DEBOUNCE_MS) return;

        lastRedirectAt = now;
        redirectFailed = false;

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(REDIRECT_URL));
        intent.setPackage(redirectPackage);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(Browser.EXTRA_APPLICATION_ID, service.getPackageName());

        try {
            service.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            redirectFailed = true;
            Log.w(LOG_TAG, "Não foi possível abrir o Google no navegador bloqueado.", e);
        }

        updateRetryButton();
    }

    private void checkRedirectDestination() {
        mainHandler.removeCallbacks(redirectCheckRunnable);
        if (redirectPackage == null) return;

        AccessibilityNodeInfo root = foregroundApplicationRoot();
        String packageName = packageNameOf(root);

        if (redirectPackage.equals(packageName)) {
            String visibleUrl = urlExtractor.extract(root, null, packageName);
            if (isRedirectDestination(visibleUrl)) {
                finishRedirect();
                return;
            }
        } else {
            // Sem uma janela ativa confiável, ou fora do navegador que está redirecionando,
            // a cortina deve falhar aberta para nunca prender a interface do aparelho.
            hideBlockCurtain();
        }

        updateRetryButton();
        mainHandler.postDelayed(redirectCheckRunnable, REDIRECT_CHECK_DELAY_MS);
    }

    private AccessibilityNodeInfo foregroundApplicationRoot() {
        for (AccessibilityWindowInfo window : service.getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                return window.getRoot();
            }
        }

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        return redirectPackage != null && redirectPackage.equals(packageNameOf(root))
                ? root
                : null;
    }

    private String packageNameOf(AccessibilityNodeInfo node) {
        if (node == null || node.getPackageName() == null) return null;
        return node.getPackageName().toString();
    }

    private void showBlockCurtain() {
        if (curtainVisibleUntil <= 0L
                || SystemClock.elapsedRealtime() >= curtainVisibleUntil) {
            hideBlockCurtain();
            return;
        }

        if (windowManager == null) {
            windowManager = (WindowManager) service.getSystemService(AccessibilityService.WINDOW_SERVICE);
        }
        if (windowManager == null) return;

        if (blockCurtain == null) {
            blockCurtain = new LinearLayout(service);
            blockCurtain.setOrientation(LinearLayout.VERTICAL);
            blockCurtain.setBackgroundColor(Color.rgb(25, 25, 25));
            blockCurtain.setGravity(Gravity.CENTER);
            blockCurtain.setPadding(dp(24), dp(24), dp(24), dp(24));
            blockCurtain.setClickable(true);
            blockCurtain.setFocusable(false);

            TextView curtainMessage = new TextView(service);
            curtainMessage.setText("Página bloqueada");
            curtainMessage.setTextColor(Color.WHITE);
            curtainMessage.setTextSize(20f);
            curtainMessage.setGravity(Gravity.CENTER);
            blockCurtain.addView(curtainMessage);

            retryRedirectButton = new Button(service);
            retryRedirectButton.setText("Tentar novamente");
            retryRedirectButton.setAllCaps(false);
            retryRedirectButton.setVisibility(View.GONE);
            retryRedirectButton.setOnClickListener(v -> openGoogle());

            LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
            );
            retryParams.topMargin = dp(24);
            blockCurtain.addView(retryRedirectButton, retryParams);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.OPAQUE
            );
            params.gravity = Gravity.TOP | Gravity.START;
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;

            try {
                windowManager.addView(blockCurtain, params);
            } catch (RuntimeException e) {
                blockCurtain = null;
                retryRedirectButton = null;
                Log.w(LOG_TAG, "Não foi possível exibir a cortina de bloqueio.", e);
            }
        }

        updateRetryButton();
    }

    private void updateRetryButton() {
        if (retryRedirectButton == null) return;

        boolean canRetry = redirectFailed
                || SystemClock.elapsedRealtime() - lastRedirectAt >= SHOW_RETRY_DELAY_MS;
        retryRedirectButton.setVisibility(canRetry ? View.VISIBLE : View.GONE);
    }

    private void expireBlockCurtain() {
        curtainVisibleUntil = 0L;
        hideBlockCurtain();
    }

    private void finishRedirect() {
        mainHandler.removeCallbacks(curtainTimeoutRunnable);
        redirectPackage = null;
        curtainVisibleUntil = 0L;
        hideBlockCurtain();
    }

    private void hideBlockCurtain() {
        if (blockCurtain == null || windowManager == null) return;

        try {
            windowManager.removeView(blockCurtain);
        } catch (RuntimeException ignored) {
        } finally {
            blockCurtain = null;
            retryRedirectButton = null;
        }
    }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }
}
