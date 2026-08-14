package com.example.hsmdemo.service;

import com.example.hsmdemo.config.HsmProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import java.security.*;
import java.util.Set;

/**
 * 方式三：PKCS#11（JDK 内置 SunPKCS11 + CloudHSM 的 libcloudhsm_pkcs11.so）。
 *
 * <p>密钥获取分两条路：
 * <ul>
 *   <li><b>AES 对称 key</b>：走标准 {@code KeyStore("PKCS11")}，对称 key 无需证书即可见。</li>
 *   <li><b>RSA 私钥/公钥</b>：CloudHSM 不在 HSM 存证书，导致 SunPKCS11 的 KeyStore 看不到 RSA 私钥。
 *       因此改用 {@link P11KeyLocator} 通过底层 PKCS#11 API 按 CKA_LABEL 直接取 handle 并包装成
 *       JCA 密钥（不依赖证书），再用标准 Signature/Cipher 完成签名/验签/RSA 加解密。</li>
 * </ul>
 *
 * <p>因用到 JDK 内部 API，运行需加 --add-exports（见 README 启动命令）。
 * PIN 格式为 {@code crypto-user:password}（CloudHSM 约定）。
 */
@Service
@Profile("pkcs11")
public class Pkcs11CryptoService implements CryptoService {

    private static final Logger log = LoggerFactory.getLogger(Pkcs11CryptoService.class);
    // CloudHSM PKCS#11 经 SunPKCS11 走 RSA 加解密：用 PKCS1 padding（CloudHSM 与 SunPKCS11 都稳定支持）。
    // 注：OAEPWithSHA-256AndMGF1Padding 这种写法是 CloudHSM JCE provider 认的，SunPKCS11 不认。
    private static final String RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding";
    private static final int RSA_KEY_BITS = 2048;

    private final HsmProperties.Pkcs11 cfg;
    private Provider provider;
    private KeyStore keyStore;
    private P11KeyLocator locator;

    public Pkcs11CryptoService(HsmProperties props) {
        this.cfg = props.getPkcs11();
    }

    @PostConstruct
    void init() throws Exception {
        String config = "--"
                + "\nname = CloudHSM"
                + "\nlibrary = " + cfg.getLibrary()
                + "\nslotListIndex = " + cfg.getSlot();

        Provider base = Security.getProvider("SunPKCS11");
        if (base == null) {
            throw new IllegalStateException("JDK 未提供 SunPKCS11 provider");
        }
        this.provider = base.configure(config);
        Security.addProvider(provider);
        log.info("已加载 PKCS#11 provider: {} (library={})", provider.getName(), cfg.getLibrary());

        char[] pin = cfg.getPin() != null ? cfg.getPin().toCharArray() : null;
        this.keyStore = KeyStore.getInstance("PKCS11", provider);
        keyStore.load(null, pin);

        // RSA key 走底层 label 定位（KeyStore 因缺证书看不到 RSA 私钥）
        this.locator = P11KeyLocator.from(provider);
        log.info("PKCS#11 已就绪：AES 走 KeyStore，RSA 走底层 label 定位");
    }

    @PreDestroy
    void shutdown() {
        if (provider != null) {
            Security.removeProvider(provider.getName());
        }
    }

    @Override
    public String backend() {
        return "PKCS#11 (" + (provider != null ? provider.getName() : "not-loaded") + ")";
    }

    @Override
    public Set<String> capabilities() {
        return Set.of(CAP_SIGN, CAP_VERIFY, CAP_ENCRYPT, CAP_DECRYPT, CAP_ENCRYPT_ASYM);
    }

    @Override
    public byte[] sign(byte[] plaintext) {
        try {
            PrivateKey key = locator.findRsaPrivateKey(cfg.getSignKeyLabel(), RSA_KEY_BITS);
            requireKey(key, cfg.getSignKeyLabel());
            Signature sig = Signature.getInstance(cfg.getSignatureAlgorithm(), provider);
            sig.initSign(key);
            sig.update(plaintext);
            return sig.sign();
        } catch (Exception e) {
            throw new IllegalStateException("PKCS#11 签名失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean verify(byte[] plaintext, byte[] signature) {
        try {
            PublicKey pub = locator.findRsaPublicKey(cfg.getSignKeyLabel() + ".pub", RSA_KEY_BITS);
            if (pub == null) {
                throw new IllegalStateException("找不到验签公钥（label=" + cfg.getSignKeyLabel() + ".pub）");
            }
            Signature sig = Signature.getInstance(cfg.getSignatureAlgorithm(), provider);
            sig.initVerify(pub);
            sig.update(plaintext);
            return sig.verify(signature);
        } catch (Exception e) {
            throw new IllegalStateException("PKCS#11 验签失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] encrypt(byte[] plaintext) {
        try {
            Key aes = keyStore.getKey(cfg.getAesKeyLabel(), null);
            requireKey(aes, cfg.getAesKeyLabel());
            return AesCbcCodec.encrypt(provider, aes, plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PKCS#11 加密失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] decrypt(byte[] ciphertext) {
        try {
            Key aes = keyStore.getKey(cfg.getAesKeyLabel(), null);
            requireKey(aes, cfg.getAesKeyLabel());
            return AesCbcCodec.decrypt(provider, aes, ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PKCS#11 解密失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] encryptAsym(byte[] plaintext) {
        try {
            PublicKey pub = locator.findRsaPublicKey(cfg.getSignKeyLabel() + ".pub", RSA_KEY_BITS);
            if (pub == null) {
                throw new IllegalStateException("找不到 RSA 公钥（label=" + cfg.getSignKeyLabel() + ".pub）");
            }
            Cipher cipher = Cipher.getInstance(RSA_TRANSFORMATION, provider);
            cipher.init(Cipher.ENCRYPT_MODE, pub);
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new IllegalStateException("PKCS#11 非对称加密失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] decryptAsym(byte[] ciphertext) {
        try {
            PrivateKey priv = locator.findRsaPrivateKey(cfg.getSignKeyLabel(), RSA_KEY_BITS);
            requireKey(priv, cfg.getSignKeyLabel());
            Cipher cipher = Cipher.getInstance(RSA_TRANSFORMATION, provider);
            cipher.init(Cipher.DECRYPT_MODE, priv);
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("PKCS#11 非对称解密失败: " + e.getMessage(), e);
        }
    }

    private static void requireKey(Key key, String label) {
        if (key == null) {
            throw new IllegalStateException("HSM 中找不到 label=" + label + " 的密钥，请先在 HSM 里创建");
        }
    }
}
