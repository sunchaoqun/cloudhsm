package com.example.hsmdemo.service;

/**
 * 三种后端（KMS / CloudHSM JCE / PKCS#11）统一实现的密码操作接口。
 * Controller 只依赖本接口，具体实现由激活的 Spring profile 决定。
 *
 * <p>所有二进制入参/出参统一用 Base64 字符串在接口层传递，便于 REST 调试。
 */
public interface CryptoService {

    /** 能力标识常量。 */
    String CAP_SIGN = "sign";
    String CAP_VERIFY = "verify";
    String CAP_ENCRYPT = "encrypt";
    String CAP_DECRYPT = "decrypt";
    /** 非对称（RSA）加解密能力。 */
    String CAP_ENCRYPT_ASYM = "encryptAsym";

    /** 返回当前实现使用的后端名，用于 /api/info 展示。 */
    String backend();

    /**
     * 当前后端支持的操作集合。前端据此动态显示/隐藏按钮。
     * 默认返回全部四种；不支持某操作的实现（如 KMS Custom Key Store 不能签名）应覆写。
     */
    default java.util.Set<String> capabilities() {
        return java.util.Set.of(CAP_SIGN, CAP_VERIFY, CAP_ENCRYPT, CAP_DECRYPT);
    }

    /**
     * 当前调用身份（用于页面显示是哪个 AWS account 在调用，跨账号演示时尤其有用）。
     * 只有 KMS 类后端有意义；默认返回 null 表示不适用（如 jce/pkcs11 直连 HSM，无 AWS account 概念）。
     *
     * @return 例如 {@code {account: "123456789012", arn: "...:assumed-role/.../crypto_user"}}，或 null
     */
    default java.util.Map<String, String> identity() {
        return null;
    }

    /**
     * 对明文做签名。
     *
     * @param plaintext 原始明文字节
     * @return 签名字节
     */
    byte[] sign(byte[] plaintext);

    /**
     * 验签。
     *
     * @param plaintext 原始明文字节
     * @param signature 待校验的签名字节
     * @return 是否验签通过
     */
    boolean verify(byte[] plaintext, byte[] signature);

    /**
     * 加密。
     *
     * @param plaintext 明文字节
     * @return 密文字节（对 KMS 是 CiphertextBlob；对 HSM 是算法输出，可能自带 IV，见实现说明）
     */
    byte[] encrypt(byte[] plaintext);

    /**
     * 解密。
     *
     * @param ciphertext 密文字节
     * @return 明文字节
     */
    byte[] decrypt(byte[] ciphertext);

    /**
     * 非对称（RSA）加密：公钥加密。
     * 默认不支持——只有实现了 RSA 加解密的后端（如 JCE）才覆写。
     * 注意 RSA 只能加密比密钥短的数据（RSA-2048 + OAEP 约 ≤190 字节）。
     */
    default byte[] encryptAsym(byte[] plaintext) {
        throw new UnsupportedOperationException("当前后端不支持非对称加密");
    }

    /**
     * 非对称（RSA）解密：私钥解密。默认不支持。
     */
    default byte[] decryptAsym(byte[] ciphertext) {
        throw new UnsupportedOperationException("当前后端不支持非对称解密");
    }
}
