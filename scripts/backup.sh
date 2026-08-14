#!/usr/bin/env bash
# CloudHSM 停用/省钱脚本：删除指定集群内所有 HSM 实例，保留空集群。
#
# 说明：
#   - 删除最后一个 HSM 时 AWS 自动生成最终备份，用户+密钥不丢。
#   - 只要不删 Cluster，备份一直保留，随时可用 ./scripts/restore.sh 恢复。
#   - 空 Cluster 不计费。
#
# 用法：
#   ./scripts/backup.sh <别名>         # 交互确认后删除该 cluster 所有 HSM
#   ./scripts/backup.sh <别名> -y      # 跳过确认
#   ./scripts/backup.sh                # 不带别名则用默认 cluster
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=config.sh
source "$SCRIPT_DIR/config.sh"

# 解析参数：第一个非 -y 的参数当别名，-y/--yes 跳过确认
ASSUME_YES=false
ALIAS_ARG=""
for arg in "$@"; do
  case "$arg" in
    -y|--yes) ASSUME_YES=true ;;
    *) ALIAS_ARG="$arg" ;;
  esac
done

require_tools
resolve_cluster "$ALIAS_ARG"
log "别名: $CLUSTER_ALIAS   集群: $CLUSTER_ID   区域: $REGION"

CLUSTER_JSON="$(describe_cluster)"
[[ "$CLUSTER_JSON" != "null" && -n "$CLUSTER_JSON" ]] || die "找不到集群 $CLUSTER_ID"

mapfile -t HSM_IDS < <(printf '%s' "$CLUSTER_JSON" \
  | python3 -c 'import sys,json;[print(h["HsmId"]) for h in json.load(sys.stdin).get("Hsms",[])]')

if [[ "${#HSM_IDS[@]}" -eq 0 ]]; then
  ok "集群 $CLUSTER_ALIAS 已经没有 HSM，无需操作（已处于省钱状态）。"
  exit 0
fi

log "将删除以下 HSM 实例（共 ${#HSM_IDS[@]} 个）："
for id in "${HSM_IDS[@]}"; do echo "   - $id"; done

if ! $ASSUME_YES; then
  echo
  warn "删除后 $CLUSTER_ALIAS 将没有可用 HSM，直到执行 ./scripts/restore.sh $CLUSTER_ALIAS。数据不丢（自动备份）。"
  read -r -p "确认删除？输入 yes 继续: " ans
  [[ "$ans" == "yes" ]] || die "已取消"
fi

for id in "${HSM_IDS[@]}"; do
  log "删除 $id ..."
  aws_hsm delete-hsm --cluster-id "$CLUSTER_ID" --hsm-id "$id" >/dev/null
  ok "已发起删除 $id"
done

log "等待所有 HSM 删除完成 ..."
for _ in $(seq 1 60); do
  remaining="$(describe_cluster | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("Hsms",[])))')"
  if [[ "$remaining" -eq 0 ]]; then
    ok "$CLUSTER_ALIAS 所有 HSM 已删除，集群现在为空，HSM 计费已停止。"
    log "恢复时执行: ./scripts/restore.sh $CLUSTER_ALIAS"
    exit 0
  fi
  printf '\r  剩余 HSM: %s  ' "$remaining"
  sleep 10
done

warn "等待超时，请稍后用 ./scripts/status.sh $CLUSTER_ALIAS 确认。"
