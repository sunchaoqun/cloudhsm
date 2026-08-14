package com.example.hsmdemo.service;

import com.example.hsmdemo.config.HsmProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.model.*;

/**
 * 方式一：通用 AWS KMS（profile {@code kms}）。
 *
 * <p>纯 Java（AWS SDK v2），无需本地库，凭据走默认链（环境变量 / ~/.aws / IAM 角色）。
 * <ul>
 *   <li>签名/验签：<b>KMS 普通（AWS 托管）的非对称 key</b>（{@code hsm.kms.sign-key-id}），Sign/Verify API。</li>
 *   <li>加密/解密：对称 key（{@code hsm.kms.encrypt-key-id}），逻辑在 {@link AbstractKmsCryptoService}。</li>
 * </ul>
 *
 * <p><b>注意：本 profile 面向普通 KMS key，签名用的 key 不能是 CloudHSM Custom Key Store 的 key</b>
 * （那种 key store 只支持对称密钥）。若你的 KMS 就是接 CloudHSM Custom Key Store，请改用
 * {@code kms-cks} profile（{@link KmsCustomKeyStoreCryptoService}），它不暴露签名能力。
 * 若要用 CloudHSM 硬件做签名，请用 {@code jce} / {@code pkcs11}。
 */
@Service
@Profile("kms")
public class KmsCryptoService extends AbstractKmsCryptoService {

    public KmsCryptoService(HsmProperties props) {
        super(props);
    }

    @Override
    public String backend() {
        return "AWS KMS 通用 (region=" + cfg.getRegion() + ")";
    }

    // capabilities() 用接口默认值：sign/verify/encrypt/decrypt 全支持。

    @Override
    public byte[] sign(byte[] plaintext) {
        requireKey(cfg.getSignKeyId(), "hsm.kms.sign-key-id");
        SignResponse resp = kms.sign(SignRequest.builder()
                .keyId(cfg.getSignKeyId())
                .messageType(MessageType.RAW)
                .message(SdkBytes.fromByteArray(plaintext))
                .signingAlgorithm(SigningAlgorithmSpec.fromValue(cfg.getSigningAlgorithm()))
                .build());
        return resp.signature().asByteArray();
    }

    @Override
    public boolean verify(byte[] plaintext, byte[] signature) {
        requireKey(cfg.getSignKeyId(), "hsm.kms.sign-key-id");
        try {
            VerifyResponse resp = kms.verify(VerifyRequest.builder()
                    .keyId(cfg.getSignKeyId())
                    .messageType(MessageType.RAW)
                    .message(SdkBytes.fromByteArray(plaintext))
                    .signature(SdkBytes.fromByteArray(signature))
                    .signingAlgorithm(SigningAlgorithmSpec.fromValue(cfg.getSigningAlgorithm()))
                    .build());
            return resp.signatureValid();
        } catch (KmsInvalidSignatureException e) {
            return false;
        }
    }
}
