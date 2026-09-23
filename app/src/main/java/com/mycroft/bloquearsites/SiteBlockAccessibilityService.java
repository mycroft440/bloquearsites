package com.mycroft.bloquearsites;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.Set;

public final class SiteBlockAccessibilityService extends AccessibilityService {
    private static final long BLOCK_DEBOUNCE_MS = 1200L;
    private static final long FIREFOX_RETRY_DELAY_MS = 220L;
    private static final int FIREFOX_RETRY_ATTEMPTS = 16;
    private static final int FIREFOX_STABLE_READS_REQUIRED = 3;
    private static final long SAMSUNG_RETRY_DELAY_MS = 250L;

    private static final String FIREFOX_CLASSIC_PACKAGE = "org.mozilla.firefox";
    private static final String[] FIREFOX_DISPLAY_VIEW_IDS = {
            "url_bar_title",
            "mozac_browser_toolbar_url_view"
    };
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
    private BlockRedirectController redirectController;
    private String lastBlockedKey = "";
    private long lastBlockedAt = 0L;
    private Runnable pendingFirefoxRetry;
    private Runnable pendingSamsungRetry;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        store = new BlockedSitesStore(this);
        redirectController = new BlockRedirectController(this, mainHandler, urlExtractor);

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            // Recebemos todos os eventos para não perder mudanças específicas do Samsung Internet
            // e do Firefox. Os demais navegadores continuam filtrados pelo conjunto legado abaixo.
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

        // O redirecionamento é um fluxo independente. Se acabou de confirmar google.com,
        // descartamos este evento para não reutilizar uma árvore anterior ao redirecionamento.
        if (redirectController != null && redirectController.refreshBeforeDetection()) {
            return;
        }

        if (store == null) store = new BlockedSitesStore(this);

        AccessibilityNodeInfo source = event.getSource();
        AccessibilityNodeInfo root = getRootInActiveWindow();

        String packageName = resolvePackageName(event, source, root);
        if (packageName == null || getPackageName().equals(packageName)) return;

        // Enquanto um navegador está sendo redirecionado, os eventos dele pertencem ao
        // controlador de redirecionamento e não voltam para a lógica de detecção/bloqueio.
        if (redirectController != null && redirectController.shouldIgnorePackage(packageName)) {
            return;
        }

        boolean firefoxClassic = isFirefoxClassicPackage(packageName);
        boolean samsung = isSamsungPackage(packageName);
        if (!firefoxClassic) cancelFirefoxRetry();

        // Firefox e Samsung podem sinalizar mudanças relevantes com tipos de evento diferentes
        // dos navegadores Chromium. Para eles, não descartamos eventos antes de ler a URL.
        if (!samsung && !firefoxClassic && !isLegacyEventType(event.getEventType())) {
            return;
        }

        Set<String> blockedSites = store.getSet();
        if (blockedSites.isEmpty()) {
            if (firefoxClassic) cancelFirefoxRetry();
            if (samsung) cancelSamsungRetry();
            return;
        }

        if (firefoxClassic) {
            // Texto digitado e sugestões nunca contam como URL navegada. Todo evento do Firefox
            // apenas inicia uma confirmação curta da URL exibida pela toolbar da janela ativa.
            scheduleFirefoxRetry(packageName);
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

        // O Firefox pode gerar eventos cuja fonte imediata pertence a uma janela auxiliar.
        // Se a fonte ou a janela ativa pertencem ao Firefox, priorizamos o navegador ativo.
        if (isFirefoxClassicPackage(sourcePackage)) return sourcePackage;
        if (isFirefoxClassicPackage(rootPackage)) return rootPackage;

        if (eventPackage != null) return eventPackage;
        if (sourcePackage != null) return sourcePackage;
        return rootPackage;
    }

    private void handleVisibleUrl(
            String packageName,
            String visibleUrl,
            Set<String> blockedSites
    ) {
        // O destino do redirecionamento fica fora da regra de bloqueio para impedir loop.
        if (redirectController != null && redirectController.isRedirectDestination(visibleUrl)) {
            return;
        }

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
        blockCurrentPage(packageName);
    }

    private void scheduleFirefoxRetry(String expectedPackage) {
        // Eventos sucessivos não reiniciam a sequência. A URL precisa aparecer estável na toolbar
        // de exibição antes de ser considerada realmente carregada.
        if (pendingFirefoxRetry != null) return;
        scheduleFirefoxRetryAttempt(
                expectedPackage,
                FIREFOX_RETRY_ATTEMPTS,
                null,
                0
        );
    }

