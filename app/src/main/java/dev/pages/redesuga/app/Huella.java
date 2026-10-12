package dev.pages.redesuga.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.biometric.BiometricManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Ingreso con huella o rostro: usuario y contraseña cifrados con una clave del Keystore que solo
 * se libera con la biometría fuerte del dueño del celular. Si se agrega otra huella, la clave se
 * invalida y hay que volver a activarlo.
 */
final class Huella {
    private static final String ALIAS = "redes-uga-huella";
    private static final String PREFS = "huella";
    private static final int FUERTE = BiometricManager.Authenticators.BIOMETRIC_STRONG;

    private Huella() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean disponible(Context c) {
        return BiometricManager.from(c).canAuthenticate(FUERTE) == BiometricManager.BIOMETRIC_SUCCESS;
    }

    static boolean guardada(Context c) {
        return prefs(c).contains("datos");
    }

    static String usuario(Context c) {
        return prefs(c).getString("usuario", "");
    }

    static boolean noPreguntar(Context c) {
        return prefs(c).getBoolean("no_preguntar", false);
    }

    static void noPreguntarMas(Context c) {
        prefs(c).edit().putBoolean("no_preguntar", true).apply();
    }

    static String estado(Context c) {
        try {
            return new JSONObject()
                    .put("disponible", disponible(c))
                    .put("guardada", guardada(c))
                    .put("usuario", usuario(c))
                    .put("noPreguntar", noPreguntar(c))
                    .toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    private static SecretKey clave() throws GeneralSecurityException, java.io.IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true);
        if (Build.VERSION.SDK_INT >= 24) b.setInvalidatedByBiometricEnrollment(true);
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(b.build());
        return g.generateKey();
    }

    /** Cifrador listo para que la biometría lo habilite; null si la clave ya no sirve. */
    static Cipher paraGuardar() {
        // Una clave invalidada (huella nueva en el celular) se descarta y se crea otra
        for (int intento = 0; intento < 2; intento++) {
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, clave());
                return c;
            } catch (Exception e) {
                borrarClave();
            }
        }
        return null;
    }

    static Cipher paraLeer(Context ctx) {
        try {
            byte[] iv = Base64.decode(prefs(ctx).getString("iv", ""), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, clave(), new GCMParameterSpec(128, iv));
            return c;
        } catch (Exception e) {
            // Por ejemplo, se agregó una huella nueva al celular: hay que volver a activarlo
            olvidar(ctx);
            return null;
        }
    }

    static boolean guardar(Context ctx, Cipher c, String usuario, String contrasena) {
        try {
            String json = new JSONObject().put("u", usuario).put("c", contrasena).toString();
            byte[] datos = c.doFinal(json.getBytes(StandardCharsets.UTF_8));
            prefs(ctx).edit()
                    .putString("datos", Base64.encodeToString(datos, Base64.NO_WRAP))
                    .putString("iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
                    .putString("usuario", usuario)
                    .remove("no_preguntar")
                    .apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** {usuario, contraseña} o null si no se pudo descifrar. */
    static String[] leer(Context ctx, Cipher c) {
        try {
            byte[] datos = Base64.decode(prefs(ctx).getString("datos", ""), Base64.NO_WRAP);
            JSONObject o = new JSONObject(new String(c.doFinal(datos), StandardCharsets.UTF_8));
            return new String[]{o.getString("u"), o.getString("c")};
        } catch (Exception e) {
            olvidar(ctx);
            return null;
        }
    }

    static void olvidar(Context ctx) {
        prefs(ctx).edit().remove("datos").remove("iv").remove("usuario").apply();
        borrarClave();
    }

    private static void borrarClave() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.deleteEntry(ALIAS);
        } catch (Exception e) {
            // No había clave
        }
    }
}
