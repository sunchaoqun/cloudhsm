package com.example.hsmdemo.dto;

/** 通用结果：resultBase64 承载签名/密文/明文；backend 标明当前后端。 */
public record CryptoResponse(String backend, String op, String resultBase64, Boolean valid) {

    public static CryptoResponse of(String backend, String op, byte[] result) {
        return new CryptoResponse(backend, op,
                java.util.Base64.getEncoder().encodeToString(result), null);
    }

    public static CryptoResponse verify(String backend, boolean valid) {
        return new CryptoResponse(backend, "verify", null, valid);
    }
}
