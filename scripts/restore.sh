#!/usr/bin/env bash
# CloudHSM 一键恢复脚本：往指定的空集群加回一个 HSM，等待 ACTIVE，
# 并自动把 cloudhsm-cli 指向新 HSM（写 IP + 装信任锚）。
#
# 前提：目标 Cluster 仍存在（用 ./scripts/backup.sh 省钱、没删集群）。
# 新 HSM 会自动从最近备份恢复：用户和密钥都回来，无需重新 activate / 建用户。
#
# 用法：
#   ./scripts/restore.sh <别名>            # 恢复该 cluster 一个 HSM
#   ./scripts/restore.sh                   # 不带别名则用默认 cluster
#   sudo -E ./scripts/restore.sh <别名>    # 若写 /opt/cloudhsm/etc 需要 root，-E 保留环境变量
#
# 可选环境变量：
#   CLOUDHSM_AZ=ap-southeast-1a    指定 HSM 所在可用区
#   CLOUDHSM_ADMIN_PASSWORD=xxx    提供后脚本自动 login 验证恢复结果
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=config.sh
source "$SCRIPT_DIR/config.sh"

require_tools
[[ -f "$CUSTOMER_CA" ]] || die "找不到信任锚文件: $CUSTOMER_CA（恢复必须要有创建集群时的 customerCA.crt）"

resolve_cluster "${1:-}"
log "别名: $CLUSTER_ALIAS   集群: $CLUSTER_ID   区域: $REGION"

CLUSTER_JSON="$(describe_cluster)"
[[ "$CLUSTER_JSON" != "null" && -n "$CLUSTER_JSON" ]] \
  || die "找不到集群 $CLUSTER_ID。若 Cluster 也被删了，需用 create-cluster --source-backup-id 从备份重建（本脚本不处理）。"

STATE="$(printf '%s' "$CLUSTER_JSON" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("State"))')"
log "集群当前状态: $STATE"

EXISTING="$(printf '%s' "$CLUSTER_JSON" | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("Hsms",[])))')"
if [[ "$EXISTING" -gt 0 ]]; then
  warn "集群里已有 $EXISTING 个 HSM，跳过创建，直接配置本地 CLI。"
else
  if [[ -z "$PREFERRED_AZ" ]]; then
    PREFERRED_AZ="$(printf '%s' "$CLUSTER_JSON" \
      | python3 -c 'import sys,json;m=json.load(sys.stdin).get("SubnetMapping",{});print(sorted(m.keys())[0] if m else "")')"
  fi
  [[ -n "$PREFERRED_AZ" ]] || die "无法确定可用区，请设置环境变量 CLOUDHSM_AZ"
  log "在可用区 $PREFERRED_AZ 创建 HSM ..."
  NEW_HSM="$(aws_hsm create-hsm \
    --cluster-id "$CLUSTER_ID" \
    --availability-zone "$PREFERRED_AZ" \
    --query 'Hsm.HsmId' --output text)"
  ok "已发起创建: $NEW_HSM"
fi

log "等待 HSM 变为 ACTIVE（通常 5-10 分钟）..."
HSM_IP=""
for _ in $(seq 1 90); do
  read -r ACTIVE_COUNT CUR < <(describe_cluster | python3 -c '
import sys, json
hsms = json.load(sys.stdin).get("Hsms", [])
active = [h for h in hsms if h.get("State") == "ACTIVE" and h.get("EniIp")]
if active:
    print(len(active), active[0]["EniIp"])
else:
    states = ",".join(h.get("State","?") for h in hsms) or "none"
    print(0, states)
')
  if [[ "$ACTIVE_COUNT" -gt 0 ]]; then
    HSM_IP="$CUR"
    ok "HSM 已 ACTIVE，IP: $HSM_IP"
    break
  fi
  printf '\r  等待中... 状态: %s   ' "$CUR"
  sleep 20
done
[[ -n "$HSM_IP" ]] || die "等待 HSM ACTIVE 超时，请用 ./scripts/status.sh $CLUSTER_ALIAS 检查。"

# 配置 cloudhsm-cli 指向新 HSM（装信任锚 + 写 IP），复用公共函数
log "配置 cloudhsm-cli 指向 $CLUSTER_ALIAS ..."
switch_cli_to_ip "$HSM_IP"

echo
ok "恢复完成！集群 $CLUSTER_ALIAS ($CLUSTER_ID) 现在有可用 HSM。"
echo
log "验证连通性："
if "$CLOUDHSM_CLI" cluster identify >/dev/null 2>&1; then
  ok "cloudhsm-cli 能连上集群。"
else
  warn "cluster identify 未通过，稍等片刻或检查安全组/网络。"
fi

if [[ -n "$ADMIN_PASSWORD" ]]; then
  log "用 admin 登录验证用户是否已恢复 ..."
  if printf '%s\n' "$ADMIN_PASSWORD" \
      | "$CLOUDHSM_CLI" login --username admin --role admin --password-stdin >/dev/null 2>&1; then
    ok "admin 登录成功，用户与密钥已从备份恢复。"
  else
    warn "admin 自动登录失败：可能密码不对，或该集群从未 activate。手动确认即可。"
  fi
fi

echo
cat <<EOF
后续手动进入交互模式：
  $CLOUDHSM_CLI interactive
  aws-cloudhsm > login --username admin --role admin
  aws-cloudhsm > user list

切到另一个 cluster：
  ./scripts/use-cluster.sh <别名>

不用时省钱：
  ./scripts/backup.sh $CLUSTER_ALIAS
EOF
