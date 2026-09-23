package com.mycroft.bloquearsites;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.pm.ApplicationInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * Troca o site da aba atual digitando o destino na própria barra de endereço do navegador.
 *
 * Um Intent ACTION_VIEW só diz qual endereço abrir, não em qual aba, e os navegadores abrem links
 * vindos de outros apps em uma aba nova (o Chrome atual ignora até o pedido de reaproveitar a aba,
 * pela flag DontClobberTabsWithChromeAppId). A barra é o único caminho para trocar o site da aba
 * bloqueada.
 *
 * Fluxo: tocar na barra, digitar o destino, conferir o texto e confirmar com o Enter de
 * acessibilidade. A barra é localizada pelo método da família do navegador: IDs do perfil, testTags
 * do Compose (Firefox) ou a estrutura da tela (Opera GX).
 *
 * O Opera GX (família lida pela estrutura) abre uma tela de pesquisa própria para editar o
 * endereço. Só para ele, o campo é achado também como o campo editável com foco, a busca é
 * repetida enquanto a tela aparece e o texto é digitado de novo se o navegador o reescrever. Os
 * demais navegadores seguem o fluxo direto, que já funcionava.
 */
final class AddressBarNavigator {
    interface Callback {
        /**
         * @param submitted  se o destino foi digitado e confirmado
         * @param touchedBar se a barra foi tocada; numa falha, o editor de endereço pode ter
         *                   ficado aberto com o site bloqueado
         */
        void onFinished(boolean submitted, boolean touchedBar);
    }

    // Os navegadores trocam a barra para o modo de edição e processam o autocomplete de forma
    // assíncrona; a tela de pesquisa do Opera GX pode levar mais tempo para aparecer.
    private static final long FOCUS_DELAY_MS = 300L;
    private static final long RETRY_DELAY_MS = 250L;
    private static final int SEARCH_SCREEN_FIND_ATTEMPTS = 5;
    private static final long SUBMIT_DELAY_MS = 300L;
    private static final long TAP_DURATION_MS = 50L;
    private static final String LOG_TAG = "BloquearSitesRedirect";

    private final AccessibilityService service;
    private final Handler mainHandler;

    private String packageName;
    private BrowserProfile profile;
    private String url;
    private Callback callback;
    private Runnable pendingStep;
    private boolean touchedBar;
    private boolean searchScreen;

    AddressBarNavigator(AccessibilityService service, Handler mainHandler) {
        this.service = service;
        this.mainHandler = mainHandler;
    }

    /**
     * Inicia a navegação. Retorna false quando ela não pode começar; nesse caso o callback
     * não é chamado e quem chamou deve usar outra forma de redirecionar.
     */
    boolean start(String packageName, String url, Callback callback) {
        cancel();

        // ACTION_IME_ENTER só existe a partir do Android 11. Sem ele não há como confirmar a URL.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;

        BrowserProfile browserProfile = BrowserProfiles.forPackage(packageName);
        if (browserProfile == null) return false;

        this.packageName = packageName;
        this.profile = browserProfile;
        this.url = url;
        this.callback = callback;
        this.touchedBar = false;
        this.searchScreen = opensSearchScreen(browserProfile);

        AccessibilityNodeInfo root = browserRoot();
        if (root == null) return false;

        // Chrome, Samsung, Mi Browser, Opera e DuckDuckGo exibem a URL no próprio campo editável.
        AccessibilityNodeInfo editField = findEditField(root);
        if (editField != null) {
            if (!editField.isFocused()
                    && !editField.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    && !editField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                return false;
            }
        } else {
            // Nos Custom Tabs do Chrome o url_bar é só leitura, e tocar nele não abre edição.
            if (BrowserProfiles.isChromium(packageName)) return false;

            // Firefox, Yandex e Opera GX exibem a URL num elemento só de leitura; tocar nele abre a
            // edição.
            AccessibilityNodeInfo display = findDisplay(root);
            if (display == null
                    || !(display.performAction(AccessibilityNodeInfo.ACTION_CLICK) || tap(display))) {
                log("barra não encontrada ou não tocada");
                return false;
            }
        }

        touchedBar = true;
        schedule(() -> typeUrl(1), FOCUS_DELAY_MS);
        return true;
    }

