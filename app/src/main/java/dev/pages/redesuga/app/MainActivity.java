package dev.pages.redesuga.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.SystemBarStyle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Abre la plataforma en un visor web propio de la app: funciona en cualquier Android, sin depender
 * de que Chrome (u otro navegador) esté instalado o actualizado.
 */
public class MainActivity extends ComponentActivity {
    static final String INICIO = "https://" + BuildConfig.SITIO + "/";
    private static final String SIN_CONEXION = "file:///android_asset/sin-conexion.html";

    private WebView web;
    private ValueCallback<Uri[]> eleccion;
    private Uri fotoCamara;

    private final ActivityResultLauncher<Intent> elegirArchivos = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                List<Uri> uris = new ArrayList<>();
                Intent datos = r.getData();
                if (r.getResultCode() == RESULT_OK && datos != null) {
                    ClipData varios = datos.getClipData();
                    if (varios != null) {
                        for (int i = 0; i < varios.getItemCount(); i++) uris.add(varios.getItemAt(i).getUri());
                    } else if (datos.getData() != null) {
                        uris.add(datos.getData());
                    }
                }
                entregar(uris.isEmpty() ? null : uris.toArray(new Uri[0]));
            });

    private final ActivityResultLauncher<Uri> sacarFoto = registerForActivityResult(
            new ActivityResultContracts.TakePicture(), ok -> entregar(ok ? new Uri[]{fotoCamara} : null));

    private final ActivityResultLauncher<String> pedirPermisoAvisos = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), concedido -> { });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle estado) {
        int fondo = ContextCompat.getColor(this, R.color.fondo);
        EdgeToEdge.enable(this, SystemBarStyle.dark(fondo), SystemBarStyle.dark(fondo));
        super.onCreate(estado);

        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackgroundColor(fondo);
        web = new WebView(this);
        web.setBackgroundColor(fondo);
        raiz.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(raiz);
        // La página no queda debajo de la barra de estado, la de navegación ni el teclado
        ViewCompat.setOnApplyWindowInsetsListener(raiz, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " RedesUGA-App/" + BuildConfig.VERSION_NAME);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        web.addJavascriptInterface(new Puente(), "RedesUGA");
        web.setWebViewClient(new Cliente());
        web.setWebChromeClient(new Cromo());
        web.setDownloadListener((url, agente, disposicion, tipo, largo) -> descargar(url, agente, disposicion, tipo));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) web.goBack();
                else finish();
            }
        });

        // Si Android cerró la app mientras estaba en la cámara, vuelve a la página en la que estaba
        if (estado == null || web.restoreState(estado) == null) web.loadUrl(destino(getIntent()));

        Avisos.programar(this);
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pedirPermisoAvisos.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getData() != null || intent.hasExtra(Avisos.EXTRA_URL)) web.loadUrl(destino(intent));
    }

    @Override
    protected void onSaveInstanceState(Bundle estado) {
        super.onSaveInstanceState(estado);
        web.saveState(estado);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    // Enlace de la plataforma, ruta de un aviso o la página de inicio
    private static String destino(Intent intent) {
        Uri datos = intent.getData();
        if (datos != null && esDelSitio(datos)) return datos.toString();
        String ruta = intent.getStringExtra(Avisos.EXTRA_URL);
        if (ruta != null && ruta.startsWith("/") && !ruta.startsWith("//")) return INICIO + ruta.substring(1);
        return INICIO;
    }

    static boolean esDelSitio(Uri u) {
        return "https".equals(u.getScheme()) && BuildConfig.SITIO.equals(u.getHost());
    }

    private void entregar(Uri[] uris) {
        if (eleccion != null) eleccion.onReceiveValue(uris);
        eleccion = null;
    }

    private void descargar(String url, String agente, String disposicion, String tipo) {
        Uri u = Uri.parse(url);
        if (!esDelSitio(u)) {
            abrirAfuera(u);
            return;
        }
        String nombre = URLUtil.guessFileName(url, disposicion, tipo);
        DownloadManager.Request pedido = new DownloadManager.Request(u)
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                .addRequestHeader("User-Agent", agente)
                .setTitle(nombre)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        // Desde Android 10 la carpeta Descargas no pide permisos; antes, la carpeta propia de la app
        if (Build.VERSION.SDK_INT >= 29) pedido.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nombre);
        else pedido.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, nombre);
        try {
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(pedido);
            Toast.makeText(this, "Descargando " + nombre + "…", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(this, "No se pudo descargar el archivo.", Toast.LENGTH_LONG).show();
        }
    }

    private void abrirAfuera(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No hay una app para abrir este enlace.", Toast.LENGTH_SHORT).show();
        }
    }

    private class Cliente extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView vista, WebResourceRequest pedido) {
            Uri u = pedido.getUrl();
            if (esDelSitio(u)) return false;
            // Otros sitios, WhatsApp, correo o teléfono: con la app que corresponda
            abrirAfuera(u);
            return true;
        }

        @Override
        public void onPageFinished(WebView vista, String url) {
            if (url == null || !url.startsWith(INICIO)) return;
            Uri u = Uri.parse(url);
            String ruta = u.getPath() == null ? "/" : u.getPath();
            // En la pantalla de ingreso no hay sesión: la clave de avisos de quien salió deja de usarse
            if (ruta.startsWith("/login")) Avisos.olvidarClave(MainActivity.this);
            else Avisos.asegurarClave(MainActivity.this);
        }

        @Override
        public void onReceivedError(WebView vista, WebResourceRequest pedido, WebResourceError error) {
            if (pedido.isForMainFrame() && esDelSitio(pedido.getUrl())) vista.loadUrl(SIN_CONEXION);
        }
    }

    private class Cromo extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView vista, ValueCallback<Uri[]> callback, FileChooserParams opciones) {
            if (eleccion != null) eleccion.onReceiveValue(null);
            eleccion = callback;
            if (opciones.isCaptureEnabled() && sacarFotoConCamara()) return true;
            boolean soloImagenes = true;
            for (String t : opciones.getAcceptTypes()) if (t == null || !t.startsWith("image/")) soloImagenes = false;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(soloImagenes ? "image/*" : "*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, opciones.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
            try {
                elegirArchivos.launch(Intent.createChooser(i, "Elegí el archivo"));
            } catch (ActivityNotFoundException e) {
                entregar(null);
                Toast.makeText(MainActivity.this, "No hay una app para elegir archivos.", Toast.LENGTH_LONG).show();
            }
            return true;
        }

        private boolean sacarFotoConCamara() {
            try {
                File dir = new File(getCacheDir(), "fotos");
                if (!dir.isDirectory() && !dir.mkdirs()) return false;
                File foto = new File(dir, "foto-" + System.currentTimeMillis() + ".jpg");
                fotoCamara = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".archivos", foto);
                sacarFoto.launch(fotoCamara);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    /** Lo único que la página puede pedirle a la app: estado y prueba de los avisos. */
    private class Puente {
        @JavascriptInterface
        public boolean avisosPermitidos() {
            return Avisos.permitidos(MainActivity.this);
        }

        @JavascriptInterface
        public boolean avisoDePrueba() {
            if (!Avisos.permitidos(MainActivity.this)) {
                runOnUiThread(() -> {
                    if (Build.VERSION.SDK_INT >= 33) pedirPermisoAvisos.launch(Manifest.permission.POST_NOTIFICATIONS);
                });
                return false;
            }
            Avisos.mostrar(MainActivity.this, "prueba", "Avisos activados",
                    "Así te vamos a avisar de las clases en vivo y los quizzes.", "/perfil");
            return true;
        }
    }
}
