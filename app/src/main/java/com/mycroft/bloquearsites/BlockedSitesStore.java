package com.mycroft.bloquearsites;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BlockedSitesStore {
    private static final String PREFS = "blocked_sites";
    private static final String KEY_DOMAINS = "domains";
    private static final String KEY_ADULT_FILTER = "block_adult_content";

    private final SharedPreferences preferences;

    public BlockedSitesStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String add(String rawSite) {
        String normalized = DomainMatcher.normalizeBlockedInput(rawSite);
        if (normalized == null) return null;

        Set<String> updated = new HashSet<>(preferences.getStringSet(KEY_DOMAINS, Collections.emptySet()));
        updated.add(normalized);
        preferences.edit().putStringSet(KEY_DOMAINS, updated).apply();
        return normalized;
    }

    public void remove(String domain) {
        Set<String> updated = new HashSet<>(preferences.getStringSet(KEY_DOMAINS, Collections.emptySet()));
        updated.remove(domain);
        preferences.edit().putStringSet(KEY_DOMAINS, updated).apply();
    }

    public Set<String> getSet() {
        return new HashSet<>(preferences.getStringSet(KEY_DOMAINS, Collections.emptySet()));
    }

    /** Opção "Bloquear pornografia" (AdultContentFilter). */
    public boolean isAdultFilterEnabled() {
        return preferences.getBoolean(KEY_ADULT_FILTER, false);
    }

    public void setAdultFilterEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_ADULT_FILTER, enabled).apply();
    }

    /** Se há algo a bloquear: sites na lista ou o bloqueio de pornografia ligado. */
    public boolean isBlockingActive() {
        return isAdultFilterEnabled() || !getSet().isEmpty();
    }

    public List<String> getSortedList() {
        List<String> result = new ArrayList<>(getSet());
        Collections.sort(result);
        return result;
    }
}
