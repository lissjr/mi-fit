package com.hanxing.mifit;

import android.content.Context;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 记住的佳明账号密码：AES-256/GCM 加密后存进 prefs，
 * 密钥放在 Android Keystore（不可导出），其它 App 拿到密文也解不开。
 */
public class Vault {
    private static final String ALIAS = "mfit_acct";

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
                        | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return kg.generateKey();
    }

    public static String seal(String plain) {
        if (plain == null || plain.length() == 0) return "";
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return Base64.encodeToString(all, Base64.NO_WRAP);
        } catch (Exception e) {
            android.util.Log.w("mfit", "vault seal fail " + e.getMessage());
            return "";
        }
    }

    public static String open(String sealed) {
        if (sealed == null || sealed.length() == 0) return "";
        try {
            byte[] all = Base64.decode(sealed, Base64.NO_WRAP);
            int ivLen = 12;
            byte[] iv = new byte[ivLen];
            byte[] ct = new byte[all.length - ivLen];
            System.arraycopy(all, 0, iv, 0, ivLen);
            System.arraycopy(all, ivLen, ct, 0, ct.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    // ---- 文件级加密：MFE1 + IV(12) + AES-GCM 密文 ----
    /** 把整份文件内容加密；失败返回 null（调用方回退明文写入，不丢数据） */
    public static byte[] sealBytes(byte[] plain) {
        if (plain == null) return null;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain);
            byte[] all = new byte[4 + iv.length + ct.length];
            all[0] = 'M'; all[1] = 'F'; all[2] = 'E'; all[3] = '1';
            System.arraycopy(iv, 0, all, 4, iv.length);
            System.arraycopy(ct, 0, all, 4 + iv.length, ct.length);
            return all;
        } catch (Exception e) {
            android.util.Log.w("mfit", "sealBytes fail " + e.getMessage());
            return null;
        }
    }

    /** 解密文件内容；不是密文（旧版明文缓存）或坏了返回 null */
    public static byte[] openBytes(byte[] sealed) {
        if (sealed == null || sealed.length < 24) return null;
        if (!(sealed[0] == 'M' && sealed[1] == 'F' && sealed[2] == 'E' && sealed[3] == '1')) return null;
        try {
            byte[] iv = new byte[12];
            System.arraycopy(sealed, 4, iv, 0, 12);
            byte[] ct = new byte[sealed.length - 16];
            System.arraycopy(sealed, 16, ct, 0, ct.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return c.doFinal(ct);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- prefs 读写 ----
    private static android.content.SharedPreferences sp(Context c) {
        return c.getSharedPreferences("mfit", Context.MODE_PRIVATE);
    }

    /** 存账号密码（加密），明白记下来 */
    public static void remember(Context c, String user, String pass) {
        String eu = seal(user), ep = seal(pass);
        if (eu.length() == 0 || ep.length() == 0) return;
        sp(c).edit().putString("acct_u", eu).putString("acct_p", ep)
                .putBoolean("remember", true).apply();
    }

    public static boolean on(Context c) {
        return sp(c).getBoolean("remember", false)
                && sp(c).getString("acct_u", "").length() > 0;
    }

    public static String user(Context c) { return open(sp(c).getString("acct_u", "")); }

    public static String pass(Context c) { return open(sp(c).getString("acct_p", "")); }

    /** 忘掉账号密码 */
    public static void forget(Context c) {
        sp(c).edit().remove("acct_u").remove("acct_p").putBoolean("remember", false).apply();
    }
}
