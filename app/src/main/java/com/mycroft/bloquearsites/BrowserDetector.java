package com.mycroft.bloquearsites;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Identifica os apps instalados que são navegadores.
 *
 * Navegador é o app que abre um link https de qualquer site. O teste usa um domínio que não
 * existe: apps que só abrem links do próprio site (YouTube, redes sociais) declaram o domínio no
 * filtro e ficam de fora; navegadores declaram http/https sem domínio. A visibilidade desses apps
 * no Android 11+ vem do bloco <queries> do manifesto.
 */
final class BrowserDetector {
    private static final Uri PROBE_URL = Uri.parse("https://bloquearsites.invalid/");

    private final Context context;
    // Filtros de intent só mudam com instalação ou atualização; o cache vive enquanto o serviço vive.
    private final Map<String, Boolean> cache = new HashMap<>();

    BrowserDetector(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean isBrowser(String packageName) {
        if (packageName == null || packageName.equals(context.getPackageName())) return false;

        Boolean cached = cache.get(packageName);
        if (cached != null) return cached;

        boolean browser = !queryWebHandlers(packageName).isEmpty();
        cache.put(packageName, browser);
        return browser;
    }

    /** Navegador instalado que não pertence a nenhuma família suportada. */
    boolean isUnsupportedBrowser(String packageName) {
        return BrowserProfiles.forPackage(packageName) == null && isBrowser(packageName);
    }

    /** Pacotes de todos os navegadores instalados, em ordem alfabética. */
    List<String> installedBrowsers() {
        TreeSet<String> packages = new TreeSet<>();
        for (ResolveInfo info : queryWebHandlers(null)) {
            if (info.activityInfo == null) continue;
            String packageName = info.activityInfo.packageName;
            if (!context.getPackageName().equals(packageName)) packages.add(packageName);
        }
        return new ArrayList<>(packages);
    }

    String labelOf(String packageName) {
        PackageManager packageManager = context.getPackageManager();
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            CharSequence label = packageManager.getApplicationLabel(info);
            return label == null || label.length() == 0 ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private List<ResolveInfo> queryWebHandlers(String packageName) {
        Intent intent = new Intent(Intent.ACTION_VIEW, PROBE_URL);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        if (packageName != null) intent.setPackage(packageName);

        PackageManager packageManager = context.getPackageManager();
        try {
            List<ResolveInfo> result;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result = packageManager.queryIntentActivities(
                        intent,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL)
                );
            } else {
                result = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL);
            }
            return result == null ? Collections.emptyList() : result;
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }
}
