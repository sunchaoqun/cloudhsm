package com.example.hsmdemo.service;

import com.example.hsmdemo.config.HsmProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 方式一（变体）：KMS Custom Key Store —— CloudHSM key store（profile {@code kms-cks}）。
 *
 * <p>这是"在 CloudHSM 下用 KMS"的真实场景：KMS 的 key 建在你的 CloudHSM 集群里，
 * 加解密运算在 HSM 硬件内完成。加解密逻辑与通用 KMS 完全一致，故复用
 * {@link AbstractKmsCryptoService}，此处不重复实现。
 *
 * <p><b>官方限制：Custom Key Store 只支持对称加密密钥，不支持非对称密钥，因此无法签名/验签。</b>
 * 参考：https://docs.aws.amazon.com/kms/latest/developerguide/create-cmk-keystore.html
 *
 * <p>因此本实现：
 * <ul>
 *   <li>{@link #capabilities()} 只声明 encrypt / decrypt —— 前端据此不会显示签名按钮。</li>
 *   <li>{@link #sign} / {@link #verify} 一旦被调用，直接抛出清晰异常，杜绝误用。</li>
 * </ul>
 * 若确需签名，请改用 {@code jce} / {@code pkcs11}（CloudHSM 硬件）或 {@code kms}（普通非对称 key）。
 */
@Service
@Profile("kms-cks")
public class KmsCustomKeyStoreCryptoService extends AbstractKmsCryptoService {

    public KmsCustomKeyStoreCryptoService(HsmProperties props) {
        super(props);
    }

    @Override
    public String backend() {
        return "AWS KMS · CloudHSM Custom Key Store (region=" + cfg.getRegion() + "，仅对称加解密)";
    }

    @Override
    public Set<String> capabilities() {
        return Set.of(CAP_ENCRYPT, CAP_DECRYPT);
    }

    @Override
    public byte[] sign(byte[] plaintext) {
        throw new UnsupportedOperationException(
                "KMS Custom Key Store 不支持签名（只支持对称密钥）。请用 jce/pkcs11（HSM 硬件签名）或 kms（普通非对称 key）。");
    }

    @Override
    public boolean verify(byte[] plaintext, byte[] signature) {
        throw new UnsupportedOperationException(
                "KMS Custom Key Store 不支持验签（只支持对称密钥）。请用 jce/pkcs11（HSM 硬件签名）或 kms（普通非对称 key）。");
    }
}
