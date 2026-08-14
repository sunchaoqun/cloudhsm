# Spring Boot HSM Demo — KMS / CloudHSM JCE / PKCS#11

一个工程用三种方式做**签名/验签**和**加密/解密**，通过 Spring Profile 切换后端：

| Profile | 后端 | 依赖 | 支持操作 | 现状 |
|---------|------|------|---------|------|
| `kms` | 通用 AWS KMS | 纯 Java（AWS SDK v2） | 签名+加解密 | 可直接跑，填 keyId 即可 |
| `kms-cks` | KMS Custom Key Store（CloudHSM） | 纯 Java（AWS SDK v2） | **仅加解密**（不支持签名） | 可直接跑，填对称 keyId |
| `jce` | CloudHSM JCE Provider | `cloudhsm-jce` 包 → `/opt/cloudhsm/java/` | 签名+加解密 | 需先装组件包 |
| `pkcs11` | JDK SunPKCS11 + CloudHSM `.so` | `cloudhsm-pkcs11` 包 → `/opt/cloudhsm/lib/` | 签名+加解密 | 需先装组件包 |

> 前端会根据 `/api/info` 返回的 capabilities 动态显示按钮：`kms-cks` 下签名区自动隐藏。

> 工程用 Java 21 + Maven Wrapper（`./mvnw`），你机器上无需单独装 mvn。

## ⚠️ 能力边界（务必先看）：谁能签名，谁只能加解密

AWS 官方明确：**KMS Custom Key Store（含 CloudHSM key store）只支持对称加密密钥**，
**不支持非对称密钥，因此不能做签名/验签**（也不支持 HMAC、导入密钥、自动轮换、多区域）。
参考：<https://docs.aws.amazon.com/kms/latest/developerguide/create-cmk-keystore.html>

| 操作 | KMS 普通 key | KMS **Custom Key Store**（CloudHSM） | `jce` / `pkcs11` 直连 HSM |
|------|:---:|:---:|:---:|
| 加密/解密（对称） | ✅ | ✅（运算在 HSM 集群内） | ✅ |
| **签名/验签（非对称）** | ✅（KMS 托管的非对称 key） | ❌ **不支持** | ✅ |

结论：
- **想用 CloudHSM 硬件做签名** → 只能走 `jce` 或 `pkcs11` profile，**KMS 这条路走不通**。
- **KMS profile 里**：`sign-key-id` 只能填 KMS 普通非对称 key；`encrypt-key-id` 可以指向
  CloudHSM Custom Key Store 的对称 key。

## 统一 REST 接口

Controller 只依赖统一接口 `CryptoService`，走哪种后端由激活的 profile 决定，接口不变：

| 方法 | 路径 | 入参 (JSON) |
|------|------|-------------|
| GET  | `/api/info` | 无，返回当前后端 |
| POST | `/api/sign` | `{"dataBase64":"..."}` |
| POST | `/api/verify` | `{"dataBase64":"...","signatureBase64":"..."}` |
| POST | `/api/encrypt` | `{"dataBase64":"..."}` |
| POST | `/api/decrypt` | `{"dataBase64":"..."}`（密文的 Base64） |

所有二进制都用 Base64 传递。

## 构建 & 运行

```bash
cd springboot-hsm-demo
./mvnw -DskipTests package
```

不指定 profile 启动会走**兜底占位**（仅提示，不崩）：

```bash
java -jar target/springboot-hsm-demo-0.0.1-SNAPSHOT.jar
# /api/info -> backend: NONE ...
```

### 方式一：KMS（现在就能真跑）

凭据走 AWS 默认链（你机器上已配好 `~/.aws` / 环境变量）。填入真实 keyId：

```bash
export KMS_SIGN_KEY_ID=arn:aws:kms:ap-southeast-1:1234:key/xxxx      # KMS 普通非对称 key (RSA/EC)，非 Custom Key Store
export KMS_ENCRYPT_KEY_ID=arn:aws:kms:ap-southeast-1:1234:key/yyyy   # 对称 key，可指向 CloudHSM Custom Key Store
export AWS_REGION=ap-southeast-1

java -jar target/springboot-hsm-demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=kms
```

