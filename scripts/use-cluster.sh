#!/usr/bin/env bash
# 一键切换 cloudhsm-cli 当前连接的 cluster。
#
# 原理：cloudhsm-cli 全局只有一份配置 /opt/cloudhsm/etc/cloudhsm-cli.cfg，
# interactive 连的就是里面写的 HSM IP。切换 = 把配置指向目标 cluster 当前的 HSM IP。
# （两个 cluster 同一个 CA，信任锚通用，无需更换 customerCA.crt。）
#
# 用法：
#   ./scripts/use-cluster.sh            # 显示当前指向 + 可用别名
#   ./scripts/use-cluster.sh c1         # 切到别名 c1 对应的 cluster
#   ./scripts/use-cluster.sh c2         # 切到别名 c2 对应的 cluster
#
# 切完直接： /opt/cloudhsm/bin/cloudhsm-cli interactive
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=config.sh
source "$SCRIPT_DIR/config.sh"

require_tools

# 不带参数：显示当前 CLI 指向的 IP，并列出别名
if [[ $# -eq 0 ]]; then
  CUR_IP="$(python3 -c '
import json,sys
try:
    cfg=json.load(open("/opt/cloudhsm/etc/cloudhsm-cli.cfg"))
    ips=[s["hostname"] for c in cfg.get("clusters",[]) for s in c["cluster"]["servers"]]
    print(",".join(ips) if ips else "（未配置）")
except Exception as e:
    print("（读不到配置: %s）" % e)
')"
  log "cloudhsm-cli 当前指向 IP: $CUR_IP"
  echo "可用 cluster 别名："
  list_aliases
  echo
  echo "用法: ./scripts/use-cluster.sh <别名>"
  exit 0
fi

resolve_cluster "$1"
log "切换到 $CLUSTER_ALIAS ($CLUSTER_ID) ..."

IP="$(active_hsm_ip)"
[[ -n "$IP" ]] || die "$CLUSTER_ID 当前没有 ACTIVE 的 HSM。若已省钱删除，请先: ./scripts/restore.sh $CLUSTER_ALIAS"

switch_cli_to_ip "$IP"
echo
ok "已切到 $CLUSTER_ALIAS。现在可以运行："
echo "  $CLOUDHSM_CLI interactive"
