package com.mycroft.bloquearsites;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.pm.ApplicationInfo;
import android.os.Build;
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
    // Com a confirmação da família em andamento, a checagem do navegador desconhecido se repete.
    private static final int UNSUPPORTED_BROWSER_CHECKS = 3;
    private static final long IDENTIFY_INTERVAL_MS = 400L;
    // Prazo para achar a barra numa versão ainda não conferida do navegador, e o prazo curto depois
    // de uma falha na mesma versão.
    private static final long ADDRESS_BAR_CHECK_MS = 5000L;
    private static final long ADDRESS_BAR_RECHECK_MS = 1500L;
    // Filtro de pornografia: espera a página carregar antes de ler o texto dela, e só relê a
    // mesma página depois de um intervalo (mais curto nos buscadores, onde a pesquisa muda sem
    // mudar o domínio).
    private static final long PAGE_SCAN_DELAY_MS = 700L;
    private static final long SEARCH_PAGE_RESCAN_MS = 1500L;
    private static final long PAGE_RESCAN_MS = 5000L;

    private static final String FIREFOX_LOG_TAG = "BloquearSitesFirefox";
    private static final String REREAD_LOG_TAG = "BloquearSitesReread";
    private static final String ADULT_LOG_TAG = "BloquearSitesAdulto";

    private static final int LEGACY_EVENT_TYPES =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_FOCUSED
                    | AccessibilityEvent.TYPE_VIEW_CLICKED;

    private final UrlExtractor urlExtractor = new UrlExtractor();
    private BrowserDetector browserDetector;
    private VerifiedBrowsers verifiedBrowsers;
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
    private Runnable pendingAddressBarCheck;
    private String pendingAddressBarPackage;
    private Runnable pendingPageScan;
    private String pendingPageScanPackage;
    private String pendingPageScanKey = "";
    private String lastPageScanKey = "";
    private long lastPageScanAt = 0L;
    private String lastIdentifyPackage = "";
    private long lastIdentifyAt = 0L;
    private String lastUnsupportedBrowser = "";
    private long lastUnsupportedBrowserAt = 0L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        store = new BlockedSitesStore(this);
        browserDetector = new BrowserDetector(this);
        verifiedBrowsers = new VerifiedBrowsers(this);
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // A tela de pesquisa do Mi Browser só navega com a ação "Ir" do teclado, enviada
                // pela conexão de entrada do serviço (AddressBarNavigator).
                info.flags |= AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR;
            }
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
        boolean adultFilter = store.isAdultFilterEnabled();
        if (blockedSites.isEmpty() && !adultFilter) {
            cancelFirefoxRetry();
            cancelReread();
            cancelPageScan();
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
                // Já recusado antes: é fechado assim que mostra uma página, sem o novo prazo. Só
                // ganha o prazo se uma família acabou de ler a URL e aguarda a confirmação.
                if (IdentifiedBrowsers.isRejected(this, packageName)
                        && !IdentifiedBrowsers.isAwaitingConfirmation(packageName)
                        && NodeSearch.containsWebContent(browserRoot)) {
                    cancelUnsupportedBrowserCheck();
                    closeUnsupportedBrowser(packageName);
                    return;
                }
                scheduleUnsupportedBrowserCheck(packageName, browserRoot);
                return;
            }

            firefox = profile.getMethod() == BrowserProfile.Method.FIREFOX_TOOLBAR;
            rereads = profile.rereadsAfterEvent();
        }

        verifyAddressBar(packageName, profile, root);

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

        // A barra sem URL ainda pode mostrar a pesquisa (o Mi Browser mostra os termos buscados).
        if (adultFilter && profile != null) {
            AccessibilityNodeInfo browserRoot = packageName.equals(packageNameOf(root))
                    ? root
                    : applicationRootForPackage(packageName);
            String barText = urlExtractor.extractBarText(browserRoot, packageName, profile);
            if (AdultContentFilter.isExplicitText(barText)) {
                logAdult(packageName, "pesquisa na barra");
                blockWithDebounce(packageName, packageName + "|barra|" + barText);
                return;
            }
            requestPageScan(packageName, barText);
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
        // O destino do redirecionamento fica fora da lista para impedir loop. Uma busca explícita no
        // Google não é o destino (isRedirectDestination); as demais páginas do Google, como o Google
        // Imagens, ainda têm o texto conferido pelo filtro de pornografia.
        if (redirectController != null && redirectController.isRedirectDestination(visibleUrl)) {
            requestPageScan(packageName, visibleUrl);
            return;
        }

        String matchedDomain = DomainMatcher.findMatchedDomain(visibleUrl, blockedSites);
        boolean adult = matchedDomain == null
                && store != null
                && store.isAdultFilterEnabled()
                && AdultContentFilter.blocksUrl(visibleUrl);
        if (matchedDomain == null && !adult) {
            requestPageScan(packageName, visibleUrl);
            return;
        }

        if (adult) logAdult(packageName, "endereço");
        String host = DomainMatcher.extractHost(visibleUrl);
        blockWithDebounce(packageName, packageName + "|" + host);
    }

    private void blockWithDebounce(String packageName, String blockKey) {
        long now = SystemClock.elapsedRealtime();
        if (blockKey.equals(lastBlockedKey) && now - lastBlockedAt < BLOCK_DEBOUNCE_MS) {
            return;
        }

        lastBlockedKey = blockKey;
        lastBlockedAt = now;
        blockCurrentPage(packageName);
    }

    /**
     * Confere o texto da página aberta pelo filtro de pornografia (AdultContentFilter), um pouco
     * depois do evento, com a página já carregada. A mesma página só é conferida de novo após um
     * intervalo; nos buscadores o intervalo é curto, porque a pesquisa muda sem mudar o domínio.
     */
    private void requestPageScan(String packageName, String pageKey) {
        if (store == null || !store.isAdultFilterEnabled()) return;

        String key = packageName + "|" + (pageKey == null ? "" : pageKey);
        long rescanAfter = AdultContentFilter.isSearchHost(DomainMatcher.extractHost(pageKey))
                ? SEARCH_PAGE_RESCAN_MS
                : PAGE_RESCAN_MS;
        if (key.equals(lastPageScanKey)
                && SystemClock.elapsedRealtime() - lastPageScanAt < rescanAfter) {
            return;
        }

        pendingPageScanKey = key;
        if (pendingPageScan != null && packageName.equals(pendingPageScanPackage)) return;

        cancelPageScan();
        pendingPageScanPackage = packageName;
        pendingPageScan = () -> {
            pendingPageScan = null;
            lastPageScanKey = pendingPageScanKey;
            lastPageScanAt = SystemClock.elapsedRealtime();

            if (store == null || !store.isAdultFilterEnabled()) return;
            if (redirectController != null && redirectController.shouldIgnorePackage(packageName)) {
                return;
            }

            AccessibilityNodeInfo root = applicationRootForPackage(packageName);
            if (root == null) return;

            PageText page = PageText.collect(root);
            if (!AdultContentFilter.isAdultPage(page.texts, page.fields)) return;

            logAdult(packageName, "texto da página");
            blockWithDebounce(packageName, lastPageScanKey);
        };
        mainHandler.postDelayed(pendingPageScan, PAGE_SCAN_DELAY_MS);
    }

    private void cancelPageScan() {
        if (pendingPageScan == null) return;
        mainHandler.removeCallbacks(pendingPageScan);
        pendingPageScan = null;
    }

    private void logAdult(String packageName, String reason) {
        if (!isDebugBuild()) return;
        Log.d(ADULT_LOG_TAG, packageName + ": conteúdo adulto (" + reason + ")");
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
        postUnsupportedBrowserCheck(packageName, 1);
    }

    private void postUnsupportedBrowserCheck(String packageName, int check) {
        pendingUnsupportedPackage = packageName;
        pendingUnsupportedCheck = () -> {
            pendingUnsupportedCheck = null;
            if (BrowserProfiles.forPackage(packageName) != null) return;

            if (store == null) store = new BlockedSitesStore(this);
            if (!store.isBlockingActive()) return;
            Set<String> blockedSites = store.getSet();

            AccessibilityNodeInfo root = applicationRootForPackage(packageName);
            if (root == null || !NodeSearch.containsWebContent(root)) return;

            BrowserProfile family = IdentifiedBrowsers.identify(this, urlExtractor, packageName, root);
            if (family != null) {
                String url = urlExtractor.extract(root, null, packageName);
                if (url != null) handleVisibleUrl(packageName, url, blockedSites);
                return;
            }

            // Uma família leu a URL há pouco: a confirmação precisa de mais tempo.
            if (IdentifiedBrowsers.isAwaitingConfirmation(packageName)
                    && check < UNSUPPORTED_BROWSER_CHECKS) {
                postUnsupportedBrowserCheck(packageName, check + 1);
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

    /**
     * Rede de segurança para navegadores suportados ou identificados: a cada versão do navegador
     * (e do app), a barra da família precisa ser achada ao menos uma vez com uma página na tela.
     * Se uma atualização muda a barra e o método deixa de achá-la, o navegador é fechado em vez de
     * deixar os sites passarem.
     *
     * Basta a barra estar na tela, sem uma URL: nas páginas de resultado, o Mi Browser mostra os
     * termos pesquisados. Depois de achada, a barra só é conferida de novo na próxima atualização,
     * porque ela some de verdade ao rolar a página (Chrome) ou em tela cheia.
     */
    private void verifyAddressBar(
            String packageName,
            BrowserProfile profile,
            AccessibilityNodeInfo root
    ) {
        if (verifiedBrowsers == null) verifiedBrowsers = new VerifiedBrowsers(this);
        if (verifiedBrowsers.isVerified(packageName)) return;

        AccessibilityNodeInfo browserRoot = packageName.equals(packageNameOf(root))
                ? root
                : applicationRootForPackage(packageName);
        if (!NodeSearch.containsWebContent(browserRoot)) return;

        if (urlExtractor.hasAddressBar(browserRoot, packageName, profile)) {
            verifiedBrowsers.markVerified(packageName);
            if (packageName.equals(pendingAddressBarPackage)) cancelAddressBarCheck();
            return;
        }

        scheduleAddressBarCheck(
                packageName,
                verifiedBrowsers.hasFailed(packageName)
                        ? ADDRESS_BAR_RECHECK_MS
                        : ADDRESS_BAR_CHECK_MS
        );
    }

    private void scheduleAddressBarCheck(String packageName, long delayMs) {
        // Eventos seguidos não adiam o prazo, que conta desde a primeira página sem barra.
        if (pendingAddressBarCheck != null && packageName.equals(pendingAddressBarPackage)) return;

        cancelAddressBarCheck();
        pendingAddressBarPackage = packageName;
        pendingAddressBarCheck = () -> {
            pendingAddressBarCheck = null;
            if (verifiedBrowsers.isVerified(packageName)) return;

            if (store == null) store = new BlockedSitesStore(this);
            if (!store.isBlockingActive()) return;
            if (redirectController != null && redirectController.shouldIgnorePackage(packageName)) {
                return;
            }

            BrowserProfile profile = BrowserProfiles.forPackage(packageName);
            AccessibilityNodeInfo root = applicationRootForPackage(packageName);
            if (profile == null || root == null || !NodeSearch.containsWebContent(root)) return;

            if (urlExtractor.hasAddressBar(root, packageName, profile)) {
                verifiedBrowsers.markVerified(packageName);
                return;
            }

            verifiedBrowsers.markFailed(packageName);
            closeBrowser(
                    packageName,
                    labelOf(packageName)
                            + " foi fechado: o Bloquear Sites não conseguiu ler a barra de"
                            + " endereço desta versão."
            );
        };
        mainHandler.postDelayed(pendingAddressBarCheck, delayMs);
    }

    private void cancelAddressBarCheck() {
        if (pendingAddressBarCheck == null) return;
        mainHandler.removeCallbacks(pendingAddressBarCheck);
        pendingAddressBarCheck = null;
    }

    private void closeUnsupportedBrowser(String packageName) {
        closeBrowser(
                packageName,
                labelOf(packageName) + " não é suportado pelo Bloquear Sites e foi fechado."
        );
    }

    private String labelOf(String packageName) {
        if (browserDetector == null) browserDetector = new BrowserDetector(this);
        return browserDetector.labelOf(packageName);
    }

    private void closeBrowser(String packageName, String message) {
        long now = SystemClock.elapsedRealtime();
        if (packageName.equals(lastUnsupportedBrowser)
                && now - lastUnsupportedBrowserAt < UNSUPPORTED_BROWSER_DEBOUNCE_MS) {
            return;
        }
        lastUnsupportedBrowser = packageName;
        lastUnsupportedBrowserAt = now;

        performGlobalAction(GLOBAL_ACTION_HOME);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
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
                if (!store.isBlockingActive()) return;
                Set<String> blockedSites = store.getSet();

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
            if (!store.isBlockingActive()) return;
            Set<String> blockedSites = store.getSet();

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
        cancelAddressBarCheck();
        cancelPageScan();
    }

    @Override
    public void onDestroy() {
        cancelFirefoxRetry();
        cancelReread();
        cancelUnsupportedBrowserCheck();
        cancelAddressBarCheck();
        cancelPageScan();
        if (redirectController != null) {
            redirectController.destroy();
            redirectController = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