> KMS 的 Custom Key Store 正在连接中：连好后把**对称** key 建在该 Key Store 里，
> `KMS_ENCRYPT_KEY_ID` 指向它即可（加解密在 HSM 集群内完成），代码无需改动。
>
> ⚠️ 但**签名**用不了 Custom Key Store：`KMS_SIGN_KEY_ID` 只能是 KMS 普通非对称 key。
> 若要用 CloudHSM 硬件做签名，请改用 `jce` / `pkcs11` profile。

冒烟示例：

```bash
curl -s localhost:8080/api/info
# 签名
SIG=$(curl -s -X POST localhost:8080/api/sign -H 'Content-Type: application/json' \
  -d '{"dataBase64":"aGVsbG8="}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["resultBase64"])')
# 验签
curl -s -X POST localhost:8080/api/verify -H 'Content-Type: application/json' \
  -d "{\"dataBase64\":\"aGVsbG8=\",\"signatureBase64\":\"$SIG\"}"
```

### 方式二：CloudHSM JCE

1. 安装组件包（Ubuntu，版本按你区域的下载地址为准）：

```bash
# 示例，实际下载链接见 AWS CloudHSM 文档
sudo dpkg -i cloudhsm-jce_latest_amd64.deb
# 装好后 /opt/cloudhsm/java/ 下会有 cloudhsm-jce-*.jar
```

2. 确保 `/opt/cloudhsm/etc/customerCA.crt` 与 `cloudhsm-cli` 指向目标 HSM（用上层 `../use-cluster.sh`）。

3. HSM 里预先建好密钥（label 要和配置一致），例如用 `cloudhsm-cli` 或 KeyStoreExplorer 生成
   `demo-sign-key`（RSA）和 `demo-aes-key`（AES）。

4. 启动（把 JCE jar 加到 classpath；provider 由代码反射加载，故无编译期依赖）：

```bash
export HSM_USER=crypto_user
export HSM_PASSWORD='******'
CP="target/springboot-hsm-demo-0.0.1-SNAPSHOT.jar:/opt/cloudhsm/java/*"
java -cp "$CP" -Dloader.main=com.example.hsmdemo.HsmDemoApplication \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --spring.profiles.active=jce
```

### 方式三：PKCS#11

1. 安装组件包：

```bash
sudo dpkg -i cloudhsm-pkcs11_latest_amd64.deb
# 装好后有 /opt/cloudhsm/lib/libcloudhsm_pkcs11.so
```

2. 同样确保信任锚与 HSM 指向配好，HSM 里有对应 label 的密钥。

3. 启动（SunPKCS11 是 JDK 自带，无需额外 classpath）：

```bash
export PKCS11_PIN='crypto_user:******'     # 格式 crypto-user:password
java -jar target/springboot-hsm-demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=pkcs11
```

## 配置项一览

见 `src/main/resources/application.yml`，所有敏感值都支持环境变量覆盖：

| 环境变量 | 说明 |
|----------|------|
| `KMS_SIGN_KEY_ID` / `KMS_ENCRYPT_KEY_ID` | KMS 签名(普通非对称 key) / 加密(对称，可为 Custom Key Store) |
| `KMS_SIGNING_ALGORITHM` | 默认 `RSASSA_PSS_SHA_256` |
| `HSM_USER` / `HSM_PASSWORD` | JCE 登录 crypto-user |
| `PKCS11_LIBRARY` / `PKCS11_PIN` / `PKCS11_SLOT` | PKCS#11 库、PIN(`user:pass`)、slot |
| `*_SIGN_KEY_LABEL` / `*_AES_KEY_LABEL` | HSM 内密钥 label |

## 加解密说明

- **KMS**：Encrypt 返回的 CiphertextBlob 直接用于 Decrypt。
- **JCE / PKCS#11**：用 AES-GCM，输出为 `IV(12B) || 密文+Tag`，Decrypt 侧按此约定还原。

## 待你提供的真实值

- KMS：`KMS_SIGN_KEY_ID`（KMS 普通非对称 key）、`KMS_ENCRYPT_KEY_ID`（对称 key，Custom Key Store 连好后可指向它）
- JCE / PKCS#11：crypto-user 密码，以及 HSM 里实际的密钥 label
