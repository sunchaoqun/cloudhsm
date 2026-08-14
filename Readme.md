# CloudHSM 省钱 / 一键恢复 / 多集群切换

一组脚本，用于 CloudHSM 集群的**省钱停用**（删 HSM 保留 Cluster）与**一键恢复**（加回 HSM），以及多集群切换。

> 集群 ID、区域等请改成你自己的（见 `scripts/config.sh` 的 `CLUSTERS`，或用环境变量覆盖）。本文用占位别名 `c1` / `c2` 示例。

## 省钱思路

CloudHSM 的成本几乎全在 **HSM 实例**（按小时计费）。只要**保留 Cluster、删掉 HSM 实例**：

- 空 Cluster **不计费**
- 自动备份存储费**极低**（几乎可忽略）
- 删除最后一个 HSM 时 AWS 会自动生成**最终备份**，用户和密钥不丢
- 需要用时重新加回 HSM，会**自动从最近备份恢复**：所有用户及密钥都回来，**无需重新 activate、无需重新建用户**

> ⚠️ 只要不删除 Cluster，备份就一直保留。若把 Cluster 也删了，备份默认只留 7 天（7–90 天可配），过期即无法恢复。

## 脚本

脚本都放在 `scripts/` 子目录。以下命令均从 `cloudhsm/` 根目录执行。信任锚 `customerCA.crt`（及其私钥）不随仓库提交，请放在本目录根或用 `CLOUDHSM_CUSTOMER_CA` 指定。

| 脚本 | 作用 |
|------|------|
| `scripts/status.sh` | 查看集群/HSM/备份状态与计费提示（只读，随时可跑） |
| `scripts/use-cluster.sh` | **切换** cloudhsm-cli 当前连接的 cluster |
| `scripts/backup.sh` | **省钱**：删除指定集群内所有 HSM，Cluster 空置，停止计费 |
| `scripts/restore.sh` | **一键恢复**：加回一个 HSM，等 ACTIVE，自动配好 CLI 指向该集群 |
| `scripts/config.sh`  | 公共配置（cluster 注册表、区域、路径、工具函数），被其余脚本 source |

### 多集群切换

`cloudhsm-cli` 全局只有一份配置，`interactive` 连的就是配置里的 HSM IP。切换 cluster = 把配置指向目标集群当前的 HSM IP。

```bash
./scripts/use-cluster.sh            # 看当前连的是哪个 + 列别名
./scripts/use-cluster.sh c1         # 切到别名 c1 对应的 cluster
/opt/cloudhsm/bin/cloudhsm-cli interactive
```

### 用法

所有需要选集群的脚本，第一个参数就是别名；不传则用默认（`config.sh` 里 `DEFAULT_ALIAS`）。

```bash
# 查看状态：不带参数列出全部；带别名看单个
./scripts/status.sh
./scripts/status.sh c1

# 不用了，省钱（删 HSM，保留 Cluster）
./scripts/backup.sh c1          # 交互确认；加 -y 跳过：./scripts/backup.sh c1 -y

# 要用了，一键恢复
./scripts/restore.sh c1         # 若需写 /opt/cloudhsm/etc，用 sudo -E ./scripts/restore.sh c1
```

### 可选环境变量

```bash
export CLOUDHSM_CLUSTER_1=cluster-xxxxxxxxxxx  # 你的真实 cluster ID（也可直接改 config.sh）
export CLOUDHSM_DEFAULT_ALIAS=c1               # 不传别名时默认操作哪个 cluster
export CLOUDHSM_REGION=ap-southeast-1          # 区域
export CLOUDHSM_AZ=ap-southeast-1a             # 恢复时 HSM 落在哪个 AZ（默认取集群子网首个 AZ）
export CLOUDHSM_ADMIN_PASSWORD=******          # 提供后 restore.sh 会自动 login 验证恢复结果
export CLOUDHSM_CUSTOMER_CA=/path/customerCA.crt   # 信任锚位置（默认取本目录根）
```

## 初始化记录（首次创建集群时执行过，仅存档）

```bash
# 生成客户 CA（私钥务必自行保管，勿提交仓库）
openssl req -x509 -newkey rsa:2048 -keyout customerCA.key -out customerCA.crt -days 3652 -nodes

# 用 CA 签发集群证书（<CLUSTER_ID> 换成你的）
openssl x509 -req -in <CLUSTER_ID>_ClusterCsr.csr -CA customerCA.crt -CAkey customerCA.key \
  -CAcreateserial -out cluster.crt -days 3652
```

```text
sudo /opt/cloudhsm/bin/configure-cli -a <HSM_IP>
/opt/cloudhsm/bin/cloudhsm-cli interactive
aws-cloudhsm > cluster activate            # 设置 admin 密码
aws-cloudhsm > login --username admin --role admin
aws-cloudhsm > user create --username <crypto_user> --role crypto-user
aws-cloudhsm > user list
```
