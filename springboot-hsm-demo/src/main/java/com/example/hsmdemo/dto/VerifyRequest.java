package com.example.hsmdemo.dto;

/** 验签请求：明文与签名均 Base64。 */
public record VerifyRequest(String dataBase64, String signatureBase64) {}
