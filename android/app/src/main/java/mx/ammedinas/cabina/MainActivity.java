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

import java.io.File;
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
    }

    // ---------------- el control negro: "volumen" de un dispositivo Bluetooth = INICIAR
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean volumen = k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN;
        boolean camara = k == KeyEvent.KEYCODE_CAMERA || k == KeyEvent.KEYCODE_FOCUS;
        if ((volumen && esExterno(e)) || camara) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) disparar();
            return true;   // el volumen del teléfono NO cambia
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean esExterno(KeyEvent e) {
        if (preferencias().getBoolean("todos", false)) return true;   // la cabina puede pedir usar también los botones del celular
        InputDevice d = e.getDevice();
        return d == null || d.isExternal();
    }

    private void disparar() {
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
            try {
                byte[] datos = Base64.decode(b64, Base64.DEFAULT);
                if (mime == null || mime.isEmpty()) mime = "image/jpeg";
                mime = mime.split(";")[0].trim();
                boolean video = mime != null && mime.startsWith("video");
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
                if (u == null) return "error: no se pudo crear el archivo";
                try (OutputStream o = cr.openOutputStream(u)) { if (o != null) o.write(datos); }
                v.clear();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(u, v, null, null);
                return "ok";
            } catch (Exception ex) {
                return "error: " + ex.getMessage();
            }
        }

        @JavascriptInterface
        public void limpiar() {
            synchronized (listos) { listos.clear(); tipos.clear(); }
            File dir = new File(getCacheDir(), "compartir");
            File[] viejos = dir.listFiles();
            if (viejos != null) for (File f : viejos) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }

        @JavascriptInterface
        public String preparar(String b64, String nombre, String mime) {
            try {
                File dir = new File(getCacheDir(), "compartir");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File f = new File(dir, nombre.replaceAll("[\\\\/:*?\"<>|]", "-"));
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(Base64.decode(b64, Base64.DEFAULT)); }
                Uri u = FileProvider.getUriForFile(MainActivity.this, AUTORIDAD, f);
                synchronized (listos) { listos.add(u); tipos.add(mime == null ? "*/*" : mime); }
                return "ok";
            } catch (Exception ex) {
                return "error: " + ex.getMessage();
            }
        }

        @JavascriptInterface
        public String compartir(String texto, String paquete) {
            final ArrayList<Uri> uris;
            String tipo;
            synchronized (listos) {
                if (listos.isEmpty()) return "error: no hay archivos";
                uris = new ArrayList<>(listos);
                boolean imagenes = true, videos = true;
                for (String t : tipos) { if (!t.startsWith("image")) imagenes = false; if (!t.startsWith("video")) videos = false; }
                tipo = imagenes ? "image/*" : videos ? "video/*" : "*/*";
            }
            final Intent i;
            if (uris.size() == 1) {
                i = new Intent(Intent.ACTION_SEND);
                i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            i.setType(tipo);
            if (texto != null && !texto.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, texto);
            ClipData clip = ClipData.newRawUri("", uris.get(0));
            for (int n = 1; n < uris.size(); n++) clip.addItem(new ClipData.Item(uris.get(n)));
            i.setClipData(clip);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            final String app = instalada(paquete) ? paquete : (instalada("com.whatsapp.w4b") && "com.whatsapp".equals(paquete) ? "com.whatsapp.w4b" : null);
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
