package com.mycroft.bloquearsites;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.InputMethod;
import android.content.pm.ApplicationInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.SurroundingText;

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
 * endereço, e o Mi Browser também, no novo estilo de página (padrão nos celulares), ao tocar na
 * barra de baixo. Só para eles, o campo é achado também como o campo editável com foco, a busca é
 * repetida enquanto a tela aparece e o texto é digitado de novo se o navegador o reescrever. Os
 * demais navegadores seguem o fluxo direto, que já funcionava.
 *
 * O campo da tela de pesquisa do Mi Browser só navega com a ação "Ir" do teclado (actionId 2); o
 * Enter de acessibilidade envia o imeActionId do campo, que é 0, e é ignorado. Por isso a
 * confirmação usa a conexão de entrada do serviço (Android 13+); sem ela, a troca pela barra nem
 * começa e o Google abre em uma aba nova.
 *
 * No Opera GX, o texto trocado por ACTION_SET_TEXT às vezes não fica no campo em Compose, e a tela
 * de pesquisa continuava com o site bloqueado selecionado. No Android 13+, o destino é digitado
 * pela conexão de entrada do serviço, como um teclado: seleciona tudo e escreve por cima. A espera
 * pela tela de pesquisa e a conferência do texto também passam pelo teclado do serviço
 * (ServiceInputMethod), sem percorrer a árvore de acessibilidade: o Opera GX não tem IDs na
 * barra, e cada busca pela estrutura da tela ocupava o serviço por muito tempo, atrasando a
 * cortina e a troca.
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
    // Opera GX com o teclado do serviço: a conexão com o campo é conferida a cada 80 ms (até 2 s),
    // até a tela de pesquisa abrir; a conferência não percorre a árvore.
    private static final long INPUT_POLL_MS = 80L;
    private static final int INPUT_POLL_ATTEMPTS = 25;
    private static final long INPUT_SUBMIT_DELAY_MS = 120L;
    private static final int INPUT_TEXT_LIMIT = 500;
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
    private boolean editorActionSubmit;
    private boolean inputConnectionTyping;
    private long barTouchedAt;

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
        this.editorActionSubmit = false;
        this.inputConnectionTyping = searchScreen && canSendEditorAction();

        AccessibilityNodeInfo root = browserRoot();
        if (root == null) return false;

        barTouchedAt = SystemClock.elapsedRealtime();

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

            // Firefox, Yandex, Opera GX e o novo estilo do Mi Browser exibem a URL num elemento só
            // de leitura; tocar nele abre a edição.
            AccessibilityNodeInfo display = findDisplay(root);
            if (display == null) {
                log("barra não encontrada");
                return false;
            }

            if (BrowserProfiles.isAospBrowser(packageName)) {
                if (!canSendEditorAction()) {
                    log("ação Ir indisponível (Android 12 ou anterior)");
                    return false;
                }
                searchScreen = true;
                editorActionSubmit = true;
            }

            if (!(display.performAction(AccessibilityNodeInfo.ACTION_CLICK) || tap(display))) {
                log("barra não tocada");
                return false;
            }
        }

        touchedBar = true;
        schedule(() -> typeUrl(1), inputConnectionTyping ? INPUT_POLL_MS : FOCUS_DELAY_MS);
        return true;
    }

    boolean isRunning() {
        return pendingStep != null;
    }

    /** Se a troca em andamento edita o endereço numa tela de pesquisa (Opera GX e Mi Browser). */
    boolean isUsingSearchScreen() {
        return searchScreen;
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
        if (inputConnectionTyping) {
            typeWithServiceKeyboard(attempt);
            return;
        }

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

    /**
     * Opera GX (Android 13+): espera um campo do navegador se conectar ao teclado do serviço depois
     * do toque na barra (a tela de pesquisa abriu) e digita o destino por ele. Se o teclado não se
     * conectar, digita pela acessibilidade.
     */
    private void typeWithServiceKeyboard(int attempt) {
        boolean connected = ServiceInputMethod.startedSince(packageName, barTouchedAt)
                && browserInputConnection() != null;
        if (!connected) {
            if (attempt < INPUT_POLL_ATTEMPTS) {
                schedule(() -> typeWithServiceKeyboard(attempt + 1), INPUT_POLL_MS);
                return;
            }
            log("teclado do serviço não se conectou; digitando pela acessibilidade");
            inputConnectionTyping = false;
            typeUrl(1);
            return;
        }

        if (!typeWithInputConnection()) {
            inputConnectionTyping = false;
            typeUrl(1);
            return;
        }
        log("destino digitado pelo teclado do serviço");
        schedule(() -> submitWithServiceKeyboard(false), INPUT_SUBMIT_DELAY_MS);
    }

    private void submitWithServiceKeyboard(boolean retyped) {
        if (browserInputConnection() == null) {
            // A tela de pesquisa fechou (Voltar) antes da confirmação.
            log("campo fechado antes da confirmação");
            finish(false);
            return;
        }

        if (!inputHasTypedUrl()) {
            if (!retyped && typeWithInputConnection()) {
                log("texto não ficou no campo; digitando de novo pelo teclado do serviço");
                schedule(() -> submitWithServiceKeyboard(true), INPUT_SUBMIT_DELAY_MS);
                return;
            }
            log("texto não ficou no campo; digitando pela acessibilidade");
            inputConnectionTyping = false;
            typeUrl(1);
            return;
        }

        boolean submitted = sendEditorAction();
        log("ação do teclado do serviço=" + submitted);
        finish(submitted);
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

        boolean focused = editField.isFocused()
                || editField.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        boolean submitted;
        if (editorActionSubmit) {
            submitted = focused && sendEditorAction();
            log("ação Ir=" + submitted);
        } else {
            submitted = focused
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && editField.performAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            log("Enter=" + submitted);
        }
        finish(submitted);
    }

    private boolean canSendEditorAction() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && service.getInputMethod() != null;
    }

    /**
     * Confirma como o botão de ação do teclado, pela conexão de entrada do serviço, com a ação que o
     * campo declara. Só vale se a entrada ativa é a do navegador.
     */
    private boolean sendEditorAction() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false;

        InputMethod.AccessibilityInputConnection connection = browserInputConnection();
        if (connection == null) return false;

        EditorInfo editor = service.getInputMethod().getCurrentInputEditorInfo();
        int action = editor.imeOptions & EditorInfo.IME_MASK_ACTION;
        if (action == EditorInfo.IME_ACTION_UNSPECIFIED || action == EditorInfo.IME_ACTION_NONE) {
            action = EditorInfo.IME_ACTION_GO;
        }
        connection.performEditorAction(action);
        return true;
    }

    /** Seleciona o texto do campo e escreve o destino por cima, como um teclado. */
    private boolean typeWithInputConnection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false;

        InputMethod.AccessibilityInputConnection connection = browserInputConnection();
        if (connection == null) return false;

        connection.performContextMenuAction(android.R.id.selectAll);
        connection.commitText(url, 1, null);
        return true;
    }

    /** Se o campo conectado ao teclado do serviço já tem o destino digitado. */
    private boolean inputHasTypedUrl() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false;

        InputMethod.AccessibilityInputConnection connection = browserInputConnection();
        if (connection == null) return false;

        SurroundingText around =
                connection.getSurroundingText(INPUT_TEXT_LIMIT, INPUT_TEXT_LIMIT, 0);
        CharSequence text = around == null ? null : around.getText();
        String typedHost = DomainMatcher.extractHost(text == null ? null : text.toString().trim());
        return typedHost != null && typedHost.equals(DomainMatcher.extractHost(url));
    }

    /** Conexão de entrada do serviço com o campo ativo, se ele é do navegador (Android 13+). */
    private InputMethod.AccessibilityInputConnection browserInputConnection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null;

        InputMethod inputMethod = service.getInputMethod();
        if (inputMethod == null || !inputMethod.getCurrentInputStarted()) return null;

        EditorInfo editor = inputMethod.getCurrentInputEditorInfo();
        if (editor == null || !packageName.equals(editor.packageName)) return null;

        return inputMethod.getCurrentInputConnection();
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

        // Na tela de pesquisa (Opera GX e Mi Browser), o campo de endereço é o campo editável com
        // foco; em último caso, o primeiro campo editável. Os dois ficam sempre fora do conteúdo da
        // página.
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
        AccessibilityNodeInfo address = findByIds(root, profile.getAddressViewIds(), editable);
        if (address != null || !editable) return address;

        return findByIds(root, profile.getEditFieldViewIds(), true);
    }

    private AccessibilityNodeInfo findByIds(
            AccessibilityNodeInfo root,
            List<String> ids,
            boolean editable
    ) {
        for (String idName : ids) {
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

    /**
     * Famílias que sempre editam o endereço numa tela de pesquisa própria (Opera GX). O Mi Browser
     * só a abre no novo estilo de página; isso é decidido ao tocar na barra.
     */
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
