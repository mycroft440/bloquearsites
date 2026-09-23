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
    // Opera GX e Mi Browser: tempo para o Google aparecer depois da troca pela barra ou da aba nova.
    private static final long REDIRECT_TIMEOUT_MS = 5000L;

    private final AccessibilityService service;
    private final Handler mainHandler;
    private final UrlExtractor urlExtractor;
    private final AddressBarNavigator addressBarNavigator;

    private WindowManager windowManager;
    private LinearLayout blockCurtain;
    private WindowManager.LayoutParams curtainParams;
    private boolean curtainPassThrough;
    private Button retryRedirectButton;
    private String redirectPackage;
    private boolean redirectFailed;
    private long lastRedirectAt = -REDIRECT_DEBOUNCE_MS;
    private long curtainVisibleUntil = 0L;
    private long redirectDeadline = 0L;
    private boolean newTabFallbackUsed;
    // O Opera GX e o Mi Browser no novo estilo de página editam o endereço numa tela de pesquisa
    // própria; os cuidados com essa tela (fechá-la numa falha, esperar que ela feche e o limite de
    // tempo) valem só para eles.
    private boolean searchScreenBrowser;

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
        this.addressBarNavigator = new AddressBarNavigator(service, mainHandler);
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
        addressBarNavigator.cancel();
        redirectPackage = packageName;
        redirectFailed = false;
        newTabFallbackUsed = false;
        searchScreenBrowser = AddressBarNavigator.opensSearchScreen(
                BrowserProfiles.forPackage(packageName));
        lastRedirectAt = -REDIRECT_DEBOUNCE_MS;
        curtainVisibleUntil = SystemClock.elapsedRealtime() + CURTAIN_MAX_VISIBLE_MS;
        redirectDeadline = SystemClock.elapsedRealtime() + REDIRECT_TIMEOUT_MS;

        // Enquanto a barra é preenchida a cortina deixa toques passarem: no Firefox a edição só
        // abre com um toque simulado, que a cortina interceptaria. Uma cortina nova já nasce assim,
        // para não disputar com o toque a atualização da janela.
        setCurtainPassThrough(true);
        showBlockCurtain();
        mainHandler.postDelayed(curtainTimeoutRunnable, CURTAIN_MAX_VISIBLE_MS);

        // O Google é aberto na própria aba do site bloqueado, pela barra de endereço. A checagem
        // do destino só começa quando a barra terminar de ser preenchida.
        if (addressBarNavigator.start(
                packageName,
                REDIRECT_URL,
                this::onAddressBarNavigationFinished)) {
            // O Mi Browser só abre a tela de pesquisa no novo estilo de página, pela barra de baixo.
            searchScreenBrowser = addressBarNavigator.isUsingSearchScreen();
            lastRedirectAt = SystemClock.elapsedRealtime();
            updateRetryButton();
            return;
        }

        setCurtainPassThrough(false);
        openGoogleInNewTab();
        mainHandler.postDelayed(redirectCheckRunnable, REDIRECT_CHECK_DELAY_MS);
    }

    void destroy() {
        addressBarNavigator.cancel();
        mainHandler.removeCallbacks(redirectCheckRunnable);
        mainHandler.removeCallbacks(curtainTimeoutRunnable);
        redirectPackage = null;
        curtainVisibleUntil = 0L;
        hideBlockCurtain();
    }

    private void openGoogleInNewTab() {
        newTabFallbackUsed = true;
        redirectDeadline = SystemClock.elapsedRealtime() + REDIRECT_TIMEOUT_MS;

        // A aba nova não tira o site bloqueado da aba atual; no Firefox, Voltar sai dele antes.
        if (BrowserProfiles.isFirefox(redirectPackage)) {
            escapeFirefoxBlockedPage();
        }
        openGoogle();
    }

    /**
     * Fecha a tela de pesquisa (Opera GX e Mi Browser) que a troca pela barra deixou aberta com o
     * site bloqueado, para a aba nova não ficar escondida atrás dela.
     */
    private void closeAddressEditor() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
    }

    private void escapeFirefoxBlockedPage() {
        boolean wentBack = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        if (!wentBack) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        }
    }

    private void onAddressBarNavigationFinished(boolean submitted, boolean touchedBar) {
        if (redirectPackage == null) return;
        setCurtainPassThrough(false);

        if (submitted) {
            redirectDeadline = SystemClock.elapsedRealtime() + REDIRECT_TIMEOUT_MS;
        } else {
            // Se a barra não pôde ser usada, cai no comportamento antigo: Google em uma aba nova.
            // Com uma tela de pesquisa (Opera GX e Mi Browser), antes fecha a que ficou aberta.
            if (touchedBar && searchScreenBrowser) closeAddressEditor();
            lastRedirectAt = -REDIRECT_DEBOUNCE_MS;
            openGoogleInNewTab();
        }
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

        // Enquanto a barra é preenchida, ela já mostra google.com sem a navegação ter ocorrido.
        if (addressBarNavigator.isRunning()) return;

        AccessibilityNodeInfo root = foregroundApplicationRoot();
        String packageName = packageNameOf(root);
        boolean browserInFront = redirectPackage.equals(packageName);

        if (browserInFront) {
            // Com a tela de pesquisa ainda aberta (Opera GX e Mi Browser), google.com é só o texto
            // digitado: a chegada só conta com ela fechada.
            String visibleUrl = urlExtractor.extract(root, null, packageName);
            if (isRedirectDestination(visibleUrl)
                    && !(searchScreenBrowser && isEditingAddress(root, packageName))) {
                finishRedirect();
                return;
            }
        } else {
            // Sem uma janela ativa confiável, ou fora do navegador que está redirecionando,
            // a cortina deve falhar aberta para nunca prender a interface do aparelho.
            hideBlockCurtain();
        }

        if (searchScreenBrowser && SystemClock.elapsedRealtime() >= redirectDeadline) {
            handleRedirectTimeout(browserInFront);
            return;
        }

        updateRetryButton();
        mainHandler.postDelayed(redirectCheckRunnable, REDIRECT_CHECK_DELAY_MS);
    }

    /**
     * O Google não apareceu a tempo. Se a troca pela barra falhou em silêncio (o Enter não
     * navegou), tenta a aba nova; se a aba nova também falhou, ou o usuário saiu do navegador,
     * libera a detecção, que volta a bloquear se o site continuar na tela.
     */
    private void handleRedirectTimeout(boolean browserInFront) {
        if (browserInFront && !newTabFallbackUsed) {
            Log.w(LOG_TAG, "Google não apareceu após a troca pela barra; abrindo aba nova.");
            closeAddressEditor();
            lastRedirectAt = -REDIRECT_DEBOUNCE_MS;
            openGoogleInNewTab();
            mainHandler.postDelayed(redirectCheckRunnable, REDIRECT_CHECK_DELAY_MS);
            return;
        }

        Log.w(LOG_TAG, "Google não apareceu; liberando a detecção.");
        mainHandler.removeCallbacks(curtainTimeoutRunnable);
        redirectPackage = null;
        redirectFailed = false;
        curtainVisibleUntil = 0L;
        hideBlockCurtain();
    }

    private boolean isEditingAddress(AccessibilityNodeInfo root, String packageName) {
        return NodeSearch.findFirst(root, node ->
                node.isFocused()
                        && node.isEditable()
                        && NodeSearch.isVisibleInPackage(node, packageName)) != null;
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
            if (curtainPassThrough) {
                params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }

            try {
                windowManager.addView(blockCurtain, params);
                curtainParams = params;
            } catch (RuntimeException e) {
                blockCurtain = null;
                retryRedirectButton = null;
                Log.w(LOG_TAG, "Não foi possível exibir a cortina de bloqueio.", e);
            }
        }

        updateRetryButton();
    }

    private void setCurtainPassThrough(boolean passThrough) {
        curtainPassThrough = passThrough;
        if (blockCurtain == null || curtainParams == null || windowManager == null) return;

        if (passThrough) {
            curtainParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            curtainParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }

        try {
            windowManager.updateViewLayout(blockCurtain, curtainParams);
        } catch (RuntimeException e) {
            Log.w(LOG_TAG, "Não foi possível atualizar a cortina de bloqueio.", e);
        }
    }

    private void updateRetryButton() {
        if (retryRedirectButton == null) return;

        boolean canRetry = redirectFailed
                || SystemClock.elapsedRealtime() - lastRedirectAt >= SHOW_RETRY_DELAY_MS;
        retryRedirectButton.setVisibility(canRetry ? View.VISIBLE : View.GONE);
    }

    private void expireBlockCurtain() {
        curtainVisibleUntil = 0L;

        // Se o Firefox não expôs a URL de destino para confirmação, não mantemos o serviço
        // preso em modo de redirecionamento. O destino Google já é ignorado pela regra normal.
        if (BrowserProfiles.isFirefox(redirectPackage)) {
            mainHandler.removeCallbacks(redirectCheckRunnable);
            redirectPackage = null;
            redirectFailed = false;
        }

        hideBlockCurtain();
    }

    private void finishRedirect() {
        addressBarNavigator.cancel();
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
            curtainParams = null;
            retryRedirectButton = null;
        }
    }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }
}
