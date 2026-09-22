package com.mycroft.bloquearsites;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Browser;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Set;

public final class SiteBlockAccessibilityService extends AccessibilityService {
    private static final String REDIRECT_URL = "https://google.com";
    private static final String REDIRECT_HOST = "google.com";
    private static final String LOG_TAG = "BloquearSites";
    private static final long REDIRECT_DEBOUNCE_MS = 1200L;
    private static final long REDIRECT_CHECK_DELAY_MS = 250L;
    private static final long SHOW_RETRY_DELAY_MS = 5000L;
    private static final long SAMSUNG_RETRY_DELAY_MS = 250L;

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
    private LinearLayout blockCurtain;
    private Button retryRedirectButton;
    private String redirectPackage;
    private boolean redirectFailed;
    private long lastRedirectAt = -REDIRECT_DEBOUNCE_MS;
    private Runnable pendingSamsungRetry;

    private final Runnable redirectCheckRunnable = this::checkRedirectDestination;

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
        if (redirectPackage != null) {
            checkRedirectDestination();
            // Não reutiliza a fonte de um evento antigo depois de confirmar a navegação.
            if (redirectPackage == null) return;
        }
        if (store == null) store = new BlockedSitesStore(this);

        AccessibilityNodeInfo source = event.getSource();
        AccessibilityNodeInfo root = getRootInActiveWindow();

        String packageName = resolvePackageName(event, source, root);
        if (packageName == null || getPackageName().equals(packageName)) return;
        if (packageName.equals(redirectPackage)) return;

        boolean samsung = isSamsungPackage(packageName);
        if (!samsung && !isLegacyEventType(event.getEventType())) {
            return;
        }

        Set<String> blockedSites = store.getSet();
        if (blockedSites.isEmpty()) {
            if (samsung) cancelSamsungRetry();
            return;
        }

        AccessibilityNodeInfo extractionRoot = root;
        if (samsung && !sameWindow(event, root)) {
            // Se o evento e a raiz apontam para janelas diferentes, a fonte do evento é mais confiável.
            extractionRoot = null;
        }

        String visibleUrl = urlExtractor.extract(extractionRoot, source, packageName);

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
        if (packageName.equals(redirectPackage)) return;

        // O destino precisa permanecer acessível, mesmo se google.com estiver na lista.
        if (REDIRECT_HOST.equals(DomainMatcher.extractHost(visibleUrl))) return;

        String matchedDomain = DomainMatcher.findMatchedDomain(visibleUrl, blockedSites);
        if (matchedDomain == null) return;

        blockCurrentPage(packageName);
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

    private void blockCurrentPage(String packageName) {
        cancelSamsungRetry();
        mainHandler.removeCallbacks(redirectCheckRunnable);
        redirectPackage = packageName;
        redirectFailed = false;
        lastRedirectAt = -REDIRECT_DEBOUNCE_MS;
        showBlockCurtain();
        openGoogle();
        mainHandler.postDelayed(redirectCheckRunnable, REDIRECT_CHECK_DELAY_MS);
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
        // Navegadores que suportam esse extra podem reutilizar a aba do redirecionamento.
        intent.putExtra(Browser.EXTRA_APPLICATION_ID, getPackageName());

        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            redirectFailed = true;
            Log.w(LOG_TAG, "Não foi possível abrir o destino no navegador.", e);
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
            if (REDIRECT_HOST.equals(DomainMatcher.extractHost(visibleUrl))) {
                redirectPackage = null;
                hideBlockCurtain();
                return;
            }
            showBlockCurtain();
        } else if (packageName != null) {
            // Suspende apenas a exibição fora do navegador; o redirecionamento segue pendente.
            hideBlockCurtain();
        }
        updateRetryButton();

        // O intervalo serve apenas para consultar a URL, nunca para liberar a página por tempo.
        mainHandler.postDelayed(redirectCheckRunnable, REDIRECT_CHECK_DELAY_MS);
    }

    private AccessibilityNodeInfo foregroundApplicationRoot() {
        // As janelas vêm da mais alta para a mais baixa. Ignora a cortina, que recebe o foco,
        // e consulta somente o app da frente, nunca um navegador escondido por outro app.
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                // Se a raiz do app da frente não está disponível, não consulta apps atrás dele.
                return window.getRoot();
            }
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        // Uma raiz de teclado, cortina ou sistema não comprova uma troca de aplicativo.
        return redirectPackage.equals(packageNameOf(root)) ? root : null;
    }

    private void showBlockCurtain() {
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }

        if (blockCurtain == null) {
            blockCurtain = new LinearLayout(this);
            blockCurtain.setOrientation(LinearLayout.VERTICAL);
            blockCurtain.setBackgroundColor(Color.rgb(25, 25, 25));
            blockCurtain.setGravity(Gravity.CENTER);
            blockCurtain.setPadding(dp(24), dp(24), dp(24), dp(24));
            blockCurtain.setClickable(true);
            blockCurtain.setFocusableInTouchMode(true);
            blockCurtain.setOnKeyListener((v, keyCode, event) -> true);

            TextView curtainMessage = new TextView(this);
            curtainMessage.setText("Página bloqueada");
            curtainMessage.setTextColor(Color.WHITE);
            curtainMessage.setTextSize(20f);
            curtainMessage.setGravity(Gravity.CENTER);
            blockCurtain.addView(curtainMessage);

            retryRedirectButton = new Button(this);
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
                            | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                    PixelFormat.OPAQUE
            );
            params.gravity = Gravity.TOP | Gravity.START;
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;

            try {
                windowManager.addView(blockCurtain, params);
                blockCurtain.requestFocus();
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

    @Override
    public void onInterrupt() {
        cancelSamsungRetry();
    }

    @Override
    public void onDestroy() {
        cancelSamsungRetry();
        mainHandler.removeCallbacksAndMessages(null);
        redirectPackage = null;
        hideBlockCurtain();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
