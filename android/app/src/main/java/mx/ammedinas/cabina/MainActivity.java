package mx.ammedinas.cabina;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Cabina 360 · AM Medinas
 * Abre la cabina web y convierte el botón del control negro (que el teléfono recibe como "volumen")
 * en el botón INICIAR de la cabina. Los botones de volumen del propio celular siguen funcionando normal.
 */
public class MainActivity extends Activity {

    private static final String BASE = "https://ammedinas.github.io/am-medinas/";
    private static final String AUTORIDAD = "mx.ammedinas.cabina.archivos";
    private static final int PERMISOS = 360;

    private WebView web;
    private PermissionRequest pendiente;
    private final ArrayList<Uri> listos = new ArrayList<>();
    private final ArrayList<String> tipos = new ArrayList<>();

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setUserAgentString(s.getUserAgentString() + " CabinaAMM360App/1");
        web.addJavascriptInterface(new Puente(), "AMMApp");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest r) {
                runOnUiThread(() -> {
                    if (tieneCamara()) r.grant(r.getResources());
                    else { pendiente = r; pedirPermisos(); }
                });
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                String h = u.getHost() == null ? "" : u.getHost();
                if (h.endsWith("ammedinas.github.io") || h.endsWith("script.google.com") || h.endsWith("googleusercontent.com")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { /* sin app para abrirlo */ }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (url != null && url.contains("cabina.html")) {
                    preferencias().edit().putString("ultima", url).apply();
                }
            }
        });

        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        setContentView(web);
        pantallaCompleta();

        web.loadUrl(preferencias().getString("ultima", BASE + "activar-cabina.html"));
        if (!tieneCamara()) pedirPermisos();
        Toast.makeText(this, "App Cabina 360 lista: el control negro inicia la toma", Toast.LENGTH_LONG).show();
    }

    // ---------------- el control negro: "volumen" de un dispositivo Bluetooth = INICIAR
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean volumen = k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN;
        boolean camara = k == KeyEvent.KEYCODE_CAMERA || k == KeyEvent.KEYCODE_FOCUS;
        if (volumen || camara) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                InputDevice d = e.getDevice();
                String origen = d == null ? "control" : d.getName();
                Toast.makeText(this, "📸 INICIAR · " + (k == KeyEvent.KEYCODE_VOLUME_DOWN ? "Vol −" : k == KeyEvent.KEYCODE_VOLUME_UP ? "Vol +" : "Cámara") + " · " + origen, Toast.LENGTH_SHORT).show();
                disparar();
            }
            return true;   // el volumen NO cambia mientras la app está abierta
        }
        return super.dispatchKeyEvent(e);
    }

    // respaldo: si algún equipo manda el botón por aquí, tampoco cambia el volumen
    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) return true;
        return super.onKeyDown(k, e);
    }

    @Override
    public boolean onKeyUp(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) return true;
        return super.onKeyUp(k, e);
    }

    private void disparar() {
        web.requestFocus();
        web.evaluateJavascript("(function(){var o={key:'Enter',code:'ControlNegro',keyCode:13,which:13,bubbles:true};"
                + "window.dispatchEvent(new KeyboardEvent('keydown',o));window.dispatchEvent(new KeyboardEvent('keyup',o));})();", null);
    }

    // ---------------- puente con la página: guardar en galería y compartir (WhatsApp, Bluetooth, redes)
    private class Puente {
        @JavascriptInterface
        public String version() { return "1"; }

        @JavascriptInterface
        public void usarBotonesDelCelular(boolean si) { preferencias().edit().putBoolean("todos", si).apply(); }

        @JavascriptInterface
        public String guardar(String b64, String nombre, String mime) {
            try { enGaleria(b64, nombre, mime); return "ok"; }
            catch (Exception ex) { return "error: " + ex.getMessage(); }
        }

        @JavascriptInterface
        public void limpiar() {
            synchronized (listos) { listos.clear(); tipos.clear(); }
        }

        @JavascriptInterface
        public String preparar(String b64, String nombre, String mime) {
            try {
                Uri u = enGaleria(b64, nombre, mime);
                synchronized (listos) { listos.add(u); tipos.add(limpiarMime(mime, nombre)); }
                return "ok";
            } catch (Exception ex) {
                return "error: " + ex.getMessage();
            }
        }

        @JavascriptInterface
        public String compartir(String texto, String paquete) { return compartirA(texto, paquete, ""); }

        @JavascriptInterface
        public String compartirA(String texto, String paquete, String numero) {
            final ArrayList<Uri> uris;
            final ArrayList<String> mimes;
            synchronized (listos) {
                if (listos.isEmpty()) return "error: no hay archivos";
                uris = new ArrayList<>(listos);
                mimes = new ArrayList<>(tipos);
            }
            boolean imagenes = true, videos = true;
            for (String t : mimes) { if (!t.startsWith("image")) imagenes = false; if (!t.startsWith("video")) videos = false; }
            final String app = instalada(paquete) ? paquete
                    : ("com.whatsapp".equals(paquete) && instalada("com.whatsapp.w4b") ? "com.whatsapp.w4b" : null);
            final boolean whats = app != null && app.startsWith("com.whatsapp");

            final Intent i;
            if (uris.size() == 1) {
                i = new Intent(Intent.ACTION_SEND);
                i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                i.setType(mimes.get(0));
            } else {
                i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                i.setType(imagenes ? "image/*" : videos ? "video/*" : "*/*");
                if (!imagenes && !videos) i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            }
            // WhatsApp: solo fotos y video (con texto, a veces manda solo el texto)
            if (!whats && app == null && texto != null && !texto.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, texto);
            ClipData clip = ClipData.newRawUri("", uris.get(0));
            for (int n = 1; n < uris.size(); n++) clip.addItem(new ClipData.Item(uris.get(n)));
            i.setClipData(clip);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String digitos = numero == null ? "" : numero.replaceAll("\\D", "");
            if (whats && digitos.length() >= 10) {                    // abre directo el chat de ese número
                if (digitos.length() == 10) digitos = "52" + digitos;
                i.putExtra("jid", digitos + "@s.whatsapp.net");
            }

            runOnUiThread(() -> {
                try {
                    if (app != null) { i.setPackage(app); startActivity(i); }
                    else startActivity(Intent.createChooser(i, "Enviar con…").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                } catch (Exception ex) {
                    i.setPackage(null);
                    try { startActivity(Intent.createChooser(i, "Enviar con…")); }
                    catch (Exception ex2) { Toast.makeText(MainActivity.this, "No se pudo compartir", Toast.LENGTH_LONG).show(); }
                }
            });
            return app != null ? "app" : "menu";
        }
    }

    // ---------------- archivos en la Galería (AM Medinas 360): WhatsApp y cualquier app los pueden leer
    private final HashMap<String, Uri> enGaleriaYa = new HashMap<>();

    private static String limpiarMime(String mime, String nombre) {
        String m = mime == null ? "" : mime.split(";")[0].trim();
        String n = nombre == null ? "" : nombre.toLowerCase();
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        return m.isEmpty() ? "application/octet-stream" : m;
    }

    private synchronized Uri enGaleria(String b64, String nombre, String mimeOriginal) throws Exception {
        byte[] datos = Base64.decode(b64, Base64.DEFAULT);
        String clave = nombre + ":" + datos.length;
        Uri ya = enGaleriaYa.get(clave);
        if (ya != null) return ya;

        String mime = limpiarMime(mimeOriginal, nombre);
        boolean video = mime.startsWith("video");
        if (video && mime.equals("video/mp4")) datos = mp4Normal(datos);   // MP4 "por partes" → MP4 normal que WhatsApp sí reproduce

        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, nombre);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/AM Medinas 360");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri coleccion = video
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        ContentResolver cr = getContentResolver();
        Uri u = cr.insert(coleccion, v);
        if (u == null) throw new Exception("no se pudo crear el archivo en la Galería");
        try (OutputStream o = cr.openOutputStream(u)) { if (o != null) o.write(datos); }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(u, v, null, null);
        enGaleriaYa.put(clave, u);
        return u;
    }

    private byte[] mp4Normal(byte[] datos) {
        File dir = new File(getCacheDir(), "video");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File ent = new File(dir, "entrada.mp4"), sal = new File(dir, "salida.mp4");
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mx = null;
        try {
            try (FileOutputStream o = new FileOutputStream(ent)) { o.write(datos); }
            ex.setDataSource(ent.getAbsolutePath());
            mx = new MediaMuxer(sal.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int n = ex.getTrackCount();
            int[] pistas = new int[n];
            int validas = 0;
            for (int t = 0; t < n; t++) {
                MediaFormat f = ex.getTrackFormat(t);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && (m.startsWith("video/") || m.startsWith("audio/"))) { ex.selectTrack(t); pistas[t] = mx.addTrack(f); validas++; }
                else pistas[t] = -1;
            }
            if (validas == 0) return datos;
            mx.start();
            ByteBuffer buf = ByteBuffer.allocate(16 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long base = -1, anterior = -1;
            while (true) {
                int tam = ex.readSampleData(buf, 0);
                if (tam < 0) break;
                int t = ex.getSampleTrackIndex();
                long ts = ex.getSampleTime();
                if (base < 0) base = ts;
                long pts = Math.max(0, ts - base);
                if (pts <= anterior) pts = anterior + 1;            // tiempos siempre crecientes
                anterior = pts;
                info.set(0, tam, pts, (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                if (t >= 0 && pistas[t] >= 0) mx.writeSampleData(pistas[t], buf, info);
                ex.advance();
            }
            mx.stop();
            mx.release(); mx = null;
            byte[] r = new byte[(int) sal.length()];
            try (FileInputStream in = new FileInputStream(sal)) { int off = 0, k; while (off < r.length && (k = in.read(r, off, r.length - off)) > 0) off += k; }
            return r.length > 1000 ? r : datos;
        } catch (Exception e) {
            return datos;                                         // si no se puede convertir, se usa el original
        } finally {
            try { if (mx != null) mx.release(); } catch (Exception ignored) { }
            ex.release();
            //noinspection ResultOfMethodCallIgnored
            ent.delete();
            //noinspection ResultOfMethodCallIgnored
            sal.delete();
        }
    }

    private boolean instalada(String paquete) {
        if (paquete == null || paquete.isEmpty()) return false;
        try { getPackageManager().getPackageInfo(paquete, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    // ---------------- permisos, pantalla completa y navegación
    private SharedPreferences preferencias() { return getSharedPreferences("amm360", MODE_PRIVATE); }

    private boolean tieneCamara() { return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; }

    private void pedirPermisos() { requestPermissions(new String[]{Manifest.permission.CAMERA}, PERMISOS); }

    @Override
    public void onRequestPermissionsResult(int codigo, String[] permisos, int[] resultados) {
        super.onRequestPermissionsResult(codigo, permisos, resultados);
        if (pendiente != null) {
            if (tieneCamara()) pendiente.grant(pendiente.getResources()); else pendiente.deny();
            pendiente = null;
        } else if (tieneCamara()) {
            web.reload();
        }
    }

    @SuppressWarnings("deprecation")
    private void pantallaCompleta() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean foco) {
        super.onWindowFocusChanged(foco);
        if (foco) pantallaCompleta();
    }

    @Override
    protected void onResume() { super.onResume(); web.onResume(); }

    @Override
    protected void onPause() { web.onPause(); super.onPause(); }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
