package com.example.hsmdemo.dto;

/** 签名/加密请求：明文用 Base64 传入。 */
public record DataRequest(String dataBase64) {}
