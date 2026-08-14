package com.example.hsmdemo.service;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.Provider;
import java.security.SecureRandom;

/**
 * AES-CBC 加解密（PKCS#11 profile 专用）。
 *
 * <p>为什么 PKCS#11 不用 GCM：CloudHSM 的 AES-GCM 要求 IV 由 HSM 生成、不接受应用传入 IV，
 * 而 JDK 的 SunPKCS11 在 GCM 加密 init 时又必须给 GCMParameterSpec（含 IV），两者冲突导致 init 失败。
 * AES-CBC 在 CloudHSM 与 SunPKCS11 上都正常支持应用传入 IV，故 PKCS#11 用 CBC。
 * （JCE profile 仍用 {@link AesGcmCodec}，因为 CloudHSM 自家 provider 对 GCM 处理不同。）
 *
 * <p>输出格式：{@code IV(16) || 密文}。用 PKCS5 填充。
 */
final class AesCbcCodec {

    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final int IV_LEN = 16;
    private static final SecureRandom RNG = new SecureRandom();

    private AesCbcCodec() {}

    static byte[] encrypt(Provider provider, Key aesKey, byte[] plaintext) throws GeneralSecurityException {
        byte[] iv = new byte[IV_LEN];
        RNG.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION, provider);
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(iv));
        byte[] ct = cipher.doFinal(plaintext);
        byte[] out = new byte[IV_LEN + ct.length];
        System.arraycopy(iv, 0, out, 0, IV_LEN);
        System.arraycopy(ct, 0, out, IV_LEN, ct.length);
        return out;
    }

    static byte[] decrypt(Provider provider, Key aesKey, byte[] blob) throws GeneralSecurityException {
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(blob, 0, iv, 0, IV_LEN);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION, provider);
        cipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(iv));
        return cipher.doFinal(blob, IV_LEN, blob.length - IV_LEN);
    }
}
