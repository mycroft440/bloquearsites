package com.mycroft.bloquearsites;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.InputMethod;
import android.annotation.TargetApi;
import android.os.Build;
import android.os.SystemClock;
import android.view.inputmethod.EditorInfo;

/**
 * Teclado do serviço de acessibilidade (Android 13+), que registra quando um campo começa a
 * receber texto.
 *
 * Na troca de site do Opera GX, isso diz sem percorrer a árvore de acessibilidade que a tela de
 * pesquisa abriu e que o campo dela está pronto para receber o destino.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
final class ServiceInputMethod extends InputMethod {
    private static volatile long lastStartAt = 0L;
    private static volatile String lastStartPackage;

    ServiceInputMethod(AccessibilityService service) {
        super(service);
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        lastStartPackage = attribute == null ? null : attribute.packageName;
        lastStartAt = SystemClock.elapsedRealtime();
    }

    /** Se um campo do pacote começou a receber texto a partir do instante indicado. */
    static boolean startedSince(String packageName, long since) {
        return packageName != null
                && packageName.equals(lastStartPackage)
                && lastStartAt >= since;
    }
}
