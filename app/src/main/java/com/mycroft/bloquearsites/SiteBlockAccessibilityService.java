package com.mycroft.bloquearsites;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.Set;

public final class SiteBlockAccessibilityService extends AccessibilityService {
    private static final long BLOCK_DEBOUNCE_MS = 1200L;
    private static final long FIREFOX_RETRY_DELAY_MS = 220L;
    private static final int FIREFOX_RETRY_ATTEMPTS = 16;
    private static final int FIREFOX_STABLE_READS_REQUIRED = 3;
    private static final long REREAD_DELAY_MS = 250L;
    private static final long UNSUPPORTED_BROWSER_DEBOUNCE_MS = 1500L;
    private static final long UNSUPPORTED_BROWSER_GRACE_MS = 2000L;
    private static final long IDENTIFY_INTERVAL_MS = 400L;

    private static final String FIREFOX_LOG_TAG = "BloquearSitesFirefox";
    private static final String REREAD_LOG_TAG = "BloquearSitesReread";

    private static final int LEGACY_EVENT_TYPES =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_FOCUSED
                    | AccessibilityEvent.TYPE_VIEW_CLICKED;

    private final UrlExtractor urlExtractor = new UrlExtractor();
    private BrowserDetector browserDetector;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BlockedSitesStore store;
    private BlockRedirectController redirectController;
    private String lastBlockedKey = "";
    private long lastBlockedAt = 0L;
    private Runnable pendingFirefoxRetry;
    private String pendingFirefoxPackage;
    private Runnable pendingReread;
    private Runnable pendingUnsupportedCheck;
    private String pendingUnsupportedPackage;
    private String lastIdentifyPackage = "";
    private long lastIdentifyAt = 0L;
    private String lastUnsupportedBrowser = "";
    private long lastUnsupportedBrowserAt = 0L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        store = new BlockedSitesStore(this);
        browserDetector = new BrowserDetector(this);
        IdentifiedBrowsers.load(this);
        redirectController = new BlockRedirectController(this, mainHandler, urlExtractor);

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            // Recebemos todos os eventos para as famílias que precisam deles (Firefox e as que
            // releem a barra). As de VIEW_ID continuam filtradas pelo conjunto legado abaixo.
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

        BrowserProfile profile = BrowserProfiles.forPackage(packageName);
        boolean firefox = profile != null
                && profile.getMethod() == BrowserProfile.Method.FIREFOX_TOOLBAR;
        boolean rereads = profile != null && profile.rereadsAfterEvent();
        if (!firefox) cancelFirefoxRetry();
        if (!rereads) cancelReread();

        // O Firefox e as famílias que releem a barra podem sinalizar a mudança com tipos de evento
        // diferentes dos navegadores Chromium. Para eles, não descartamos eventos pelo tipo.
        if (!rereads && !firefox && !isLegacyEventType(event.getEventType())) {
            return;
        }

        Set<String> blockedSites = store.getSet();
        if (blockedSites.isEmpty()) {
            cancelFirefoxRetry();
            cancelReread();
            return;
        }

        // Navegadores sabidamente sem leitura confiável (Via, UC) são fechados assim que aparecem.
        if (profile == null && BrowserProfiles.isKnownUnsupported(packageName)) {
            closeUnsupportedBrowser(packageName);
            return;
        }

        if (browserDetector == null) browserDetector = new BrowserDetector(this);
        if (profile == null && browserDetector.isBrowser(packageName)) {
            // Navegador fora da lista: é testado com o método de cada família. Encaixado, passa a
            // usar a família; se nenhuma se encaixa, é bloqueado enquanto houver sites na lista,
            // para não servir de desvio.
            long now = SystemClock.elapsedRealtime();
            if (packageName.equals(lastIdentifyPackage) && now - lastIdentifyAt < IDENTIFY_INTERVAL_MS) {
                return;
            }
            lastIdentifyPackage = packageName;
            lastIdentifyAt = now;

            AccessibilityNodeInfo browserRoot = packageName.equals(packageNameOf(root))
                    ? root
                    : applicationRootForPackage(packageName);
            profile = IdentifiedBrowsers.identify(this, urlExtractor, packageName, browserRoot);
            if (profile == null) {
                scheduleUnsupportedBrowserCheck(packageName, browserRoot);
                return;
            }

            firefox = profile.getMethod() == BrowserProfile.Method.FIREFOX_TOOLBAR;
            rereads = profile.rereadsAfterEvent();
        }

