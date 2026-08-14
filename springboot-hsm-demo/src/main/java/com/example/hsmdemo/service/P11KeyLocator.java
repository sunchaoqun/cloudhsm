package com.example.hsmdemo.service;

import sun.security.pkcs11.wrapper.CK_ATTRIBUTE;
import sun.security.pkcs11.wrapper.PKCS11;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;

/**
 * 绕开 SunPKCS11 的 KeyStore（它要求私钥有配套证书），
 * 通过反射直接用底层 PKCS#11 API 按 CKA_LABEL 查 key handle，再包装成标准 JCA 密钥。
 *
 * <p>为什么需要它：CloudHSM 不在 HSM 上存证书，SunPKCS11 的 KeyStore 因此看不到 RSA 私钥。
 * 但底层 PKCS#11（C_FindObjects 按 label + C_Sign 等）不需要证书就能用私钥
 * （已由 pkcs11-tool --sign 验证）。本类把这套底层能力暴露为 JCA PrivateKey/PublicKey，
 * 之后用标准 Signature/Cipher（provider 指向 SunPKCS11）即可完成签名/验签/RSA 加解密。
 *
 * <p>依赖 JDK 内部 API（sun.security.pkcs11.*），运行时需要 --add-exports（见 README）。
 * 参考实现：github.com/aobao32/cloudhsm-java-pkcs11
 */
final class P11KeyLocator {

    // PKCS#11 常量（值来自 sun.security.pkcs11.wrapper.PKCS11Constants，此处内联避免静态导入跨接口问题）
    private static final long CKA_CLASS = 0L;
    private static final long CKA_TOKEN = 1L;
    private static final long CKA_LABEL = 3L;
    private static final long CKA_MODULUS = 288L;
    private static final long CKA_PUBLIC_EXPONENT = 290L;
    private static final long CKO_PUBLIC_KEY = 2L;
    private static final long CKO_PRIVATE_KEY = 3L;

    private final PKCS11 p11;
    private final long sessionId;
    private final Object sessionObj;

    private P11KeyLocator(PKCS11 p11, long sessionId, Object sessionObj) {
        this.p11 = p11;
        this.sessionId = sessionId;
        this.sessionObj = sessionObj;
    }

    /** 从已初始化并登录的 SunPKCS11 provider 反射取出底层 PKCS11 与 session。 */
    static P11KeyLocator from(Provider sunPkcs11Provider) throws Exception {
        Field tokenField = sunPkcs11Provider.getClass().getDeclaredField("token");
        tokenField.setAccessible(true);
        Object token = tokenField.get(sunPkcs11Provider);

        Field p11Field = token.getClass().getDeclaredField("p11");
        p11Field.setAccessible(true);
        PKCS11 p11 = (PKCS11) p11Field.get(token);

        Method getObjSession = token.getClass().getDeclaredMethod("getObjSession");
        getObjSession.setAccessible(true);
        Object sessionObj = getObjSession.invoke(token);

        Method idMethod = sessionObj.getClass().getDeclaredMethod("id");
        idMethod.setAccessible(true);
        long sessionId = (Long) idMethod.invoke(sessionObj);

        return new P11KeyLocator(p11, sessionId, sessionObj);
    }

    /** 按 label + class 查第一个匹配对象的 handle，找不到返回 -1。 */
    private long findHandle(long keyClass, String label) throws Exception {
        CK_ATTRIBUTE[] template = {
                new CK_ATTRIBUTE(CKA_TOKEN, true),
                new CK_ATTRIBUTE(CKA_CLASS, keyClass),
                new CK_ATTRIBUTE(CKA_LABEL, label.getBytes("UTF-8")),
        };
        p11.C_FindObjectsInit(sessionId, template);
        long[] found = p11.C_FindObjects(sessionId, 1);
        p11.C_FindObjectsFinal(sessionId);
        return found.length > 0 ? found[0] : -1;
    }

    /** 按 label 取 RSA 私钥（包装成 JCA PrivateKey），找不到返回 null。 */
    PrivateKey findRsaPrivateKey(String label, int keyBits) throws Exception {
        long handle = findHandle(CKO_PRIVATE_KEY, label);
        if (handle < 0) {
            return null;
        }
        CK_ATTRIBUTE[] attrs = {new CK_ATTRIBUTE(CKA_MODULUS), new CK_ATTRIBUTE(CKA_PUBLIC_EXPONENT)};
        p11.C_GetAttributeValue(sessionId, handle, attrs);
        return (PrivateKey) invokeP11KeyFactory("privateKey", handle, keyBits, attrs);
    }

    /** 按 label 取 RSA 公钥（包装成 JCA PublicKey），找不到返回 null。 */
    PublicKey findRsaPublicKey(String label, int keyBits) throws Exception {
        long handle = findHandle(CKO_PUBLIC_KEY, label);
        if (handle < 0) {
            return null;
        }
        CK_ATTRIBUTE[] attrs = {new CK_ATTRIBUTE(CKA_MODULUS), new CK_ATTRIBUTE(CKA_PUBLIC_EXPONENT)};
        p11.C_GetAttributeValue(sessionId, handle, attrs);
        return (PublicKey) invokeP11KeyFactory("publicKey", handle, keyBits, attrs);
    }

    /** 反射调用 sun.security.pkcs11.P11Key.publicKey/privateKey 工厂方法。 */
    private Object invokeP11KeyFactory(String factory, long handle, int keyBits, CK_ATTRIBUTE[] attrs)
            throws Exception {
        Class<?> p11KeyClass = Class.forName("sun.security.pkcs11.P11Key");
        Class<?> sessionClass = Class.forName("sun.security.pkcs11.Session");
        Method m = p11KeyClass.getDeclaredMethod(
                factory, sessionClass, long.class, String.class, int.class, CK_ATTRIBUTE[].class);
        m.setAccessible(true);
        return m.invoke(null, sessionObj, handle, "RSA", keyBits, attrs);
    }
}
