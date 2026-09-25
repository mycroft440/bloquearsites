package com.mycroft.bloquearsites;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.Locale;

/**
 * Liberação do app para rodar em segundo plano sem restrições.
 *
 * Com o app fora da tela, a otimização de bateria do Android e as economias de energia dos
 * fabricantes (a MIUI/HyperOS da Xiaomi, principalmente) atrasam o serviço de acessibilidade, e o
 * bloqueio chega segundos depois de o site ou o navegador abrir. O usuário tira o app da otimização
 * de bateria e, na Xiaomi, libera o início automático e a economia de bateria do app.
 */
final class BackgroundAccess {
    private BackgroundAccess() {}

    /** Se o app já está fora da otimização de bateria do Android. */
    static boolean isIgnoringBatteryOptimizations(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /**
     * Pede para tirar o app da otimização de bateria (diálogo do sistema). Se o aparelho não tiver o
     * diálogo, abre a lista de otimização de bateria ou, em último caso, os detalhes do app.
     */
    static void requestIgnoreBatteryOptimizations(Activity activity) {
        Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + activity.getPackageName()));
        if (start(activity, request)) return;
        if (start(activity, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return;
        openAppDetails(activity);
    }

    /** Aparelhos Xiaomi, Redmi e POCO, com restrições próprias de segundo plano. */
    static boolean hasManufacturerRestrictions() {
        String manufacturer = Build.MANUFACTURER == null
                ? ""
                : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        return manufacturer.contains("xiaomi")
                || manufacturer.contains("redmi")
                || manufacturer.contains("poco");
    }

    /** Tela de início automático da Xiaomi; sem ela, os detalhes do app. */
    static void openAutoStartSettings(Activity activity) {
        Intent autoStart = new Intent().setComponent(new ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"));
        if (start(activity, autoStart)) return;
        openAppDetails(activity);
    }

    /** Economia de bateria do app na Xiaomi ("Sem restrições"); sem ela, os detalhes do app. */
    static void openManufacturerBatterySettings(Activity activity) {
        Intent battery = new Intent()
                .setComponent(new ComponentName(
                        "com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                .putExtra("package_name", activity.getPackageName())
                .putExtra("package_label", activity.getString(R.string.app_name));
        if (start(activity, battery)) return;
        openAppDetails(activity);
    }

    static void openAppDetails(Activity activity) {
        start(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + activity.getPackageName())));
    }

    private static boolean start(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            // Tela inexistente nesta versão do sistema (ActivityNotFoundException) ou protegida
            // (SecurityException): tenta a próxima.
            return false;
        }
    }
}
