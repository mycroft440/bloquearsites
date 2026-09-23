package com.mycroft.bloquearsites;

import java.util.HashMap;
import java.util.Map;

/**
 * Confirmação da família de um navegador desconhecido.
 *
 * Uma leitura da URL não basta: há navegadores que mostram a URL só enquanto a página carrega e
 * depois trocam pelo título, e neles sites abertos por links passariam. A mesma família precisa
 * ler a URL, sem nenhuma falha no meio, por pelo menos CONFIRM_AFTER_MS.
 */
final class IdentificationConfirmation {
    static final long CONFIRM_AFTER_MS = 2000L;

    private final Map<String, Candidate> candidates = new HashMap<>();

    /** Leitura bem-sucedida pela família. Retorna true quando a família fica confirmada. */
    boolean onRead(String packageName, String family, long now) {
        Candidate candidate = candidates.get(packageName);
        if (candidate == null || !candidate.family.equals(family)) {
            candidates.put(packageName, new Candidate(family, now));
            return false;
        }

        if (now - candidate.firstReadAt < CONFIRM_AFTER_MS) return false;

        candidates.remove(packageName);
        return true;
    }

    /** Nenhuma família leu a URL com a página na tela: a contagem recomeça. */
    void reset(String packageName) {
        candidates.remove(packageName);
    }

    /** Se uma família já leu a URL e aguarda a confirmação. */
    boolean isPending(String packageName) {
        return candidates.containsKey(packageName);
    }

    private static final class Candidate {
        final String family;
        final long firstReadAt;

        Candidate(String family, long firstReadAt) {
            this.family = family;
            this.firstReadAt = firstReadAt;
        }
    }
}
