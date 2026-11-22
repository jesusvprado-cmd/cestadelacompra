package com.suso.listacompra;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;

/**
 * Lista de la compra: toda la pantalla está en assets/index.html.
 * Esta clase hace de puente: internet (Firebase y Gemini), escucha en directo,
 * cámara/galería, dictado por voz, guardar ajustes y compartir.
 */
public class MainActivity extends Activity {

    private static final int REQ_VOZ = 11, REQ_CAMARA = 12, REQ_GALERIA = 13;
    private static final int MAX_LADO = 1800;

    private WebView web;
    private SharedPreferences prefs;
    private ClipboardManager portapapeles;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Object candado = new Object();
    private volatile int streamGen = 0;
    private volatile HttpURLConnection streamConn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("lista", MODE_PRIVATE);
        portapapeles = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        getWindow().setStatusBarColor(0xFF2E7D32);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url.startsWith("http")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
                    return true;
                }
                return false;
            }
        });
        web.addJavascriptInterface(new Puente(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    @Override
    protected void onResume() {
        super.onResume();
        js("window.alVolver&&alVolver()");
    }

    @Override
    protected void onPause() {
        super.onPause();
        pararStream();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.atras&&atras())?'si':'no'", v -> {
            if (v == null || !v.contains("si")) salir();
        });
    }

    private void salir() {
        super.onBackPressed();
    }

    private void js(final String code) {
        ui.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    private static String leer(InputStream is) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
        br.close();
        return sb.toString();
    }

    /* ---------------- Peticiones a internet ---------------- */

    private void peticion(String id, String metodo, String url, String cuerpo, String cabeceras) {
        int code = 0;
        String txt;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(90000);
            String m = metodo == null ? "GET" : metodo.toUpperCase(Locale.ROOT);
            if (m.equals("PATCH")) {
                // HttpURLConnection no admite PATCH: Firebase acepta POST + esta cabecera
                c.setRequestMethod("POST");
                c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            } else {
                c.setRequestMethod(m);
            }
            if (cabeceras != null && !cabeceras.isEmpty()) {
                JSONObject h = new JSONObject(cabeceras);
                Iterator<String> it = h.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    c.setRequestProperty(k, h.getString(k));
                }
            }
            if (cuerpo != null && !cuerpo.isEmpty() && !m.equals("GET") && !m.equals("DELETE")) {
                byte[] datos = cuerpo.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(datos.length);
                try (OutputStream os = c.getOutputStream()) { os.write(datos); }
            }
            code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            txt = is == null ? "" : leer(is);
        } catch (Exception e) {
            txt = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (c != null) c.disconnect();
        }
        js("window.__http(" + q(id) + "," + code + "," + q(txt) + ")");
    }

    /* ---------------- Escuchar Firebase en directo (EventSource) ---------------- */

    private void iniciarStream(final String url) {
        final int gen;
        synchronized (candado) {
            pararStreamSinBloqueo();
            gen = ++streamGen;
        }
        new Thread(() -> bucleStream(url, gen)).start();
    }

    private void pararStream() {
        synchronized (candado) { pararStreamSinBloqueo(); }
    }

    private void pararStreamSinBloqueo() {
        streamGen++;
        final HttpURLConnection c = streamConn;
        streamConn = null;
        if (c != null) new Thread(() -> { try { c.disconnect(); } catch (Exception ignored) { } }).start();
    }

    private void bucleStream(String url, int gen) {
        int espera = 2000;
        while (gen == streamGen) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(75000); // Firebase manda "keep-alive" cada ~30 s
                c.setRequestProperty("Accept", "text/event-stream");
                streamConn = c;
                int code = c.getResponseCode();
                if (code != 200) {
                    InputStream es = c.getErrorStream();
                    String err = es == null ? "" : leer(es);
                    js("window.__sseError&&__sseError(" + code + "," + q(err) + ")");
                    throw new IOException("HTTP " + code);
                }
                if (gen != streamGen) break;
                js("window.__sseEstado(true)");
                espera = 2000;
                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                String linea, evento = null;
                StringBuilder data = new StringBuilder();
                while (gen == streamGen && (linea = br.readLine()) != null) {
                    if (linea.isEmpty()) {
                        if (evento != null) js("window.__sse(" + q(evento) + "," + q(data.toString()) + ")");
                        evento = null;
                        data.setLength(0);
                    } else if (linea.startsWith("event:")) {
                        evento = linea.substring(6).trim();
                    } else if (linea.startsWith("data:")) {
                        if (data.length() > 0) data.append('\n');
                        data.append(linea.substring(5).trim());
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) try { c.disconnect(); } catch (Exception ignored) { }
            }
            if (gen != streamGen) break;
            js("window.__sseEstado(false)");
            try { Thread.sleep(espera); } catch (InterruptedException ie) { break; }
            espera = Math.min(espera * 2, 30000);
        }
    }

    /* ---------------- Cámara y galería ---------------- */

    private File archivoFoto() {
        File dir = new File(getCacheDir(), "fotos");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "lista.jpg");
    }

    private void abrirFoto(boolean camara) {
        try {
            if (camara) {
                File f = archivoFoto();
                if (f.exists()) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fotos", f);
                Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
                i.setClipData(ClipData.newRawUri("foto", uri));
                i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(i, REQ_CAMARA);
            } else {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(i, "Elige la foto de la lista"), REQ_GALERIA);
            }
        } catch (Exception e) {
            js("window.__foto(null," + q("No se pudo abrir la cámara: " + e.getMessage()) + ")");
        }
    }

    private InputStream abrir(Uri uri) throws IOException {
        if ("file".equals(uri.getScheme())) return new FileInputStream(uri.getPath());
        InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("No se puede abrir la imagen");
        return is;
    }

    private void prepararFoto(Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream is = abrir(uri)) { BitmapFactory.decodeStream(is, null, o); }
            if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("la imagen no se puede leer");
            int lado = Math.max(o.outWidth, o.outHeight), muestra = 1;
            while (lado / (muestra * 2) >= MAX_LADO) muestra *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = muestra;
            Bitmap bm;
            try (InputStream is = abrir(uri)) { bm = BitmapFactory.decodeStream(is, null, o2); }
            if (bm == null) throw new IOException("la imagen no se puede leer");

            int giro = 0;
            try (InputStream is = abrir(uri)) {
                int or = new ExifInterface(is).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (or == ExifInterface.ORIENTATION_ROTATE_90) giro = 90;
                else if (or == ExifInterface.ORIENTATION_ROTATE_180) giro = 180;
                else if (or == ExifInterface.ORIENTATION_ROTATE_270) giro = 270;
            } catch (Exception ignored) { }

            float escala = Math.min(1f, (float) MAX_LADO / Math.max(bm.getWidth(), bm.getHeight()));
            if (escala < 1f || giro != 0) {
                Matrix m = new Matrix();
                if (escala < 1f) m.postScale(escala, escala);
                if (giro != 0) m.postRotate(giro);
                Bitmap b2 = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
                if (b2 != bm) bm.recycle();
                bm = b2;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 85, out);
            bm.recycle();
            String b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            js("window.__foto(" + q(b64) + ",'')");
        } catch (Throwable e) {
            js("window.__foto(null," + q("No se pudo leer la foto: " + e.getMessage()) + ")");
        }
    }

    /* ---------------- Voz ---------------- */

    private void abrirVoz() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Di lo que hay que comprar");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        try {
            startActivityForResult(i, REQ_VOZ);
        } catch (ActivityNotFoundException e) {
            js("window.__voz(null," + q("Este móvil no tiene dictado por voz (hace falta la app de Google).") + ")");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VOZ) {
            if (res == RESULT_OK && data != null) {
                ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (r != null && !r.isEmpty()) {
                    js("window.__voz(" + q(r.get(0)) + ",'')");
                    return;
                }
            }
            js("window.__voz(null,'')");
        } else if (req == REQ_CAMARA || req == REQ_GALERIA) {
            if (res != RESULT_OK) { js("window.__foto(null,'')"); return; }
            final Uri uri = req == REQ_CAMARA ? Uri.fromFile(archivoFoto()) : (data != null ? data.getData() : null);
            if (uri == null) { js("window.__foto(null," + q("No ha llegado ninguna foto") + ")"); return; }
            js("window.__fotoCargando&&__fotoCargando()");
            new Thread(() -> prepararFoto(uri)).start();
        }
    }

    /* ---------------- Lo que puede llamar el JavaScript ---------------- */

    public class Puente {
        @JavascriptInterface public String get(String k) { return prefs.getString(k, ""); }

        @JavascriptInterface public void set(String k, String v) {
            if (v == null || v.isEmpty()) prefs.edit().remove(k).apply();
            else prefs.edit().putString(k, v).apply();
        }

        @JavascriptInterface public void http(final String id, final String metodo, final String url, final String cuerpo, final String cabeceras) {
            new Thread(() -> peticion(id, metodo, url, cuerpo, cabeceras)).start();
        }

        @JavascriptInterface public void streamStart(String url) { iniciarStream(url); }

        @JavascriptInterface public void streamStop() { pararStream(); }

        @JavascriptInterface public void voz() { ui.post(MainActivity.this::abrirVoz); }

        @JavascriptInterface public void foto(final boolean camara) { ui.post(() -> abrirFoto(camara)); }

        @JavascriptInterface public void compartir(final String texto) {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, texto);
                startActivity(Intent.createChooser(i, "Enviar el código con…"));
            });
        }

        @JavascriptInterface public void copiar(final String texto) {
            ui.post(() -> portapapeles.setPrimaryClip(ClipData.newPlainText("lista", texto)));
        }

        @JavascriptInterface public String pegar() {
            try {
                ClipData c = portapapeles.getPrimaryClip();
                if (c != null && c.getItemCount() > 0) {
                    CharSequence t = c.getItemAt(0).coerceToText(MainActivity.this);
                    return t == null ? "" : t.toString();
                }
            } catch (Exception ignored) { }
            return "";
        }

        @JavascriptInterface public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return "?"; }
        }
    }
}
