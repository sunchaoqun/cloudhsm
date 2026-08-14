package com.example.hsmdemo.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 兜底：未激活任何 profile（kms/jce/pkcs11）时提供一个占位实现，
 * 让应用能正常启动，并在调用时给出清晰提示，而不是启动即崩。
 */
@Configuration
public class FallbackCryptoConfig {

    @Bean
    @ConditionalOnMissingBean(CryptoService.class)
    public CryptoService noOpCryptoService() {
        return new CryptoService() {
            @Override public String backend() {
                return "NONE — 未激活后端，请用 --spring.profiles.active=kms|kms-cks|jce|pkcs11 启动";
            }
            @Override public java.util.Set<String> capabilities() {
                return java.util.Set.of();
            }
            private RuntimeException notActive() {
                return new IllegalStateException(
                        "未激活任何后端 profile。启动时加 --spring.profiles.active=kms（或 jce / pkcs11）");
            }
            @Override public byte[] sign(byte[] p) { throw notActive(); }
            @Override public boolean verify(byte[] p, byte[] s) { throw notActive(); }
            @Override public byte[] encrypt(byte[] p) { throw notActive(); }
            @Override public byte[] decrypt(byte[] c) { throw notActive(); }
        };
    }
}
