package com.example.hsmdemo.tools;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * 一次性工具：用 CloudHSM JCE Provider 给 HSM 里【已存在但没有证书】的 RSA 私钥补一张自签证书，
 * 使其在 SunPKCS11 的 KeyStore 里可见（SunPKCS11 只索引"有配套证书"的私钥）。
 *
 * <p>为什么用 JCE 而不是 PKCS#11：CloudHSM JCE Provider 能按 label 取到无证书的已有私钥，
 * 而 SunPKCS11 取不到（循环依赖）。补完证书后，JCE 与 SunPKCS11 都能用同一个 label。
 *
 * <p>私钥全程不出 HSM（用 HSM 私钥在 HSM 内签证书）。运行一次即可，证书持久保存在 HSM。
 *
 * 运行方式（需要 JCE jar 在 classpath，且设置 HSM 登录环境变量）：
 * <pre>
 *   export HSM_USER=crypto_user
 *   export HSM_PASSWORD='...'
 *   CP="target/springboot-hsm-demo-0.0.1-SNAPSHOT.jar:/opt/cloudhsm/java/*"
 *   java -cp "$CP" com.example.hsmdemo.tools.AttachCertToKey demo-sign-key
 * </pre>
 * 参数：argv[0] = 私钥 label（默认 demo-sign-key）。
 */
public final class AttachCertToKey {

    private static final String PROVIDER_CLASS = "com.amazonaws.cloudhsm.jce.provider.CloudHsmProvider";
    private static final String KEYSTORE_TYPE = "CloudHSM";
    private static final String SIG_ALG = "SHA256withRSA";

    public static void main(String[] args) throws Exception {
        String label = args.length > 0 ? args[0] : "demo-sign-key";

        // HSM 登录凭据（与 demo 的 jce profile 一致，走环境变量）
        if (System.getenv("HSM_USER") != null) {
            System.setProperty("HSM_USER", System.getenv("HSM_USER"));
        }
        if (System.getenv("HSM_PASSWORD") != null) {
            System.setProperty("HSM_PASSWORD", System.getenv("HSM_PASSWORD"));
        }

        // 反射加载 CloudHSM JCE provider（避免编译期依赖那颗非 Maven 中央仓库 jar）
        Provider hsm = (Provider) Class.forName(PROVIDER_CLASS).getDeclaredConstructor().newInstance();
        if (Security.getProvider(hsm.getName()) == null) {
            Security.addProvider(hsm);
        }
        System.out.println("[info] 已加载 CloudHSM JCE provider: " + hsm.getName());

        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE, hsm);
        ks.load(null, null);

        // 若已有证书则跳过（幂等）
        Certificate existing = ks.getCertificate(label);
        if (existing != null) {
            System.out.println("[skip] label=" + label + " 已经有证书，无需补。");
            return;
        }

        PrivateKey priv = (PrivateKey) ks.getKey(label, null);
        if (priv == null) {
            throw new IllegalStateException("HSM 里找不到私钥 label=" + label + "（JCE 也取不到，请确认 label）");
        }
        // 公钥：优先取 <label>.pub，其次从证书（此处无证书，故取 .pub）
        PublicKey pub = (PublicKey) ks.getKey(label + ".pub", null);
        if (pub == null) {
            Certificate pubCert = ks.getCertificate(label + ".pub");
            if (pubCert != null) {
                pub = pubCert.getPublicKey();
            }
        }
        if (pub == null) {
            throw new IllegalStateException("找不到公钥 label=" + label + ".pub，无法造证书");
        }

        System.out.println("[info] 取到私钥+公钥，开始用 HSM 私钥自签证书 ...");
        X509Certificate cert = selfSign(priv, pub, hsm, label);

        // 存回 HSM：alias 必须等于私钥 label
        ks.setKeyEntry(label, priv, null, new Certificate[]{cert});
        System.out.println("[ok] 已给 label=" + label + " 关联自签证书并写回 HSM。");
        System.out.println("[ok] 现在 SunPKCS11 也能看到该私钥了，可用 pkcs11 profile 测签名。");
    }

    private static X509Certificate selfSign(PrivateKey priv, PublicKey pub, Provider hsm, String cn) throws Exception {
        X500Name dn = new X500Name("CN=" + cn + ", O=hsm-demo");
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 86400_000L);
        Date notAfter = new Date(now + 3650L * 86400_000L);

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                dn, BigInteger.valueOf(now), notBefore, notAfter, dn, pub);

        // 关键：用 HSM 里的私钥签名（signer provider 指向 CloudHSM），私钥不出 HSM
        ContentSigner signer = new JcaContentSignerBuilder(SIG_ALG)
                .setProvider(hsm)
                .build(priv);

        return new JcaX509CertificateConverter()
                .getCertificate(builder.build(signer));
    }
}
