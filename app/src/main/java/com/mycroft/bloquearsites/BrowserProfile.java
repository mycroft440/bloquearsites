package com.mycroft.bloquearsites;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Uma família de navegadores que expõe a barra de endereço do mesmo jeito. Os pacotes de uma
 * família compartilham o método de identificação e, quando existem, os IDs da barra.
 */
public final class BrowserProfile {
    /** Como a família expõe a URL na árvore de acessibilidade. */
    public enum Method {
        /**
         * A URL está no texto de uma View com ID conhecido, e os eventos clássicos de mudança de
         * janela/conteúdo bastam para lê-la.
         */
        VIEW_ID,

        /**
         * Igual a VIEW_ID, mas a barra pode ser atualizada depois do evento: todos os eventos são
         * ouvidos e a barra é relida após um curto atraso quando a primeira leitura falha.
         */
        VIEW_ID_WITH_REREAD,

        /**
         * Toolbar do Firefox (Compose ou View): só a URL exibida conta, confirmada por leituras
         * estáveis, porque o campo de edição mostra texto digitado e sugestões.
         */
        FIREFOX_TOOLBAR,

        /**
         * IDs ofuscados que mudam a cada versão: a barra é achada pela posição e pelo tipo dos
         * nós, fora do conteúdo da página. Também relê a barra após o evento.
         */
        TOOLBAR_STRUCTURE
    }

    private final String family;
    private final Method method;
    private final Set<String> packageNames;
    private final List<String> addressViewIds;
    private final String note;

    public BrowserProfile(
            String family,
            Method method,
            String[] packageNames,
            String... addressViewIds
    ) {
        this(family, method, Collections.unmodifiableSet(new HashSet<>(Arrays.asList(packageNames))),
                Collections.unmodifiableList(Arrays.asList(addressViewIds)), null);
    }

    private BrowserProfile(
            String family,
            Method method,
            Set<String> packageNames,
            List<String> addressViewIds,
            String note
    ) {
        this.family = family;
        this.method = method;
        this.packageNames = packageNames;
        this.addressViewIds = addressViewIds;
        this.note = note;
    }

    /** Cópia com uma observação para o usuário, exibida na lista de navegadores do app. */
    public BrowserProfile withNote(String note) {
        return new BrowserProfile(family, method, packageNames, addressViewIds, note);
    }

    public boolean matchesPackage(String packageName) {
        return packageName != null && packageNames.contains(packageName);
    }

    public String getFamily() {
        return family;
    }

    public Method getMethod() {
        return method;
    }

    public Set<String> getPackageNames() {
        return packageNames;
    }

    public List<String> getAddressViewIds() {
        return addressViewIds;
    }

    /** Limitação ou ajuste necessário no navegador, ou null. */
    public String getNote() {
        return note;
    }

    /** Ouve todos os tipos de evento e relê a barra se a primeira leitura falhar. */
    public boolean rereadsAfterEvent() {
        return method == Method.VIEW_ID_WITH_REREAD || method == Method.TOOLBAR_STRUCTURE;
    }
}