    boolean isRunning() {
        return pendingStep != null;
    }

    void cancel() {
        if (pendingStep != null) mainHandler.removeCallbacks(pendingStep);
        pendingStep = null;
        callback = null;
    }

    /**
     * A URL da toolbar em Compose do Firefox não expõe ação de clique: o clearAndSetSemantics do
     * mesmo nó descarta a semântica do clickable. O TalkBack também recorre a um toque simulado.
     */
    private boolean tap(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return false;

        Path path = new Path();
        path.moveTo(bounds.exactCenterX(), bounds.exactCenterY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
                .build();
        return service.dispatchGesture(gesture, null, null);
    }

    private void typeUrl(int attempt) {
        AccessibilityNodeInfo editField = findEditField(browserRoot());
        if (editField == null) {
            if (searchScreen && attempt < SEARCH_SCREEN_FIND_ATTEMPTS) {
                schedule(() -> typeUrl(attempt + 1), RETRY_DELAY_MS);
            } else {
                log("campo de edição não encontrado após " + attempt + " tentativas");
                finish(false);
            }
            return;
        }

        // Sem foco, o Chrome ignora o texto no autocomplete e o Enter não navegaria.
        if (!editField.isFocused()) editField.performAction(AccessibilityNodeInfo.ACTION_FOCUS);

        boolean typed = setText(editField);
        log("campo " + describe(editField) + " setText=" + typed);
        if (!typed) {
            finish(false);
            return;
        }

        schedule(() -> submit(false), SUBMIT_DELAY_MS);
    }

    private void submit(boolean retyped) {
        AccessibilityNodeInfo editField = findEditField(browserRoot());
        if (editField == null) {
            log("campo sumiu antes do Enter");
            finish(false);
            return;
        }

        // Se o navegador reescreveu o campo depois de abri-lo (por exemplo, com a URL atual
        // selecionada), o Enter recarregaria o site bloqueado: digita de novo uma vez.
        if (!hasTypedUrl(editField)) {
            if (searchScreen && !retyped && setText(editField)) {
                log("texto não ficou no campo; digitando de novo");
                schedule(() -> submit(true), SUBMIT_DELAY_MS);
            } else {
                log("texto não ficou no campo: " + describe(editField));
                finish(false);
            }
            return;
        }

        boolean submitted = (editField.isFocused()
                || editField.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && editField.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        log("Enter=" + submitted);
        finish(submitted);
    }

    private boolean setText(AccessibilityNodeInfo editField) {
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, url);
        return editField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
    }

    private boolean hasTypedUrl(AccessibilityNodeInfo editField) {
        CharSequence text = editField.getText();
        String typedHost = DomainMatcher.extractHost(text == null ? null : text.toString());
        return typedHost != null && typedHost.equals(DomainMatcher.extractHost(url));
    }

    private void schedule(Runnable step, long delayMs) {
        pendingStep = () -> {
            pendingStep = null;
            step.run();
        };
        mainHandler.postDelayed(pendingStep, delayMs);
    }

    private void finish(boolean submitted) {
        Callback finished = callback;
        callback = null;
        pendingStep = null;
        if (finished != null) finished.onFinished(submitted, touchedBar);
    }

    private AccessibilityNodeInfo findEditField(AccessibilityNodeInfo root) {
        if (root == null) return null;

        AccessibilityNodeInfo known = findKnownEditField(root);
        if (known != null || !touchedBar || !searchScreen) return known;

        // Na tela de pesquisa do Opera GX, o campo de endereço é o campo editável com foco; em
        // último caso, o primeiro campo editável. Os dois ficam sempre fora do conteúdo da página.
        AccessibilityNodeInfo focused = NodeSearch.findFirst(root, node ->
                node.isFocused()
                        && node.isEditable()
                        && NodeSearch.isVisibleInPackage(node, packageName));
        if (focused != null) return focused;

        return NodeSearch.findFirst(root, node ->
                node.isEditable() && NodeSearch.isVisibleInPackage(node, packageName));
    }

    private AccessibilityNodeInfo findKnownEditField(AccessibilityNodeInfo root) {
        switch (profile.getMethod()) {
            case TOOLBAR_STRUCTURE:
                return ToolbarStructure.findEditField(root, packageName);
            case FIREFOX_TOOLBAR:
                AccessibilityNodeInfo viewField = findByProfileIds(root, true);
                if (viewField != null) return viewField;
                return NodeSearch.findFirst(root, node ->
                        FirefoxToolbarNodes.hasTag(node, FirefoxToolbarNodes.SEARCH_BOX_TAG)
                                && node.isEditable()
                                && NodeSearch.isVisibleInPackage(node, packageName));
            default:
                return findByProfileIds(root, true);
        }
    }

    private AccessibilityNodeInfo findDisplay(AccessibilityNodeInfo root) {
        switch (profile.getMethod()) {
            case TOOLBAR_STRUCTURE:
                return ToolbarStructure.findUrlDisplay(root, packageName);
            case FIREFOX_TOOLBAR:
                AccessibilityNodeInfo composeUrl = NodeSearch.findFirst(root, node ->
                        FirefoxToolbarNodes.hasTag(node, FirefoxToolbarNodes.URL_BOX_TAG)
                                && NodeSearch.isVisibleInPackage(node, packageName));
                if (composeUrl != null) return composeUrl;
                return findByProfileIds(root, false);
            default:
                return findByProfileIds(root, false);
        }
    }

    private AccessibilityNodeInfo findByProfileIds(AccessibilityNodeInfo root, boolean editable) {
        for (String idName : profile.getAddressViewIds()) {
            try {
                List<AccessibilityNodeInfo> nodes =
                        root.findAccessibilityNodeInfosByViewId(packageName + ":id/" + idName);
                if (nodes == null) continue;

                for (AccessibilityNodeInfo node : nodes) {
                    if (node == null || !NodeSearch.isVisibleInPackage(node, packageName)) continue;
                    if (!editable || node.isEditable()) return node;
                }
            } catch (RuntimeException ignored) {
                // A toolbar pode ser recriada durante a leitura; o passo falha e cai no fallback.
            }
        }
        return null;
    }

    /** Navegadores que editam o endereço numa tela de pesquisa própria (Opera GX). */
    static boolean opensSearchScreen(BrowserProfile profile) {
        return profile != null && profile.getMethod() == BrowserProfile.Method.TOOLBAR_STRUCTURE;
    }

    private String describe(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        return "[id=" + node.getViewIdResourceName()
                + " class=" + node.getClassName()
                + " focado=" + node.isFocused()
                + " host=" + DomainMatcher.extractHost(text == null ? null : text.toString())
                + "]";
    }

    private void log(String message) {
        if ((service.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        Log.d(LOG_TAG, packageName + ": " + message);
    }

    private AccessibilityNodeInfo browserRoot() {
        AccessibilityNodeInfo fallback = null;

        try {
            for (AccessibilityWindowInfo window : service.getWindows()) {
                if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }

                AccessibilityNodeInfo root = window.getRoot();
                CharSequence rootPackage = root == null ? null : root.getPackageName();
                if (rootPackage == null || !packageName.equals(rootPackage.toString())) continue;

                if (window.isActive()) return root;
                if (fallback == null) fallback = root;
            }
        } catch (RuntimeException ignored) {
            // Algumas versões do Android podem invalidar uma janela enquanto percorremos a lista.
        }

        return fallback;
    }
}
