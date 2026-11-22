package com.suso.listacompra;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Mira en segundo plano (cada ~12 h, con internet) si en GitHub hay una versión
 * más nueva que la instalada y, si la hay, avisa con una notificación.
 */
public class Actualizaciones extends JobService {

    /** Repositorio de GitHub donde se publican las versiones (Releases). */
    public static final String REPO = "jesusvprado-cmd/cestadelacompra";
    public static final String API_ULTIMA = "https://api.github.com/repos/" + REPO + "/releases/latest";

    private static final int ID_TRABAJO = 7001;
    private static final int ID_NOTIF = 7002;
    private static final String CANAL = "actualizaciones";

    /** Programa la comprobación periódica (solo si no estaba ya programada). */
    public static void programar(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null || js.getPendingJob(ID_TRABAJO) != null) return;
            JobInfo info = new JobInfo.Builder(ID_TRABAJO, new ComponentName(c, Actualizaciones.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(12L * 60 * 60 * 1000)
                    .setPersisted(true)
                    .build();
            js.schedule(info);
        } catch (Exception ignored) { }
    }

    /** Código de versión instalado (el número que pone GitHub en cada compilación). */
    public static int codigoInstalado(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Devuelve {codigo, nombre, notas, urlApk} de la última versión publicada, o null. */
    public static String[] ultimaPublicada() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API_ULTIMA).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = br.readLine()) != null) sb.append(l).append('\n');
            }
            JSONObject j = new JSONObject(sb.toString());
            String tag = j.optString("tag_name", "");
            int codigo = Integer.parseInt(tag.replaceAll("[^0-9]", ""));
            String url = null;
            JSONArray assets = j.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name", "").endsWith(".apk")) { url = a.optString("browser_download_url"); break; }
                }
            }
            if (url == null) return null;
            return new String[]{String.valueOf(codigo), j.optString("name", tag), j.optString("body", ""), url};
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            try {
                String[] ult = ultimaPublicada();
                if (ult != null) {
                    int codigo = Integer.parseInt(ult[0]);
                    SharedPreferences p = getSharedPreferences("lista", MODE_PRIVATE);
                    if (codigo > codigoInstalado(this) && codigo != p.getInt("avisada", 0)) {
                        avisar(this, ult[1], ult[2]);
                        p.edit().putInt("avisada", codigo).apply();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    @SuppressWarnings("deprecation")
    private static void avisar(Context c, String nombre, String notas) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CANAL, "Actualizaciones de la app", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent abrir = new Intent(c, MainActivity.class);
        abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String texto = notas == null || notas.trim().isEmpty() ? "Toca para actualizar" : notas.trim().split("\n")[0];
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CANAL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Lista de la compra: " + nombre + " disponible")
                .setContentText(texto)
                .setContentIntent(pi)
                .setAutoCancel(true);
        try { nm.notify(ID_NOTIF, b.build()); } catch (SecurityException ignored) { }
    }
}
