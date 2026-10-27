package com.example.dsh_apk;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecretStore {
    private static final String ALIAS = "dsh_remote_token";
    private static final String PREFS = "connection";
    private final SharedPreferences prefs;

    SecretStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String token(String url) {
        String suffix = keySuffix(url);
        String iv = prefs.getString("iv_" + suffix, null);
        String encrypted = prefs.getString("token_" + suffix, null);
        boolean legacy = iv == null && url.equals(prefs.getString("token_url", ""));
        if (legacy) {
            iv = prefs.getString("iv", null);
            encrypted = prefs.getString("token", null);
        }
        if (iv == null || encrypted == null) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            if (legacy) prefs.edit().remove("iv").remove("token").apply();
            else prefs.edit().remove("iv_" + suffix).remove("token_" + suffix).apply();
            return "";
        }
    }

    void saveToken(String token, String url) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        String suffix = keySuffix(url);
        prefs.edit()
                .putString("iv_" + suffix, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString("token_" + suffix, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .apply();
    }

    void removeToken(String url) {
        String suffix = keySuffix(url);
        SharedPreferences.Editor edit = prefs.edit().remove("iv_" + suffix)
                .remove("token_" + suffix);
        if (url.equals(prefs.getString("token_url", ""))) {
            edit.remove("iv").remove("token").remove("token_url");
        }
        edit.apply();
    }

    private String keySuffix(String url) {
        return Base64.encodeToString(url.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
