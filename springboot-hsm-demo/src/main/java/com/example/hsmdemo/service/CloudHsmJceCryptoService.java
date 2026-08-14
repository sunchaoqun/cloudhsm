package com.example.hsmdemo.service;

import com.example.hsmdemo.config.HsmProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.security.*;
import java.security.cert.Certificate;

/**
 * 方式二：CloudHSM JCE Provider。
 *
 * <p>依赖 cloudhsm-jce 包（安装到 /opt/cloudhsm/java/），本类通过反射按类名
 * {@code com.amazonaws.cloudhsm.jce.provider.CloudHsmProvider} 加载 provider，
 * 从而【无需】编译期依赖那颗不在 Maven 中央仓库的 jar；只要运行时该 jar 在
 * classpath 上即可（启动命令见 README）。
 *
 * <p>登录：CloudHSM JCE 支持用系统属性/环境变量或登录管理器登录。这里采用最常见的
 * 显式登录方式：设置 {@code HSM_USER}/{@code HSM_PASSWORD} 环境变量或通过
 * {@code CloudHsmProvider} 的登录 API。为保持 demo 简洁并与官方示例一致，
 * 我们用 KeyStore("CloudHSM") 加载已存在于 HSM 中的密钥（按 label 查找）。
 *
 * <p>密钥前提：HSM 中已存在标签为 {@code hsm.jce.sign-key-label} 的非对称密钥、
 * {@code hsm.jce.aes-key-label} 的 AES 密钥（可用 cloudhsm-cli / KeyStoreExplorer 预先生成）。
 */
@Service
@Profile("jce")
public class CloudHsmJceCryptoService implements CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CloudHsmJceCryptoService.class);
    private static final String PROVIDER_CLASS = "com.amazonaws.cloudhsm.jce.provider.CloudHsmProvider";
    private static final String KEYSTORE_TYPE = "CloudHSM";

    private final HsmProperties.Jce cfg;
    private Provider provider;
    private KeyStore keyStore;

    public CloudHsmJceCryptoService(HsmProperties props) {
        this.cfg = props.getJce();
    }

    @PostConstruct
    void init() throws Exception {
        // HSM 登录凭据：CloudHSM JCE 约定通过环境变量或系统属性提供
        if (cfg.getUser() != null) {
            System.setProperty("HSM_USER", cfg.getUser());
        }
        if (cfg.getPassword() != null) {
            System.setProperty("HSM_PASSWORD", cfg.getPassword());
        }

        // 反射加载 CloudHsmProvider（无编译期依赖）
        Class<?> clazz = Class.forName(PROVIDER_CLASS);
        this.provider = (Provider) clazz.getDeclaredConstructor().newInstance();
        if (Security.getProvider(provider.getName()) == null) {
            Security.addProvider(provider);
        }
        log.info("已加载 CloudHSM JCE provider: {}", provider.getName());

        this.keyStore = KeyStore.getInstance(KEYSTORE_TYPE, provider);
        keyStore.load(null, null);
    }

    @Override
    public String backend() {
        return "CloudHSM JCE (" + (provider != null ? provider.getName() : "not-loaded") + ")";
    }

    @Override
    public java.util.Set<String> capabilities() {
        return java.util.Set.of(CAP_SIGN, CAP_VERIFY, CAP_ENCRYPT, CAP_DECRYPT, CAP_ENCRYPT_ASYM);
    }

    @Override
    public byte[] sign(byte[] plaintext) {
        try {
            PrivateKey key = (PrivateKey) keyStore.getKey(cfg.getSignKeyLabel(), null);
            requireKey(key, cfg.getSignKeyLabel());
            Signature sig = Signature.getInstance(cfg.getSignatureAlgorithm(), provider);
            sig.initSign(key);
            sig.update(plaintext);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JCE 签名失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean verify(byte[] plaintext, byte[] signature) {
        try {
            Certificate cert = keyStore.getCertificate(cfg.getSignKeyLabel());
            PublicKey pub = (cert != null)
                    ? cert.getPublicKey()
                    : (PublicKey) keyStore.getKey(cfg.getSignKeyLabel() + ".pub", null);
            if (pub == null) {
                throw new IllegalStateException("找不到用于验签的公钥（label=" + cfg.getSignKeyLabel() + "）");
            }
            Signature sig = Signature.getInstance(cfg.getSignatureAlgorithm(), provider);
            sig.initVerify(pub);
            sig.update(plaintext);
            return sig.verify(signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JCE 验签失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] encrypt(byte[] plaintext) {
        try {
            Key aes = keyStore.getKey(cfg.getAesKeyLabel(), null);
            requireKey(aes, cfg.getAesKeyLabel());
            return AesGcmCodec.encrypt(provider, aes, plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JCE 加密失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] decrypt(byte[] ciphertext) {
        try {
            Key aes = keyStore.getKey(cfg.getAesKeyLabel(), null);
            requireKey(aes, cfg.getAesKeyLabel());
            return AesGcmCodec.decrypt(provider, aes, ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JCE 解密失败: " + e.getMessage(), e);
        }
    }

    // RSA 非对称加解密：公钥加密、私钥解密。用 OAEP(SHA-256) padding，比 PKCS1 安全。
    // 复用签名 key 对（demo-sign-key / demo-sign-key.pub），需其 encrypt/decrypt 属性为 true。
    // 注意 RSA 只能加密比密钥短的数据（RSA-2048 + OAEP 约 ≤190 字节）。
    private static final String RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";

    @Override
    public byte[] encryptAsym(byte[] plaintext) {
        try {
            Certificate cert = keyStore.getCertificate(cfg.getSignKeyLabel());
            PublicKey pub = (cert != null)
                    ? cert.getPublicKey()
                    : (PublicKey) keyStore.getKey(cfg.getSignKeyLabel() + ".pub", null);
            if (pub == null) {
                throw new IllegalStateException("找不到用于加密的公钥（label=" + cfg.getSignKeyLabel() + "）");
            }
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(RSA_TRANSFORMATION, provider);
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, pub);
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JCE 非对称加密失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] decryptAsym(byte[] ciphertext) {
        try {
            PrivateKey priv = (PrivateKey) keyStore.getKey(cfg.getSignKeyLabel(), null);
            requireKey(priv, cfg.getSignKeyLabel());
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(RSA_TRANSFORMATION, provider);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, priv);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JCE 非对称解密失败: " + e.getMessage(), e);
        }
    }

    private static void requireKey(Key key, String label) {
        if (key == null) {
            throw new IllegalStateException("HSM 中找不到 label=" + label + " 的密钥，请先在 HSM 里创建");
        }
    }
}
