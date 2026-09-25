package com.mycroft.bloquearsites;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;

/**
 * Registra em que versão de cada navegador o método da família achou a barra de endereço.
 *
 * É a rede de segurança para um navegador suportado que deixa de ser lido, como o Mi Browser 14
 * depois de mudar a barra: a cada versão, a barra precisa ser achada ao menos uma vez com uma
 * página na tela, ou o navegador é fechado. A versão junta a data da última instalação ou
 * atualização do navegador e a do próprio app, porque qualquer uma das duas pode mudar a barra ou
 * o método que a lê.
 */
final class VerifiedBrowsers {
    private static final String VERIFIED_PREFS_NAME = "verified_browsers";
    private static final String FAILED_PREFS_NAME = "unreadable_browsers";
    // O navegador pode ser atualizado com o serviço rodando. A versão é consultada de novo após
    // esse intervalo, sem uma chamada ao PackageManager a cada evento.
    private static final long VERSION_CACHE_MS = 30_000L;

    private final Context context;
    private final Map<String, String> versions = new HashMap<>();
    private final Map<String, Long> versionsReadAt = new HashMap<>();

    VerifiedBrowsers(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Se a barra já foi achada na versão instalada do navegador. */
    boolean isVerified(String packageName) {
        String version = versionOf(packageName);
        // Sem a versão não há o que comparar; a rede de segurança não age.
        if (version == null) return true;
        return version.equals(prefs(VERIFIED_PREFS_NAME).getString(packageName, null));
    }

    /** Se a barra não foi achada na versão instalada e o navegador foi fechado. */
    boolean hasFailed(String packageName) {
        String version = versionOf(packageName);
        return version != null
                && version.equals(prefs(FAILED_PREFS_NAME).getString(packageName, null));
    }

    void markVerified(String packageName) {
        String version = versionOf(packageName);
        if (version == null) return;
        prefs(VERIFIED_PREFS_NAME).edit().putString(packageName, version).apply();
        prefs(FAILED_PREFS_NAME).edit().remove(packageName).apply();
    }

    void markFailed(String packageName) {
        String version = versionOf(packageName);
        if (version == null) return;
        prefs(FAILED_PREFS_NAME).edit().putString(packageName, version).apply();
    }

    static String version(long browserUpdatedAt, long appUpdatedAt) {
        return browserUpdatedAt + "/" + appUpdatedAt;
    }

    /** Versão instalada do navegador e do app, ou null se não puder ser lida. */
    String versionOf(String packageName) {
        if (packageName == null) return null;

        long now = SystemClock.elapsedRealtime();
        Long readAt = versionsReadAt.get(packageName);
        if (readAt != null && now - readAt < VERSION_CACHE_MS) return versions.get(packageName);

        String version = readVersion(packageName);
        versions.put(packageName, version);
        versionsReadAt.put(packageName, now);
        return version;
    }

    private String readVersion(String packageName) {
        PackageInfo browser = packageInfo(packageName);
        PackageInfo app = packageInfo(context.getPackageName());
        if (browser == null || app == null) return null;
        return version(browser.lastUpdateTime, app.lastUpdateTime);
    }

    private PackageInfo packageInfo(String packageName) {
        PackageManager packageManager = context.getPackageManager();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(0)
                );
            }
            return packageManager.getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    private SharedPreferences prefs(String name) {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }
}
