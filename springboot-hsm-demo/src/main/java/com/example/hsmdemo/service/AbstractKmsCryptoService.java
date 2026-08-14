package com.example.hsmdemo.service;

import com.example.hsmdemo.config.HsmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KMS 两种后端（通用 KMS / Custom Key Store）的共用基类。
 *
 * <p>把 {@link KmsClient} 的构建与【对称加解密】逻辑集中在这里，避免在两个 profile
 * 实现里复制粘贴。子类只需决定：是否支持签名、以及 capabilities。
 *
 * <p>加解密对两种后端完全一致（都用对称 key 的 Encrypt/Decrypt，
 * Custom Key Store 的差异对调用方透明），因此放在基类。
 */
abstract class AbstractKmsCryptoService implements CryptoService {

    private static final Logger log = LoggerFactory.getLogger(AbstractKmsCryptoService.class);

    protected final KmsClient kms;
    protected final HsmProperties.Kms cfg;
    private final StsClient sts;
    private volatile Map<String, String> cachedIdentity;

    protected AbstractKmsCryptoService(HsmProperties props) {
        this.cfg = props.getKms();
        Region region = Region.of(cfg.getRegion());
        this.kms = KmsClient.builder().region(region).build();
        this.sts = StsClient.builder().region(region).build();
    }

    /**
     * 返回当前 AWS 调用身份（account + arn），用于页面展示是哪个账号在调用。
     * 失败时返回 null（不影响 /api/info 其它字段），并结果缓存避免每次都打 STS。
     */
    @Override
    public Map<String, String> identity() {
        if (cachedIdentity != null) {
            return cachedIdentity;
        }
        try {
            GetCallerIdentityResponse id = sts.getCallerIdentity();
            Map<String, String> m = new LinkedHashMap<>();
            m.put("account", id.account());
            m.put("arn", id.arn());
            m.put("region", cfg.getRegion());
            cachedIdentity = m;
            return m;
        } catch (Exception e) {
            log.warn("获取 STS 调用身份失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public byte[] encrypt(byte[] plaintext) {
        requireKey(cfg.getEncryptKeyId(), "hsm.kms.encrypt-key-id");
        EncryptResponse resp = kms.encrypt(EncryptRequest.builder()
                .keyId(cfg.getEncryptKeyId())
                .plaintext(SdkBytes.fromByteArray(plaintext))
                .build());
        return resp.ciphertextBlob().asByteArray();
    }

    @Override
    public byte[] decrypt(byte[] ciphertext) {
        requireKey(cfg.getEncryptKeyId(), "hsm.kms.encrypt-key-id");
        DecryptResponse resp = kms.decrypt(DecryptRequest.builder()
                .keyId(cfg.getEncryptKeyId())
                .ciphertextBlob(SdkBytes.fromByteArray(ciphertext))
                .build());
        return resp.plaintext().asByteArray();
    }

    protected static void requireKey(String value, String prop) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("未配置 " + prop + "，请在 application.yml 或环境变量中填入真实 KMS keyId");
        }
    }
}
