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
    // Pacote do campo conectado agora ao teclado do serviço (null sem campo conectado).
    private static volatile String activePackage;
    private static volatile boolean created;

    ServiceInputMethod(AccessibilityService service) {
        super(service);
        created = true;
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        lastStartPackage = attribute == null ? null : attribute.packageName;
        lastStartAt = SystemClock.elapsedRealtime();
        activePackage = lastStartPackage;
    }

    @Override
    public void onFinishInput() {
        activePackage = null;
    }

    /**
     * Se um campo do pacote pode estar em edição. Se o sistema não criou o teclado do serviço, não
     * há como saber, e a resposta é sim. Só pode ser chamado no Android 13+.
     */
    static boolean mayBeEditing(String packageName) {
        if (!created) return true;
        return packageName != null && packageName.equals(activePackage);
    }

    /** Se um campo do pacote começou a receber texto a partir do instante indicado. */
    static boolean startedSince(String packageName, long since) {
        return packageName != null
                && packageName.equals(lastStartPackage)
                && lastStartAt >= since;
    }
}
