#!/usr/bin/env bash
# CloudHSM 状态查看：集群状态、HSM 实例、最近备份、计费提示。
#
#   ./scripts/status.sh            # 列出所有已注册 cluster 的概览
#   ./scripts/status.sh s7n4       # 只看某个 cluster 的详情 + 备份
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=config.sh
source "$SCRIPT_DIR/config.sh"

require_tools

# 打印单个 cluster 的详情
show_one() {
  resolve_cluster "$1"
  log "别名: $CLUSTER_ALIAS   集群: $CLUSTER_ID   区域: $REGION"

  local info
  info="$(aws_hsm describe-clusters --filters clusterIds="$CLUSTER_ID" \
    --query 'Clusters[0].[State,HsmType,BackupPolicy]' --output text 2>/dev/null || true)"
  if [[ -z "$info" || "$info" == "None"* ]]; then
    warn "找不到集群 $CLUSTER_ID（可能已删除或 region 不对）"
    echo
    return
  fi
  read -r C_STATE C_TYPE C_BACKUP <<<"$info"
  echo "集群状态 : $C_STATE"
  echo "HSM 类型 : $C_TYPE"
  echo "备份策略 : $C_BACKUP"

  local hsm_lines hsm_count
  hsm_lines="$(aws_hsm describe-clusters --filters clusterIds="$CLUSTER_ID" \
    --query 'Clusters[0].Hsms[].[HsmId,State,AvailabilityZone,EniIp]' --output text 2>/dev/null || true)"
  if [[ -n "$hsm_lines" ]]; then
    hsm_count="$(printf '%s\n' "$hsm_lines" | grep -c .)"
    echo "HSM 数量 : $hsm_count"
    printf '%s\n' "$hsm_lines" | while read -r id st az ip; do
      echo "   - $id  $st  AZ=$az  IP=$ip"
    done
  else
    hsm_count=0
    echo "HSM 数量 : 0"
    echo "   （无 HSM —— 省钱状态，不产生 HSM 计费）"
  fi

  echo "最近备份："
  aws_hsm describe-backups \
    --filters clusterIds="$CLUSTER_ID" \
    --no-sort-ascending \
    --query 'Backups[:5].[BackupId,BackupState,CreateTimestamp]' \
    --output table 2>&1 || warn "无法列出备份"

  if [[ "$hsm_count" -gt 0 ]]; then
    warn "$CLUSTER_ALIAS 有 $hsm_count 个 HSM 在计费（hsm2m.medium 约 \$2.28/HSM/小时）。不用时: ./scripts/backup.sh $CLUSTER_ALIAS"
  else
    ok "$CLUSTER_ALIAS 当前无 HSM 计费。需要时: ./scripts/restore.sh $CLUSTER_ALIAS"
  fi
  echo
}

if [[ $# -ge 1 ]]; then
  show_one "$1"
else
  log "所有已注册 cluster 概览："
  echo
  for a in "${!CLUSTERS[@]}"; do
    show_one "$a"
  done
fi
