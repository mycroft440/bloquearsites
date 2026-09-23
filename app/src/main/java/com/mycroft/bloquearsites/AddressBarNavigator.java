package com.mycroft.bloquearsites;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
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
 * acessibilidade. Os IDs vêm do perfil de cada navegador; o Firefox atual usa testTags do Compose.
 */
final class AddressBarNavigator {
    interface Callback {
        void onFinished(boolean submitted);
    }

    // Os navegadores trocam a barra para o modo de edição e processam o autocomplete de forma
    // assíncrona.
    private static final long FOCUS_DELAY_MS = 300L;
    private static final long SUBMIT_DELAY_MS = 300L;
    private static final long TAP_DURATION_MS = 50L;

    private final AccessibilityService service;
    private final Handler mainHandler;

    private String packageName;
    private BrowserProfile profile;
    private String url;
    private Callback callback;
    private Runnable pendingStep;

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

        AccessibilityNodeInfo root = browserRoot();
        if (root == null) return false;

        // Chrome, Samsung, Opera e DuckDuckGo exibem a URL no próprio campo editável.
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

            // O Firefox exibe a URL num elemento só de leitura; tocar nele abre o campo de edição.
            AccessibilityNodeInfo display = findDisplay(root);
            if (display == null
                    || !(display.performAction(AccessibilityNodeInfo.ACTION_CLICK) || tap(display))) {
                return false;
            }
        }

        schedule(this::typeUrl, FOCUS_DELAY_MS);
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

    private void typeUrl() {
        AccessibilityNodeInfo editField = findEditField(browserRoot());
        if (editField == null) {
            finish(false);
            return;
        }

        // Sem foco, o Chrome ignora o texto no autocomplete e o Enter não navegaria.
        if (!editField.isFocused()) editField.performAction(AccessibilityNodeInfo.ACTION_FOCUS);

        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, url);
        if (!editField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            finish(false);
            return;
        }

        schedule(this::submit, SUBMIT_DELAY_MS);
    }

    private void submit() {
        // Se o navegador reescreveu a barra depois do foco, o Enter recarregaria o site bloqueado.
        AccessibilityNodeInfo editField = findEditField(browserRoot());
        boolean submitted = editField != null
                && hasTypedUrl(editField)
                && (editField.isFocused()
                        || editField.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && editField.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        finish(submitted);
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
        if (finished != null) finished.onFinished(submitted);
    }

    private AccessibilityNodeInfo findEditField(AccessibilityNodeInfo root) {
        if (root == null) return null;

        AccessibilityNodeInfo byId = findByProfileIds(root, true);
        if (byId != null) return byId;

        if (!BrowserProfiles.isFirefox(packageName)) return null;
        return FirefoxToolbarNodes.findFirst(root, node ->
                FirefoxToolbarNodes.hasTag(node, FirefoxToolbarNodes.SEARCH_BOX_TAG)
                        && node.isEditable()
                        && isUsable(node));
    }

    private AccessibilityNodeInfo findDisplay(AccessibilityNodeInfo root) {
        if (BrowserProfiles.isFirefox(packageName)) {
            AccessibilityNodeInfo composeUrl = FirefoxToolbarNodes.findFirst(root, node ->
                    FirefoxToolbarNodes.hasTag(node, FirefoxToolbarNodes.URL_BOX_TAG)
                            && isUsable(node));
            if (composeUrl != null) return composeUrl;
        }
        return findByProfileIds(root, false);
    }

    private AccessibilityNodeInfo findByProfileIds(AccessibilityNodeInfo root, boolean editable) {
        for (String idName : profile.getAddressViewIds()) {
            try {
                List<AccessibilityNodeInfo> nodes =
                        root.findAccessibilityNodeInfosByViewId(packageName + ":id/" + idName);
                if (nodes == null) continue;

                for (AccessibilityNodeInfo node : nodes) {
                    if (node == null || !isUsable(node)) continue;
                    if (!editable || node.isEditable()) return node;
                }
            } catch (RuntimeException ignored) {
                // A toolbar pode ser recriada durante a leitura; o passo falha e cai no fallback.
            }
        }
        return null;
    }

    private boolean isUsable(AccessibilityNodeInfo node) {
        CharSequence nodePackage = node.getPackageName();
        return node.isVisibleToUser()
                && nodePackage != null
                && packageName.equals(nodePackage.toString());
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
