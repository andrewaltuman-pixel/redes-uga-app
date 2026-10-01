package dev.pages.redesuga.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.CookieManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Avisos de la plataforma (clase en vivo, quiz, notas cargadas) como notificaciones de la app.
 * El visor web de Android no recibe avisos push: la app los consulta cada 15 minutos con una clave propia
 * del celular, que solo permite leer esos avisos (no abre la sesión) y que la plataforma borra al cerrar sesión.
 */
final class Avisos {
    static final String EXTRA_URL = "url";
    private static final String CANAL = "avisos";
    private static final String PREFS = "avisos";
    private static final long UN_DIA = 24L * 3600 * 1000;
    private static final ExecutorService HILO = Executors.newSingleThreadExecutor();

    private Avisos() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void programar(Context c) {
        PeriodicWorkRequest tarea = new PeriodicWorkRequest.Builder(RevisarAvisos.class, 15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("avisos", ExistingPeriodicWorkPolicy.KEEP, tarea);
    }

    static boolean permitidos(Context c) {
        return NotificationManagerCompat.from(c).areNotificationsEnabled();
    }

    static void olvidarClave(Context c) {
        prefs(c).edit().remove("token").remove("token_fecha").apply();
    }

    /** Con la sesión abierta en la app, pide (o renueva una vez por día) la clave de avisos de este celular. */
    static void asegurarClave(Context c) {
        Context app = c.getApplicationContext();
        HILO.execute(() -> {
            SharedPreferences p = prefs(app);
            if (p.getString("token", null) != null && System.currentTimeMillis() - p.getLong("token_fecha", 0) < UN_DIA) return;
            String dispositivo = p.getString("dispositivo", null);
            if (dispositivo == null) {
                dispositivo = UUID.randomUUID().toString();
                p.edit().putString("dispositivo", dispositivo).apply();
            }
            try {
                HttpURLConnection con = abrir("push/clave-app");
                String cookies = CookieManager.getInstance().getCookie(MainActivity.INICIO);
                if (cookies != null) con.setRequestProperty("Cookie", cookies);
                con.setRequestProperty("X-Dispositivo", dispositivo);
                String cuerpo = leer(con);
                if (cuerpo == null) return;
                p.edit().putString("token", new JSONObject(cuerpo).getString("token"))
                        .putLong("token_fecha", System.currentTimeMillis()).apply();
                if (p.getLong("desde", 0) == 0) revisar(app);
            } catch (Exception e) {
                // Sin conexión o sin sesión: se vuelve a intentar en la próxima página
            }
        });
    }

    /** Consulta los avisos nuevos y los muestra. Lo llama RevisarAvisos en segundo plano. */
    static void revisar(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        String token = p.getString("token", null);
        if (token == null) return;
        long desde = p.getLong("desde", 0);
        HttpURLConnection con = abrir("avisos-app?desde=" + desde);
        con.setRequestProperty("Authorization", "Bearer " + token);
        if (con.getResponseCode() == 401) {
            olvidarClave(c);
            return;
        }
        String cuerpo = leer(con);
        if (cuerpo == null) return;
        JSONObject r = new JSONObject(cuerpo);
        JSONArray lista = r.getJSONArray("avisos");
        long siguiente = r.getLong("ultimo");
        for (int i = 0; i < lista.length(); i++) {
            JSONObject a = lista.getJSONObject(i);
            mostrar(c, a.optString("etiqueta", "rc34"), a.getString("titulo"), a.optString("cuerpo"), a.optString("url", "/"));
        }
        // La plataforma devuelve hasta 10 por vez: si vinieron 10, se sigue desde el último en la próxima consulta
        if (lista.length() >= 10) siguiente = lista.getJSONObject(lista.length() - 1).getLong("id");
        p.edit().putLong("desde", Math.max(siguiente, 1)).apply();
    }

    static void mostrar(Context c, String etiqueta, String titulo, String cuerpo, String url) {
        if (!permitidos(c)) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CANAL, c.getString(R.string.canalAvisos), NotificationManager.IMPORTANCE_HIGH));
        }
        Intent abrir = new Intent(c, MainActivity.class)
                .putExtra(EXTRA_URL, url)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int id = etiqueta.hashCode();
        PendingIntent pi = PendingIntent.getActivity(c, id, abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder n = new NotificationCompat.Builder(c, CANAL)
                .setSmallIcon(R.drawable.ic_notificacion)
                .setColor(c.getColor(R.color.verde))
                .setContentTitle(titulo)
                .setContentText(cuerpo)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(cuerpo))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(c).notify(id, n.build());
        } catch (SecurityException e) {
            // Permiso de notificaciones retirado mientras tanto
        }
    }

    private static HttpURLConnection abrir(String ruta) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(MainActivity.INICIO + ruta).openConnection();
        con.setInstanceFollowRedirects(false);
        con.setConnectTimeout(15000);
        con.setReadTimeout(15000);
        con.setRequestProperty("Accept", "application/json");
        return con;
    }

    // Solo respuestas JSON 200: sin sesión la plataforma redirige al ingreso
    private static String leer(HttpURLConnection con) throws Exception {
        try {
            if (con.getResponseCode() != 200 || con.getContentType() == null || !con.getContentType().contains("json")) return null;
            try (InputStream in = con.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            con.disconnect();
        }
    }
}
