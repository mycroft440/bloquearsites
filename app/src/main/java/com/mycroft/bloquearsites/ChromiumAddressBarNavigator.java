package com.mycroft.bloquearsites;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * Troca o site da aba atual em navegadores Chromium digitando o destino na própria barra de
 * endereço. Um Intent ACTION_VIEW sempre abre uma aba nova no Chrome atual (a flag
 * DontClobberTabsWithChromeAppId vem ativa por padrão), então a barra é o único caminho para
 * reaproveitar a aba do site bloqueado.
 */
final class ChromiumAddressBarNavigator {
    interface Callback {
        void onFinished(boolean submitted);
    }

    private static final String ADDRESS_BAR_ID = "url_bar";

    // O Chrome troca a barra para o modo de edição e processa o autocomplete de forma assíncrona.
    private static final long FOCUS_DELAY_MS = 250L;
    private static final long SUBMIT_DELAY_MS = 300L;

    private final AccessibilityService service;
    private final Handler mainHandler;

    private String packageName;
    private String url;
    private Callback callback;
    private Runnable pendingStep;

    ChromiumAddressBarNavigator(AccessibilityService service, Handler mainHandler) {
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

        this.packageName = packageName;
        this.url = url;
        this.callback = callback;

        AccessibilityNodeInfo addressBar = findAddressBar();
        if (addressBar == null) return false;

        if (!addressBar.isFocused()
                && !addressBar.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                && !addressBar.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            return false;
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

    private void typeUrl() {
        // Sem foco, o Chrome ignora o texto no autocomplete e o Enter não navegaria.
        AccessibilityNodeInfo addressBar = findAddressBar();
        if (addressBar == null || !addressBar.isFocused()) {
            finish(false);
            return;
        }

        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, url);
        if (!addressBar.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            finish(false);
            return;
        }

        schedule(this::submit, SUBMIT_DELAY_MS);
    }

    private void submit() {
        // Se o Chrome reescreveu a barra depois do foco, o Enter recarregaria o site bloqueado.
        AccessibilityNodeInfo addressBar = findAddressBar();
        boolean submitted = addressBar != null
                && addressBar.isFocused()
                && hasTypedUrl(addressBar)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && addressBar.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        finish(submitted);
    }

    private boolean hasTypedUrl(AccessibilityNodeInfo addressBar) {
        CharSequence text = addressBar.getText();
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

    private AccessibilityNodeInfo findAddressBar() {
        AccessibilityNodeInfo root = browserRoot();
        if (root == null) return null;

        try {
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(packageName + ":id/" + ADDRESS_BAR_ID);
            if (nodes == null) return null;

            // Custom Tabs usam o mesmo ID num TextView somente leitura; só a barra editável serve.
            for (AccessibilityNodeInfo node : nodes) {
                if (node != null && node.isEditable()) return node;
            }
        } catch (RuntimeException ignored) {
            // A toolbar pode ser recriada durante a leitura; o passo falha e cai no fallback.
        }
        return null;
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
