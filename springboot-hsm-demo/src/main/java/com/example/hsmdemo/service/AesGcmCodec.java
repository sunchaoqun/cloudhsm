package com.example.hsmdemo.service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.Provider;

/**
 * AES-GCM 加解密的共用编解码逻辑（JCE / PKCS#11 两个后端复用，避免重复）。
 *
 * <p>CloudHSM 的 AES-GCM IV 由 HSM 生成，长度不保证是 12 字节（FIPS 下可能不同），
 * 故输出格式为 {@code [ivLen(1B)] || IV || 密文+Tag}，解密时按前缀精确还原 IV 长度，
 * 不写死长度。tag 固定 128 位（CloudHSM 仅支持该长度）。
 */
final class AesGcmCodec {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    private AesGcmCodec() {}

    static byte[] encrypt(Provider provider, Key aesKey, byte[] plaintext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION, provider);
        cipher.init(Cipher.ENCRYPT_MODE, aesKey);
        // CloudHSM 的 IV 由 HSM 在加密时生成，必须在 doFinal 之后才能取到真实 IV；
        // 若在 doFinal 之前调 getIV()，拿到的是初始化的全零缓冲区（曾导致解密 DataException）。
        byte[] ct = cipher.doFinal(plaintext);
        byte[] iv = cipher.getIV();
        byte[] out = new byte[1 + iv.length + ct.length];
        out[0] = (byte) iv.length;
        System.arraycopy(iv, 0, out, 1, iv.length);
        System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
        return out;
    }

    static byte[] decrypt(Provider provider, Key aesKey, byte[] blob) throws GeneralSecurityException {
        int ivLen = blob[0] & 0xFF;
        byte[] iv = new byte[ivLen];
        System.arraycopy(blob, 1, iv, 0, ivLen);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION, provider);
        cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
        int ctOffset = 1 + ivLen;
        return cipher.doFinal(blob, ctOffset, blob.length - ctOffset);
    }
}