    private void scheduleFirefoxRetryAttempt(
            String expectedPackage,
            int attemptsRemaining,
            String previousHost,
            int stableReads
    ) {
        pendingFirefoxRetry = () -> {
            pendingFirefoxRetry = null;

            String nextHost = previousHost;
            int nextStableReads = stableReads;

            AccessibilityNodeInfo root = applicationRootForPackage(expectedPackage);
            if (root != null) {
                if (store == null) store = new BlockedSitesStore(this);
                Set<String> blockedSites = store.getSet();
                if (blockedSites.isEmpty()) return;

                String loadedUrl = extractFirefoxDisplayedUrl(root, expectedPackage);
                String host = DomainMatcher.extractHost(loadedUrl);

                if (host == null) {
                    nextHost = null;
                    nextStableReads = 0;
                } else if (host.equals(previousHost)) {
                    nextHost = host;
                    nextStableReads = stableReads + 1;
                } else {
                    nextHost = host;
                    nextStableReads = 1;
                }

                if (loadedUrl != null
                        && nextStableReads >= FIREFOX_STABLE_READS_REQUIRED) {
                    handleVisibleUrl(expectedPackage, loadedUrl, blockedSites);

                    if (redirectController != null
                            && redirectController.shouldIgnorePackage(expectedPackage)) {
                        return;
                    }
                }
            } else {
                nextHost = null;
                nextStableReads = 0;
            }

            if (attemptsRemaining > 1) {
                scheduleFirefoxRetryAttempt(
                        expectedPackage,
                        attemptsRemaining - 1,
                        nextHost,
                        nextStableReads
                );
            }
        };

        mainHandler.postDelayed(pendingFirefoxRetry, FIREFOX_RETRY_DELAY_MS);
    }

    private String extractFirefoxDisplayedUrl(
            AccessibilityNodeInfo root,
            String packageName
    ) {
        if (root == null || packageName == null) return null;

        int expectedWindowId = root.getWindowId();
        for (String idName : FIREFOX_DISPLAY_VIEW_IDS) {
            String exactId = packageName + ":id/" + idName;
            try {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(exactId);
                if (nodes == null) continue;

                for (AccessibilityNodeInfo node : nodes) {
                    if (node == null || !node.isVisibleToUser()) continue;
                    if (!packageName.equals(packageNameOf(node))) continue;

                    int nodeWindowId = node.getWindowId();
                    if (expectedWindowId >= 0
                            && nodeWindowId >= 0
                            && nodeWindowId != expectedWindowId) {
                        continue;
                    }

                    String text = cleanNodeText(node.getText());
                    if (DomainMatcher.extractHost(text) != null) return text;

                    String description = cleanNodeText(node.getContentDescription());
                    if (DomainMatcher.extractHost(description) != null) return description;
                }
            } catch (RuntimeException ignored) {
                // O Firefox pode recriar a toolbar durante a leitura; a próxima tentativa cobre isso.
            }
        }

        return null;
    }

    private String cleanNodeText(CharSequence value) {
        if (value == null || value.length() == 0) return null;
        String cleaned = value.toString().trim();
        return cleaned.isEmpty() ? null : cleaned;
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

    private AccessibilityNodeInfo applicationRootForPackage(String packageName) {
        if (packageName == null) return null;

        AccessibilityNodeInfo focusedCandidate = null;

        try {
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }

                AccessibilityNodeInfo candidate = window.getRoot();
                if (!packageName.equals(packageNameOf(candidate))) continue;

                if (window.isActive()) {
                    return candidate;
                }

                if (window.isFocused()) {
                    focusedCandidate = candidate;
                }
            }
        } catch (RuntimeException ignored) {
            // Algumas versões do Android podem invalidar uma janela enquanto percorremos a lista.
        }

        AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
        if (packageName.equals(packageNameOf(activeRoot))) {
            return activeRoot;
        }

        return focusedCandidate;
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

    private void blockCurrentPage(String packageName) {
        cancelFirefoxRetry();
        cancelSamsungRetry();

        if (redirectController == null) {
            redirectController = new BlockRedirectController(this, mainHandler, urlExtractor);
        }
        redirectController.start(packageName);
    }

    @Override
    public void onInterrupt() {
        cancelFirefoxRetry();
        cancelSamsungRetry();
    }

    @Override
    public void onDestroy() {
        cancelFirefoxRetry();
        cancelSamsungRetry();
        if (redirectController != null) {
            redirectController.destroy();
            redirectController = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