        if (firefox) {
            // Texto digitado e sugestões nunca contam como URL navegada. Todo evento do Firefox
            // apenas inicia uma confirmação curta da URL exibida pela toolbar da janela ativa.
            scheduleFirefoxRetry(packageName);
            return;
        }

        AccessibilityNodeInfo extractionRoot = root;
        if (rereads && !sameWindow(event, root)) {
            // Se o evento e a raiz apontam para janelas diferentes, a fonte do evento é mais
            // confiável agora, e a releitura olha a janela do navegador depois.
            extractionRoot = null;
        }

        String visibleUrl = urlExtractor.extract(extractionRoot, source, packageName);

        if (visibleUrl != null) {
            cancelReread();
            handleVisibleUrl(packageName, visibleUrl, blockedSites);
            return;
        }

        if (rereads) {
            scheduleReread(packageName);
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

        // Eventos podem vir de janelas auxiliares (teclado, pop-ups, a própria cortina). Se a fonte
        // ou a janela ativa pertencem a um navegador conhecido, priorizamos esse navegador.
        if (BrowserProfiles.forPackage(sourcePackage) != null) return sourcePackage;
        if (BrowserProfiles.forPackage(rootPackage) != null) return rootPackage;

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

    /**
     * Confere de novo, após um intervalo, um navegador que não se encaixou em nenhuma família. Só
     * bloqueia se a página continua na tela e ainda nenhuma família se encaixa: um navegador pode
     * mostrar a URL na barra depois do conteúdo.
     */
    private void scheduleUnsupportedBrowserCheck(
            String packageName,
            AccessibilityNodeInfo browserRoot
    ) {
        // Sem página web na tela o app ainda não está navegando, ou não é de fato um navegador
        // (gerenciadores de download também abrem links).
        if (!NodeSearch.containsWebContent(browserRoot)) return;
        if (pendingUnsupportedCheck != null && packageName.equals(pendingUnsupportedPackage)) return;

        cancelUnsupportedBrowserCheck();
        pendingUnsupportedPackage = packageName;
        pendingUnsupportedCheck = () -> {
            pendingUnsupportedCheck = null;
            if (BrowserProfiles.forPackage(packageName) != null) return;

            if (store == null) store = new BlockedSitesStore(this);
            Set<String> blockedSites = store.getSet();
            if (blockedSites.isEmpty()) return;

            AccessibilityNodeInfo root = applicationRootForPackage(packageName);
            if (root == null || !NodeSearch.containsWebContent(root)) return;

            BrowserProfile family = IdentifiedBrowsers.identify(this, urlExtractor, packageName, root);
            if (family != null) {
                String url = urlExtractor.extract(root, null, packageName);
                if (url != null) handleVisibleUrl(packageName, url, blockedSites);
                return;
            }

            IdentifiedBrowsers.markRejected(this, packageName);
            closeUnsupportedBrowser(packageName);
        };
        mainHandler.postDelayed(pendingUnsupportedCheck, UNSUPPORTED_BROWSER_GRACE_MS);
    }

    private void cancelUnsupportedBrowserCheck() {
        if (pendingUnsupportedCheck == null) return;
        mainHandler.removeCallbacks(pendingUnsupportedCheck);
        pendingUnsupportedCheck = null;
    }

    private void closeUnsupportedBrowser(String packageName) {
        long now = SystemClock.elapsedRealtime();
        if (packageName.equals(lastUnsupportedBrowser)
                && now - lastUnsupportedBrowserAt < UNSUPPORTED_BROWSER_DEBOUNCE_MS) {
            return;
        }
        lastUnsupportedBrowser = packageName;
        lastUnsupportedBrowserAt = now;

        performGlobalAction(GLOBAL_ACTION_HOME);
        Toast.makeText(
                this,
                browserDetector.labelOf(packageName)
                        + " não é suportado pelo Bloquear Sites e foi fechado.",
                Toast.LENGTH_LONG
        ).show();
    }

    private void scheduleFirefoxRetry(String expectedPackage) {
        // Eventos sucessivos do mesmo navegador não reiniciam a sequência. A URL precisa aparecer
        // estável na toolbar de exibição antes de ser considerada realmente carregada.
        if (pendingFirefoxRetry != null && expectedPackage.equals(pendingFirefoxPackage)) return;

        cancelFirefoxRetry();
        pendingFirefoxPackage = expectedPackage;
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

            int attemptNumber = FIREFOX_RETRY_ATTEMPTS - attemptsRemaining + 1;
            String nextHost = previousHost;
            int nextStableReads = stableReads;

            AccessibilityNodeInfo root = applicationRootForPackage(expectedPackage);
            if (root != null) {
                if (store == null) store = new BlockedSitesStore(this);
                Set<String> blockedSites = store.getSet();
                if (blockedSites.isEmpty()) return;

                String loadedUrl = urlExtractor.extractFirefoxDisplayedUrl(root, expectedPackage);
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

                logFirefoxRetry(attemptNumber, attemptsRemaining, root, host, nextStableReads);

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
                logFirefoxRetry(attemptNumber, attemptsRemaining, null, null, 0);
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

    private void cancelFirefoxRetry() {
        if (pendingFirefoxRetry == null) return;
        mainHandler.removeCallbacks(pendingFirefoxRetry);
        pendingFirefoxRetry = null;
    }

    private void logFirefoxRetry(
            int attemptNumber,
            int attemptsRemaining,
            AccessibilityNodeInfo root,
            String host,
            int stableReads
    ) {
        if (!isDebugBuild()) return;

        Log.d(
                FIREFOX_LOG_TAG,
                "retry=" + attemptNumber + "/" + FIREFOX_RETRY_ATTEMPTS
                        + " rootWindow=" + windowIdOf(root)
                        + " rootPkg=" + packageNameOf(root)
                        + " host=" + (host == null ? "<nao-detectado>" : host)
                        + " stableReads=" + stableReads
        );

        // A árvore completa só é descrita no início e no fim da sequência, para não pesar
        // na thread principal enquanto a página carrega.
        if (host == null && root != null && (attemptNumber == 1 || attemptsRemaining == 1)) {
            Log.d(
                    FIREFOX_LOG_TAG,
                    "toolbarCandidates=" + urlExtractor.describeFirefoxToolbarCandidates(root)
            );
        }
    }

    private void scheduleReread(String expectedPackage) {
        cancelReread();

        pendingReread = () -> {
            pendingReread = null;

            AccessibilityNodeInfo root = applicationRootForPackage(expectedPackage);
            if (root == null) {
                logReread(expectedPackage, null);
                return;
            }

            if (store == null) store = new BlockedSitesStore(this);
            Set<String> blockedSites = store.getSet();
            if (blockedSites.isEmpty()) return;

            String visibleUrl = urlExtractor.extract(root, null, expectedPackage);
            logReread(expectedPackage, visibleUrl);

            if (visibleUrl != null) {
                handleVisibleUrl(expectedPackage, visibleUrl, blockedSites);
            }
        };

        mainHandler.postDelayed(pendingReread, REREAD_DELAY_MS);
    }

    private void cancelReread() {
        if (pendingReread == null) return;
        mainHandler.removeCallbacks(pendingReread);
        pendingReread = null;
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

    private boolean isDebugBuild() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private void logReread(String packageName, String visibleUrl) {
        if (!isDebugBuild()) return;

        BrowserProfile profile = BrowserProfiles.forPackage(packageName);
        String host = DomainMatcher.extractHost(visibleUrl);
        Log.d(
                REREAD_LOG_TAG,
                "family=" + (profile == null ? "<desconhecida>" : profile.getFamily())
                        + " pkg=" + packageName
                        + " host=" + (host == null ? "<nao-detectado>" : host)
        );
    }

    private void blockCurrentPage(String packageName) {
        cancelFirefoxRetry();
        cancelReread();

        if (redirectController == null) {
            redirectController = new BlockRedirectController(this, mainHandler, urlExtractor);
        }
        redirectController.start(packageName);
    }

    @Override
    public void onInterrupt() {
        cancelFirefoxRetry();
        cancelReread();
        cancelUnsupportedBrowserCheck();
    }

    @Override
    public void onDestroy() {
        cancelFirefoxRetry();
        cancelReread();
        cancelUnsupportedBrowserCheck();
        if (redirectController != null) {
            redirectController.destroy();
            redirectController = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
