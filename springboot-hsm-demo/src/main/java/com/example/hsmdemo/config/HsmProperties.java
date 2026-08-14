package com.example.hsmdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 三种后端的可配置项。用 {@code hsm.*} 前缀，见 application.yml。
 * 真实值（KMS keyId、HSM 用户名/密码、PKCS#11 库路径等）由你后续填入或用环境变量覆盖。
 */
@ConfigurationProperties(prefix = "hsm")
public class HsmProperties {

    private final Kms kms = new Kms();
    private final Jce jce = new Jce();
    private final Pkcs11 pkcs11 = new Pkcs11();

    public Kms getKms() { return kms; }
    public Jce getJce() { return jce; }
    public Pkcs11 getPkcs11() { return pkcs11; }

    /** AWS KMS 方式。 */
    public static class Kms {
        /** 区域，如 ap-southeast-1。 */
        private String region = "ap-southeast-1";
        /**
         * 非对称签名 key（RSA/EC），用于 sign/verify。
         * ⚠️ 必须是 KMS 普通非对称 key；不能是 CloudHSM Custom Key Store 的 key
         * （该 key store 只支持对称密钥，无法签名）。要用 HSM 硬件签名请走 jce/pkcs11。
         */
        private String signKeyId;
        /** 签名算法，如 RSASSA_PSS_SHA_256 / ECDSA_SHA_256。 */
        private String signingAlgorithm = "RSASSA_PSS_SHA_256";
        /**
         * 对称 key（SYMMETRIC_DEFAULT），用于 encrypt/decrypt。
         * ✅ 可指向 CloudHSM Custom Key Store 的对称 key，加解密在 HSM 集群内完成。
         */
        private String encryptKeyId;

        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getSignKeyId() { return signKeyId; }
        public void setSignKeyId(String signKeyId) { this.signKeyId = signKeyId; }
        public String getSigningAlgorithm() { return signingAlgorithm; }
        public void setSigningAlgorithm(String a) { this.signingAlgorithm = a; }
        public String getEncryptKeyId() { return encryptKeyId; }
        public void setEncryptKeyId(String encryptKeyId) { this.encryptKeyId = encryptKeyId; }
    }

    /** CloudHSM JCE 方式。 */
    public static class Jce {
        /** crypto-user 用户名，如 crypto_user。 */
        private String user;
        /** crypto-user 密码。 */
        private String password;
        /** HSM 里用于签名/验签的密钥标签（label）。 */
        private String signKeyLabel = "demo-sign-key";
        /** HSM 里用于加解密的 AES 密钥标签。 */
        private String aesKeyLabel = "demo-aes-key";
        /** 签名算法，如 SHA256withRSA。 */
        private String signatureAlgorithm = "SHA256withRSA";

        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getSignKeyLabel() { return signKeyLabel; }
        public void setSignKeyLabel(String l) { this.signKeyLabel = l; }
        public String getAesKeyLabel() { return aesKeyLabel; }
        public void setAesKeyLabel(String l) { this.aesKeyLabel = l; }
        public String getSignatureAlgorithm() { return signatureAlgorithm; }
        public void setSignatureAlgorithm(String a) { this.signatureAlgorithm = a; }
    }

    /** PKCS#11 方式。 */
    public static class Pkcs11 {
        /** CloudHSM PKCS#11 库路径，装 cloudhsm-pkcs11 后通常为此。 */
        private String library = "/opt/cloudhsm/lib/libcloudhsm_pkcs11.so";
        /** slot 索引（slotListIndex，第几个 slot，从 0）。CloudHSM 通常取第一个即 0。 */
        private int slot = 0;
        /** 登录 PIN，格式为 user:password（CloudHSM 约定）。 */
        private String pin;
        /** 签名密钥别名（PKCS#11 label）。 */
        private String signKeyLabel = "demo-sign-key";
        /** AES 密钥别名。 */
        private String aesKeyLabel = "demo-aes-key";
        /** 签名算法。 */
        private String signatureAlgorithm = "SHA256withRSA";

        public String getLibrary() { return library; }
        public void setLibrary(String library) { this.library = library; }
        public int getSlot() { return slot; }
        public void setSlot(int slot) { this.slot = slot; }
        public String getPin() { return pin; }
        public void setPin(String pin) { this.pin = pin; }
        public String getSignKeyLabel() { return signKeyLabel; }
        public void setSignKeyLabel(String l) { this.signKeyLabel = l; }
        public String getAesKeyLabel() { return aesKeyLabel; }
        public void setAesKeyLabel(String l) { this.aesKeyLabel = l; }
        public String getSignatureAlgorithm() { return signatureAlgorithm; }
        public void setSignatureAlgorithm(String a) { this.signatureAlgorithm = a; }
    }
}
