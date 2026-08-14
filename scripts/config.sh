#!/usr/bin/env bash
# CloudHSM 多集群公共配置与工具函数
# 被 restore.sh / backup.sh / status.sh / use-cluster.sh 通过 `source` 引用。

set -euo pipefail

# ---------- 集群注册表 ----------
# 别名 => clusterId。新增 cluster 在这里加一行即可。
# 提交到公开仓库时用占位示例；实际使用改成你自己的 cluster ID，或用环境变量覆盖。
declare -A CLUSTERS=(
  [c1]="${CLOUDHSM_CLUSTER_1:-cluster-xxxxxxxxxxx}"
  [c2]="${CLOUDHSM_CLUSTER_2:-cluster-yyyyyyyyyyy}"
)
# 默认操作哪个（不传参数时用）
DEFAULT_ALIAS="${CLOUDHSM_DEFAULT_ALIAS:-c1}"

REGION="${CLOUDHSM_REGION:-ap-southeast-1}"

# cloudhsm-cli 相关路径
CLOUDHSM_CLI="${CLOUDHSM_CLI:-/opt/cloudhsm/bin/cloudhsm-cli}"
CONFIGURE_CLI="${CONFIGURE_CLI:-/opt/cloudhsm/bin/configure-cli}"

# 本地信任锚（两个 cluster 用的是同一个 CA，通用）
# 脚本在 scripts/ 子目录，证书放在上一级 cloudhsm/ 根目录，故默认取 $SCRIPT_DIR/..
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CUSTOMER_CA="${CLOUDHSM_CUSTOMER_CA:-$PROJECT_DIR/customerCA.crt}"
CLOUDHSM_CA_DEST="/opt/cloudhsm/etc/customerCA.crt"

# 恢复出的 HSM 落在哪个可用区（默认取集群第一个子网所在 AZ）
PREFERRED_AZ="${CLOUDHSM_AZ:-}"

# admin 密码：仅 restore.sh 自动 login 验证时用得到（从备份恢复无需重新 activate）
ADMIN_PASSWORD="${CLOUDHSM_ADMIN_PASSWORD:-}"

# ---------- 工具函数 ----------
log()  { printf '\033[1;34m[%s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*"; }
ok()   { printf '\033[1;32m[ OK ]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[WARN]\033[0m %s\n' "$*"; }
err()  { printf '\033[1;31m[FAIL]\033[0m %s\n' "$*" >&2; }
die()  { err "$*"; exit 1; }

aws_hsm() {
  aws cloudhsmv2 --region "$REGION" "$@"
}

require_tools() {
  command -v aws >/dev/null 2>&1 || die "未找到 aws CLI"
  [[ -x "$CLOUDHSM_CLI" ]] || die "未找到 cloudhsm-cli: $CLOUDHSM_CLI"
  [[ -x "$CONFIGURE_CLI" ]] || die "未找到 configure-cli: $CONFIGURE_CLI"
}

# 列出所有已注册别名
list_aliases() {
  local a
  for a in "${!CLUSTERS[@]}"; do
    printf '  %-6s -> %s\n' "$a" "${CLUSTERS[$a]}"
  done
}

# 把 别名 或 clusterId 解析成 clusterId，写入全局 CLUSTER_ID
# 用法: resolve_cluster [别名或clusterId]，不传则用 DEFAULT_ALIAS
resolve_cluster() {
  local key="${1:-$DEFAULT_ALIAS}"
  if [[ -n "${CLUSTERS[$key]:-}" ]]; then
    CLUSTER_ID="${CLUSTERS[$key]}"
    CLUSTER_ALIAS="$key"
  elif [[ "$key" == cluster-* ]]; then
    CLUSTER_ID="$key"
    CLUSTER_ALIAS="$key"
  else
    err "未知的 cluster: '$key'。已注册的别名："
    list_aliases >&2
    exit 1
  fi
}

# 返回集群 JSON（describe-clusters 过滤 CLUSTER_ID）
describe_cluster() {
  aws_hsm describe-clusters \
    --filters clusterIds="$CLUSTER_ID" \
    --query 'Clusters[0]' --output json
}

cluster_state() {
  describe_cluster | python3 -c 'import sys,json;print(json.load(sys.stdin).get("State","UNKNOWN"))'
}

# 输出该 cluster 当前第一个 ACTIVE 且有 EniIp 的 HSM 的 IP（没有则输出空）
active_hsm_ip() {
  describe_cluster | python3 -c '
import sys, json
hsms = json.load(sys.stdin).get("Hsms", [])
for h in hsms:
    if h.get("State") == "ACTIVE" and h.get("EniIp"):
        print(h["EniIp"]); break
'
}

# 安装信任锚到 cloudhsm-cli 目录（同一个 CA，两个 cluster 通用）
install_trust_anchor() {
  [[ -f "$CUSTOMER_CA" ]] || die "找不到信任锚文件: $CUSTOMER_CA"
  if [[ ! -f "$CLOUDHSM_CA_DEST" ]] || ! cmp -s "$CUSTOMER_CA" "$CLOUDHSM_CA_DEST"; then
    if cp "$CUSTOMER_CA" "$CLOUDHSM_CA_DEST" 2>/dev/null; then
      ok "已安装信任锚到 $CLOUDHSM_CA_DEST"
    else
      warn "无写入权限，改用 sudo 复制信任锚 ..."
      sudo cp "$CUSTOMER_CA" "$CLOUDHSM_CA_DEST"
      ok "已安装信任锚到 $CLOUDHSM_CA_DEST"
    fi
  fi
}

# 把 cloudhsm-cli 指向指定 IP（写全局配置）。用于 use-cluster / restore。
switch_cli_to_ip() {
  local ip="$1"
  [[ -n "$ip" ]] || die "switch_cli_to_ip: IP 为空"
  install_trust_anchor
  if ! "$CONFIGURE_CLI" -a "$ip" 2>/dev/null; then
    warn "configure-cli 需要 root，改用 sudo ..."
    sudo "$CONFIGURE_CLI" -a "$ip"
  fi
  ok "cloudhsm-cli 已指向 $ip"
}
